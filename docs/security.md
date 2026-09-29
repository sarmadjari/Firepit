# Firepit security

What Firepit protects, how, and where each claim is implemented. Written to be
checked rather than believed: every guarantee below names the file that provides
it and the test that holds it in place.

Firepit is a group messenger that runs over the [Meshtastic](https://meshtastic.org)
LoRa mesh. There is no server and no account, and nothing Firepit says to
anybody needs the internet. The one exception is map tiles (§1). Everything
here is about what travels on the air and what sits on the phone.

- **Platform:** Android, `minSdk 29`. iOS is not built yet.
- **Radio firmware:** Meshtastic 2.7 or newer. Older is refused, not degraded (§9).
- **Wire formats:** `protos/meshchat/meshchat.proto`, generated with Wire.

---

## 1. What we defend against

| Adversary | Position | Outcome |
|---|---|---|
| Passive listener on the mesh | Any radio in range | Sees traffic exists. Cannot read room messages, direct messages, positions or pins. |
| Another Meshtastic user | Holds the published primary key | Sees a node broadcast NodeInfo. Cannot read any conversation, and nothing they send on the primary is kept as a message or a pin. |
| Someone who photographs an invite | Camera on the QR | Gets no key, but does learn the room id. Can ask to join for about 30 seconds, which a person must approve after comparing a fingerprint of the joiner's radio and phone keys. Cannot use the room id to join the roster or change the room's keys. |
| A relay carrying our packets | Between two nodes | Forwards ciphertext. Cannot read or alter anything Firepit sends undetected. |
| Someone holding a member's radio | Physical access, USB, or a Bluetooth pairing | Reads the channel layer: the room PSK, the radio's own name and battery, and its node list. **Cannot** read sealed room text, positions or pins, or direct messages between Firepit users: the room key only ever travels sealed to a phone key (§3), and direct messages are sealed phone to phone, neither of which the radio holds. Unsealed text, pins or positions they send into a room are dropped. |
| Someone nearby with Bluetooth | In range while the owner's phone is not connected | Can pair only with the radio's PIN. Firepit checks on every connection whether the radio has no PIN or the published default one, and offers to set a new one (§7). |

### What we do not defend against

Stated plainly, because a threat model that claims everything is worthless.

- **A member of a room.** Anyone invited holds the room key and can read and
  keep everything — words, positions and pins — and can seal anything claiming
  to come from another member. Rotation (§6) is the only recourse and it is not
  retroactive.
- **A member lying about another member's phone key.** A member can announce a
  false phone key for somebody else in the room (§6). The next rotation to that
  person is sealed to the false key but still travels PKI to their own radio, so
  the liar cannot read it — but that person misses the new key and has to be
  invited again.
- **Replayed sealed messages.** The sealing context binds a message to its room
  and sender but not to its packet, so someone holding the room PSK can
  re-broadcast a recorded sealed message and it shows as new.
- **A compromised or unlocked phone.** The database and every key are encrypted
  at rest (§4), so a copy of the app's files reads as noise. An attacker running
  code as the app, or holding the unlocked phone, reads what the app reads.
- **Traffic analysis.** LoRa is broadcast. Who transmits, when, how often and
  how much is visible to anyone listening, regardless of encryption. Within a
  room every sealed packet asks for an acknowledgement, so that one header bit
  does not tell words from receipts, pins or positions.
- **Direct messages to people outside our rooms.** Somebody whose phone key we
  never learned — anyone who has not shared a room with us, which includes
  everybody not running Firepit — can only be reached through the radios'
  PKI, which whoever holds either radio can read. The composer says so on every
  such message.
- **Forged positions and telemetry from outside our rooms.** A member's position
  is only believed sealed. Anyone else's comes from unauthenticated firmware
  broadcasts, as do battery and signal figures, and anyone in range can forge
  those for any node number.
- **Map tiles.** The map fetches tiles from `tiles.openfreemap.org` over the
  internet, which learns your IP address and the area shown. Online, the map
  does not move to where the group is until asked, requests carry a User-Agent
  that does not name the app, and a finished download offers offline-only mode,
  which stops tile requests altogether.
- **Sharing with the phone away.** Positions are sealed by the phone, so sharing
  pauses while the phone is out of reach of its radio. A radio left somewhere
  shares nothing on its own.

---

## 2. Primitives

No cryptography is invented here. Everything is platform-provided, except
SQLCipher, which is the standard for encrypting SQLite.

| Purpose | Construction | Parameters |
|---|---|---|
| Sealing room content | AES-256-GCM | 32-byte key, 12-byte random nonce, 16-byte tag |
| Handing a room key to one phone | ECDH P-256 (one-off key) + HKDF-SHA256 + AES-256-GCM | 33-byte compressed keys, bound to room id + generation + recipient |
| Direct messages between phones | ECDH P-256 (both phones' keys) + HKDF-SHA256 + AES-256-GCM | bound to sender + recipient, in that order |
| Direct messages, radio layer | X25519 + AES-CCM | Meshtastic firmware PKI, 32-byte public keys; the outer layer only |
| Invite tokens | HMAC-SHA256 | truncated to 8 bytes |
| Key derivation | HMAC-SHA256 | context string + room id + generation |
| Database at rest | SQLCipher 4 (AES-256, per page, HMAC-SHA512) | random 32-byte raw key, wrapped by the Keystore |
| Secrets in preferences | Android Keystore AES-256-GCM | key never leaves secure hardware |
| Randomness | `java.security.SecureRandom` | all keys, nonces, room ids |

Implemented in `core/crypto/RoomCipher.kt`, `core/crypto/KeyEnvelope.kt`,
`core/crypto/DirectSeal.kt`, `core/crypto/RoomCrypto.kt` and
`core/database/DatabaseEncryption.kt`.

**A peer's P-256 key is checked to be on the curve before it is used.** Agreeing
on a point that is not is how a long-lived private key leaks a few bits at a
time.

**Nonces are random and transmitted**, never derived from a packet id. A packet
id is not ours to guarantee unique, and a repeated nonce under the same key
breaks GCM completely.

**Sealed content is bound to its room and sender.** `SealedText.contextOf(roomId,
senderNodeNum)` is passed as GCM additional authenticated data — authenticated
but not transmitted. A sealed message lifted into another room, or re-attributed
to a different sender, fails to open. And a sealed message is only believed on
the slot of the room it names, or privately to us (`TrustRules.sealedPlacementOk`),
so someone in two rooms cannot have one room's words shown in the other. A
direct message is bound the same way to its sender and recipient
(`DirectSeal.contextOf`), so it cannot be turned round or re-addressed, and only
the sender's phone key can produce one that opens.

> Verify: `RoomCipherTest`, `SealedTextTest`, `SealedRoomTextTest`, `SealedReceiptTest`,
> `KeyEnvelopeTest`, `DirectSealTest`, `TrustRulesTest`

---

## 3. Key hierarchy

Three kinds of key, deliberately.

```
room PSK (32 bytes, random)          →  written to the radio
  └─ invite key   = HMAC(psk, "meshchat-invite-v1" || room_id || generation)

firepit key (32 bytes, random)       →  on phones only; travels only sealed to a phone key
  └─ seals room text, positions, pins, person cards, receipts,
     roster events and syncs, key rotations and the notice that one happened

phone key (P-256, one per phone)     →  private half never leaves the phone
  ├─ receives a room's firepit key in a grant or a rotation
  └─ agrees a key with another phone for direct messages and their receipts
```

The split is the point. The **room PSK** is what the firmware encrypts the
channel with, so anyone holding the radio — or a stolen radio — has it. The
**firepit key** exists only in phone storage, so the same person gets a
timestamp and a node number and nothing to read.

The firepit key does have to travel, to a new member or after a rotation, and
the only private path to one person is a PKI direct message — which the
receiving *radio* decrypts, with a private key that anyone holding that radio
can read out over Bluetooth. So the firepit key never rides that layer alone:
it is sealed again to the recipient's **phone key** (`KeyEnvelope`), and the
radio carries something it cannot open.

A room key is never derived from a room name or anything guessable, and never
empty: the firmware treats an empty PSK as "inherit the primary", which would
silently create a room every nearby radio could read. A room whose slot has to
move is read back first, and a read that fails stops the move rather than
writing the room with no key.

---

## 4. Storage

Room keys must be able to leave the phone — sealed to another phone's key in a
grant or a rotation — so they cannot live inside the Android Keystore itself.
Instead each is wrapped by an AES-256-GCM key that never leaves secure hardware,
and only the wrapped form is written to preferences.

- `core/database/KeystoreWrapping.kt` — wrap/unwrap, one implementation
- `core/data/RoomKeyStore.kt` — room keys, per generation
- `core/data/PhoneKeyStore.kt` — this phone's own key pair
- `core/data/PrimaryBackup.kt` — the radio's original channel, which contains
  somebody else's mesh PSK
- `core/database/DatabaseEncryption.kt` — the database's key

**The database is encrypted.** Messages are stored opened, and so are
positions, names, rosters, pins and receipts, so the file itself is encrypted
with SQLCipher under a random key the Keystore wraps. Android's own disk
encryption only protects app files until the phone is first unlocked after a
restart; a copy taken after that — a forensic extraction of a locked phone,
malware with root, a backup the OS gets wrong — is noise without the Keystore.
An unencrypted database from an older version is rewritten encrypted on first
open and the plain file deleted; if that fails, it is deleted anyway rather
than left readable. The key stays usable while the phone is locked, because
messages arrive in a pocket.

If a wrapping key is gone — app data restored onto a different phone, or the
Keystore reset — unwrapping returns null rather than throwing. What it
protected is lost, which is correct, but it is not a reason to crash. Removing
the screen lock does not destroy these keys: they are not bound to user
authentication.

**Old generations are kept**, so a packet sealed just before a rotation that
arrives after it still opens; the mesh delivers late. History itself is stored
opened, in the encrypted database.

**Nothing is kept longer than asked.** The retention window deletes messages,
and with them positions older than the window, nodes not heard in it that share
no room with us, expired pins, and the map tiles cached while browsing. Person
cards and phone keys go when nobody they belong to shares a room with us, and
leaving a room takes its history, pins, roster and every key it had. Settings
can erase the history outright. A room nobody has spoken in for the chosen
lifetime is left automatically, judged on when it last had anything said,
pinned or shared, which is recorded as it happens.

**Nothing leaves in a backup.** `allowBackup` is off and the extraction rules
exclude every storage area from cloud backup and device transfer.

---

## 5. What may go on the air

The rule is not "always encrypt" — that would make talking to people outside
Firepit impossible. It is **never downgrade silently**.

`core/protocol/MessagePrivacy.kt` decides carriage as data, not as branches
inside a sender:

| Conversation | Carriage | Readable by |
|---|---|---|
| Firepit room | `SealedRoom` — AES-GCM inside the channel cipher, on `PRIVATE_APP` | Room members holding the firepit key |
| One person who shares a room with us | `SealedDirect` — sealed to their phone key, inside firmware PKI to their radio | That person's phone |
| One person whose phone key we never learned | `ToOneNode` — firmware PKI to their radio key only, labelled as such in the composer | That person, and whoever holds either radio |
| Meshtastic channel | `OpenChannel` — ordinary `TEXT_MESSAGE_APP` | Anyone with the channel key, by design |
| Anything else | `Refused` | Not sent |

A refusal is surfaced to the user with a reason (`NO_PEER_KEY`, `NOT_A_ROOM`,
`NOT_ENCRYPTED`, `ROOM_KEY_MISSING`, `ROOM_MOVED_ON`). Firepit never falls back
to the channel key for a direct message — that is precisely what Meshtastic did
before 2.5, where "anyone in the channel could read all your direct messages" —
and never for a Firepit room either: a room still on the radio whose key this
phone lost, or which moved to a key that never reached us, is refused rather
than treated as an ordinary channel.

**Positions and pins are sealed like words.** The phone seals its own fix under
the room's key and sends it at the beacon rate while sharing is on
(`LocationRepository`); pins travel the same way (`WaypointRepository`). The
radio's own position broadcast is kept off on every channel, re-asserted on
every connection (`PositionSharing.writesToSilence`), because it would go out
under the channel key and carry on with the phone away. "Where are you?" is a
sealed question answered by the other phone, and only while it is sharing with
that room (`TrustRules.positionQueryAnswerable`).

### Enforced at construction

Rules that were each broken at least once by a feature written before they
existed are asserted in `MeshPacketBuilder.meshPacket`, so an unsafe packet
cannot be built:

```kotlin
require(!pkiEncrypted || publicKey.size == PUBLIC_KEY_SIZE)
require(to == BROADCAST_NODENUM || portNum != TEXT_MESSAGE_APP || pkiEncrypted)
require(portNum !in LOCATION_PORTS || channel != PRIMARY_SLOT)
require(portNum != TRACEROUTE_APP || channel != PRIMARY_SLOT)
```

In order: PKI needs a real key; a direct text message must be encrypted to its
recipient; positions and waypoints never go on the primary; a traceroute names
every node that carried it and the primary's key is held by every Firepit radio.

### What is believed on the way in

Everything that arrives is attacker-controlled, so what is kept is decided in
`core/protocol/TrustRules.kt`:

- **Unsealed text** is kept as a direct message only when the firmware decrypted
  it with the sender's key, never from the primary, and never on a Firepit
  room's slot, where anything real arrives sealed.
- **Sealed direct messages** are only opened when they came privately to us,
  from somebody whose phone key we hold; opening proves their phone sealed it.
  One that will not open is answered with a `SealedDirectRefused`, and the
  sender marks it as not opened rather than leaving it reading as delivered.
- **Pins** are only taken sealed under a room's current key, on its slot. A pin
  can only be locked to whoever sealed it, a locked pin changes only at its
  owner's hand, and a pin never moves to another room.
- **Positions** of anyone in our rooms are only believed sealed under the
  room's current key; an unsealed one naming a member was written by whoever
  holds a radio (`TrustRules.unsealedPositionAcceptable`).
- **Receipts** are only recorded sealed — under a room key, or phone to phone —
  and only from the person a direct message went to, or a member of the room a
  room message was sent in. Nobody gets a receipt whose phone key we do not
  hold, so a stranger cannot learn this phone is on and reading.
- **Delivery ticks** move to "delivered" only on the recipient's own
  acknowledgement; anybody else answering a packet id is ignored
  (`MessageStatusRules.fromRouting`).
- **Person cards** are only taken sealed under a room key we share. PKI alone is
  not enough: anyone can encrypt to us.

**On the phone's own surfaces.** Notifications name nobody unless the owner
turns that on, carry a public version for a secure lock screen, and are not
bridged to a watch. Screens are kept out of screenshots, screen recordings and
the Recents snapshot unless the owner allows it; the invite screen always is.
Every text field asks the keyboard not to learn from it.

> Verify: `MessagePrivacyTest`, `MeshPacketSafetyTest`, `PositionPrecisionTest`
> (`PositionSharingTest`), `TrustRulesTest`, `MessageStatusRulesTest`, `ReceiptRulesTest`,
> `ProtocolContractTest` (every sealed payload fits its packet at its limits)

---

## 6. Invites

The part most worth auditing, because an invite is how a stranger becomes
someone who can read everything.

### The code carries no keys

```protobuf
message Invite {
  reserved 4, 5, 13;              // held open: a key here is in every photograph
  uint32 version = 1;
  fixed32 room_id = 2;
  string room_name = 3;
  uint32 generation = 6;
  LoRaProfile lora = 7;
  Inviter inviter = 8;            // includes the inviter's 32-byte public key
  fixed32 invite_id = 9;
  uint32 issued_at = 10;
  uint32 window = 11;
  bytes token = 12;
}
```

A photograph of the QR yields a room name, a room id, the inviter's public key,
and a token that stops being accepted in about thirty seconds. It does not yield
a way to read anything.

### Handshake

```
inviter                                   joiner
   │  QR: room id, name, mode, pubkey, token
   │ ────────────────────────────────────►  scan
   │                                        shows inviter id + key fingerprint;
   │                                        nothing is sent until the reader
   │                                        confirms it matches the inviter's screen
   │
   │    JoinHello { invite_id, token, joiner_key, phone_key }
   │  ◄──────────────────────────────────── PKI to inviter's key
   │
   ├─ rate limit · bound to its key · one-shot · 0 hops · token check
   ├─ ask the person holding the phone, showing a fingerprint of joiner_key
   │  and phone_key together
   │
   │    RoomGrant { room_psk, generation, sealed_key = seal(phone_key, firepit key) }
   │ ────────────────────────────────────►  PKI to joiner_key
   │                                        writes the channel, stores keys
   │    SealedMessage { RosterSync }        sealed under the key just granted
   │ ────────────────────────────────────►  PKI to joiner_key
```

Every check on the inviter's side narrows what a stolen code is worth:

| Check | Why | Where |
|---|---|---|
| Rate limit, 5/minute per node | A stranger who cannot pass the token check has no reason to keep trying | `admitAttempt` |
| Decrypted with the key it names | The grant goes to that key; a merely claimed one would let a spoofed hello point the room's keys at somebody else | `TrustRules.helloIsBound` |
| Invite not already used | A code stops being worth presenting once the person it was shown to is in; the next code shown has a new id | `IssuedInvite.usedBy` |
| Zero hops | A code is shown to somebody in front of you; anything relayed was read somewhere you cannot see | `PacketOrigin.arrivedDirectly` |
| Token minted by us, recently | Proves the code is genuine | `RoomCrypto.matchesRecentToken` |
| A second hello cannot swap keys | The first one is what the person in front of you is checking | `TrustRules.mayReplacePending` |
| **A person says yes, having compared fingerprints** | Proves the bearer is the person it was shown to — which no token can | `approveJoin`, `RoomJoinPrompts` |

The last row is the one that matters. A token proves the *invite* is genuine, not
that the *bearer* was authorised. Without human approval, a photographed code
used inside the window would still yield the keys. Both phones show one line
computed over the joiner's radio key **and** phone key
(`KeyFingerprint.ofJoin`); if the two read out differently, the hello did not
come from the phone in front of you. The phone key has to be in it: that is
what the room's key is sealed to, and a line over the radio key alone would let
whoever held the joiner's radio ask in with a phone of their own.

**The hop rule fails closed.** `hop_start` and `hop_limit` are not authenticated,
so the count derived from them is a claim rather than a measurement. A sender
three hops away can leave `hop_start` at zero to make the distance look
unknowable while setting `hop_limit` high enough to be relayed anyway — the
packet then arrives claiming a *negative* distance, which no honest packet can.
Only `hop_start == hop_limit` is treated as adjacent; everything else, including
every impossible pair, is refused.

**The grant's outer layer goes to the key the firmware decrypted the hello
with**, which must equal the key the hello names (`MeshPacket.public_key` is set
by the firmware on PKI decryption). The room's own key inside goes further: it
is sealed to the joiner's phone key, so the two radios carry it without being
able to read it.

**The joiner accepts a grant only from the node it scanned.** The room id and
invite id both travel in the QR code, so anyone who photographed it can name them
and could otherwise answer first with their own keys — putting the scanner in
*their* room, under a name they expected, without either real party noticing.
Answering as the scanned node is a different matter: the joiner seeded that
node's public key from the code itself (`add_contact`, step A), so the firmware
will only decrypt a reply that was genuinely signed by its holder. The check is
`packet.from == awaited.inviter` on a packet that must already be
`pki_encrypted`; either alone is insufficient.

**A scanned code cannot rewrite what the radio already knows.** The code's
inviter key is added to the radio only when the radio has no key for that node;
a code naming a known node under a different key is refused
(`TrustRules.contactFor`). And a grant for a room this phone already holds is
only taken from a member of it, moving it forward (`TrustRules.mayTakeGrant`) —
otherwise a stranger's code naming one of our room ids could overwrite that room
with keys they chose.

### Rosters and rotation

The roster decides who is handed the next key, so what may add to it is narrow:

- our own grant, or our own approval of a joiner;
- a `RosterEvent { JOINED }` sealed under the room's current key, on the room's
  slot, from the member who vouched (`handleRosterEvent`);
- a `RosterSync` sent privately by the inviter who let us in, sealed under the
  room's current key, while they are still a member
  (`TrustRules.rosterSyncAcceptable`);
- anyone whose sealed message opens under the room's **current** key. An older
  generation's key is what a removed member still holds, so it reads history but
  vouches for nobody, and neither introduces people nor says who anyone is.

Nothing unsealed adds a member: on a room's slot, that is somebody holding a
member's radio.

A **rotation** removes somebody by moving everyone else to new keys. In order
(`RoomRepository.rotateRoom`):

1. A `RosterEvent { KEY_ROTATED }` goes to the room **sealed under the key being
   replaced, on the channel key being replaced**, before the sender's own radio
   moves. A member who then misses their own copy of the new key still hears
   that the room has gone: their phone marks the room as moved on and stops
   sending in it (`RoomKind.FIREPIT_MOVED_ON`), instead of talking into a room
   only the removed member can still read. Their own copy of the new key clears
   the mark. Only the very next generation is believed
   (`TrustRules.rotationNoticeAcceptable`): the removed member still holds the
   old key and could otherwise name a generation no real key ever reaches.
2. The sender's radio moves to the new channel key.
3. Each remaining member is sent the rotation as a PKI direct message, after
   their radio key is put back into our radio's node list with `add_contact`
   (it evicts, and the firmware refuses to encrypt to a node it forgot). The new
   firepit key inside is sealed to that member's phone key, and the whole
   rotation is sealed under the key it replaces, so only somebody who holds the
   room can move it on — anyone can encrypt to a public key, so PKI alone proves
   nothing about membership. It is sealed under the generation *that member*
   holds, which is older than the one being replaced if they also missed an
   earlier rotation, and carries every removal they missed. A member counts as
   reached **only when their own radio acknowledges the packet**
   (`MeshRepository.sendAwaitingAck`); our radio accepting it proves nothing
   about where it went.
4. Everyone owed the key is recorded (`pending_handovers`, no secrets) *before*
   the handovers go out, in the same all-or-nothing step as the notice and our
   own key change, so closing the app mid-rotation strands nobody. A record is
   cleared on confirmation; the rest are handed the key again the next time
   anything is heard from them, at most every ten minutes.

It is accepted only from a member, privately, sealed under the current
generation, and moving forward (`TrustRules.rotationAcceptable`). The person
removing sees who confirmed and who has not yet.

**Phone keys** arrive in the join hello, in the sealed `JOINED` event that
introduces a newcomer, and in person cards, which every phone sends to its rooms
even when nobody has chosen a name. They are learned on first sight and then
kept (`TrustRules.shouldStorePhoneKey`). Only a join a person approved replaces
one — our own approval, or the sealed `JOINED` from whoever gave it — which is
how somebody who returns with a new phone stays reachable. A member whose phone
key is unknown cannot be handed a new key, and is reported as not yet reached,
like a member who is out of range.

A **lost or lent radio** can be removed from every room it is in from the
device list (`RoomRepository.removeFromAllRooms`), which rotates each of them
without it.

### Timing

| Constant | Value | Meaning |
|---|---|---|
| `ROTATION_SECONDS` | 8 s | how often the code is redrawn |
| `WINDOW_TOLERANCE` | ±2 windows | clock skew between two phones |
| `DEFAULT_LOOKBACK_WINDOWS` | 4 (~32 s) | how long the inviter accepts its own token |

The lookback is short because the inviter's public key is in the code, so the
joiner replies immediately — nothing has to propagate first.

**The joiner cannot verify the token.** It is an HMAC under the room key, which
they do not have and are not yet trusted with. They check the window is fresh,
which fails an obviously old photograph on the spot; the inviter proves the
token before granting anything.

### Screen capture

The invite screen always sets `FLAG_SECURE`, whatever the screenshot setting,
so the code cannot be screenshotted or screen-recorded and cannot reach a photo
backup (`app/privacy/SecureWindow.kt`). A camera pointed at the screen is still
a camera — which is what the approval step is for.

> Verify: `InvitePrivacyTest` (asserts on encoded bytes that no 32-byte key
> appears in an invite, and that a grant's key only opens for the joiner's
> phone), `RoomCryptoTest`, `ScanDisambiguationTest`, `KeyEnvelopeTest`,
> `KeyFingerprintTest`, `TrustRulesTest`, `ProtocolContractTest` (a sealed
> rotation and a sealed roster sync still fit one PKI packet), `PacketOriginTest`
> (every hop pair, including the impossible ones)

---

## 7. Metadata and the radio

What an observer learns at each layer, assuming they hold the relevant key.

| Layer | Visible without any key | Visible with the channel key | Visible with the firepit key |
|---|---|---|---|
| LoRa | that a transmission happened, its length, sender node number, whether it asks for an acknowledgement | — | — |
| Primary channel | — | node names, battery, hardware model | — |
| Room channel | — | that a `PRIVATE_APP` packet exists, its room id and generation, sender, timestamp, size | the words, positions, pins, names and receipts |
| Direct message | sender, recipient, timing, size | — (PKI) | — (sealed phone to phone) |

The primary channel's key is **published in this repository**
(`protos/meshchat-primary-key.txt`) and ships in every copy of the app. It is a
community key, not a secret: its purpose is that Firepit radios find each
other, and it protects nothing. Making a radio "private to Firepit" hides its
name and battery from ordinary Meshtastic radios, not from anyone running
Firepit, and the app says so. Nothing else is placed on it — no positions, no
waypoints, no traceroutes, no conversation.

Firepit rooms never bridge to MQTT (`uplink_enabled` and `downlink_enabled` are
both false on every room channel). A radio's own MQTT settings are another
matter: with MQTT on and any channel uplinked, the firmware uploads every PKI
packet it carries as well. Firepit checks for that on every connection. Other
people's gateways can still upload what they relay, which for our traffic is
ciphertext and its header.

**The radio's own settings** are checked on every connection
(`core/protocol/RadioSecurityCheck.kt`), each with a one-tap fix that writes
back the radio's own section with only that setting changed:

| Finding | Why it matters |
|---|---|
| Bluetooth with no PIN, or the published default `123456` | anyone nearby can pair while the owner's phone is not connected, and read or change everything on the radio |
| A remote admin key | whoever holds it reads the channel keys over the mesh, even after a rotation |
| Managed mode | the radio ignores this phone; reported, not fixable from here |
| The legacy admin channel, debug logging to apps | other ways in |
| MQTT uplink or map reporting | traffic or the radio's position leaves over the internet |

A saved radio's node number and key are pinned the first time it connects; a
radio answering at the same address as somebody else is flagged rather than
silently accepted. **Forgetting** the connected radio can take Firepit's rooms
off it first and put its own primary channel back, so whoever has it next
holds none of the rooms' channel keys.

What a radio keeps in its own flash — channel keys, its node list, its private
key — is readable over USB by anyone who has it in their hands. That is why no
Firepit key, word, pin or position is ever given to it unsealed.

## 8. Time

Timestamps affect what a reader believes, so they are treated as input rather
than as fact. `core/protocol/RadioClock.kt`:

- Stamps from our own radio are corrected by the measured clock offset, and can
  never read later than now.
- Stamps from another node are believed only while they could be true: not zero,
  not more than five minutes ahead, not older than thirty days.
- Anything we observe ourselves uses the phone clock.

An unconfigured radio can sit at 1970 or drift by days. In testing, two radios
were 305 and 282 seconds out.

> Verify: `RadioClockTest`

---

## 9. Firmware floor

Firepit requires **2.7.0 or newer** and refuses to connect below it rather than
degrading. A radio that is too old is disconnected politely so it frees its
PhoneAPI slot, and the refusal is terminal — retrying would only reach the same
firmware. A radio that reports no readable version is let through: refusing
would block radios that simply did not say, and the capabilities that matter
are read from the device either way.

Capabilities are read from the device, never inferred from a version string.
`supportsSigning` is 2.8-only and nothing depends on it.

> Verify: `FirmwareVersionTest`, `PhoneApiSessionTest`

---

## 10. Auditing this

```bash
cd android
./gradlew build          # compiles, lints, runs every test
./gradlew :core:crypto:test --rerun-tasks
```

Worth reading in order:

1. `core/protocol/MessagePrivacy.kt` — the carriage rule
2. `core/protocol/TrustRules.kt` — what is believed on the way in
3. `core/protocol/MeshPacketBuilder.kt` — what cannot be built
4. `core/crypto/RoomCipher.kt` — the sealing construction
5. `core/crypto/KeyEnvelope.kt` — how a room key reaches one phone
6. `core/crypto/DirectSeal.kt` — how one person's words reach one phone
7. `core/data/RoomRepository.kt` — invites, grants, rosters, rotation
8. `core/data/LocationRepository.kt`, `core/data/WaypointRepository.kt` — sealed positions and pins
9. `core/protocol/RadioSecurityCheck.kt` — what is checked on the radio itself
10. `core/data/RoomKeyStore.kt`, `core/data/PhoneKeyStore.kt`,
    `core/database/DatabaseEncryption.kt` — storage

Every `link.send` in the codebase is in `core/data`. No packet is constructed in
the UI layer, so there is no path around the rules above.

To watch a real exchange:

```bash
adb logcat -s FirepitRooms:I FirepitLink:I FirepitLocation:I
```

No key material is ever logged. Log lines report absence ("no public key for X")
but never contents.

---

## Reporting

Security issues are more useful than feature requests. If something here is
wrong, the claim and the code disagree, or a test passes while the property it
names does not hold, please say so.
