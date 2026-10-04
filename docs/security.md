# Firepit security

What Firepit protects, how, and where each claim is implemented. Written to be
checked rather than believed: every guarantee below names the file that provides
it and the test that holds it in place.

Firepit is a group messenger that runs over the [Meshtastic](https://meshtastic.org)
LoRa mesh. There is no server and no account, and nothing Firepit says to
anybody needs the internet. The one exception is map tiles (§1). Everything
here is about what travels on the air and what sits on the phone.

- **Platforms:** Android (`minSdk 29`) and iPhone (iOS 17). Both apps follow every rule
  below and send the same bytes. They differ only in how each operating system
  protects the phone itself (§4, §5, §11).
- **Radio firmware:** Meshtastic 2.7 or newer. Older is refused, not degraded (§9).
- **Wire formats:** `protos/meshchat/meshchat.proto`, generated with Wire on Android
  and SwiftProtobuf on iOS.
- **Code references** are to the Android app, the reference implementation, with
  paths under `android/`. Each type named has a Swift twin of the same name in
  `ios/Packages/FirepitKit`, except the per-platform storage covered in §4.
  §10 lists the iOS files and tests.

---

## 1. What we defend against

| Adversary | Position | Outcome |
|---|---|---|
| Passive listener on the mesh | Any radio in range | Sees traffic exists. Cannot read room messages, direct messages, positions or pins. |
| Another Meshtastic user | Holds the published primary key | Sees a node broadcast NodeInfo. Cannot read any conversation, and nothing they send on the primary is kept as a message or a pin. |
| Someone who photographs an invite | Camera on the QR | Gets no key, but does learn the room id. Can ask to join for about 30 seconds, which a person must approve after comparing a fingerprint of the joiner's radio and phone keys. Cannot use the room id to join the roster or change the room's keys. |
| A relay carrying our packets | Between two nodes | Forwards ciphertext. Cannot read or alter anything Firepit sends undetected. |
| Someone holding a member's radio | Physical access, USB, or a Bluetooth pairing | Reads the channel layer: the room PSK, the radio's own name and battery, and its node list. **Cannot** read sealed room text, positions or pins, or direct messages between Firepit users: the room key only ever travels sealed to a phone key (§3), and direct messages are sealed phone to phone, mixed with an hourly room key when the phones share one, neither of which the radio holds. Unsealed text, pins or positions they send into a room are dropped. |
| Someone nearby with Bluetooth | In range while the owner's phone is not connected | Can pair only with the radio's PIN. Firepit checks on every connection whether the radio has no PIN or the published default one, and offers to set a new one (§7). |
| Someone who later takes a member's phone, its key store or a backup | Holds recordings of the air from before | Reads what the phone still shows, since history is stored opened (§4). **Cannot** decrypt recorded room traffic from before the hour preceding the theft: room keys move on every hour, one way, and the old hours' keys are destroyed (§3). |
| Someone who records a room and plays it back | Any radio holding the room's channel key | Nothing. Each sealed message opens once, and only in the hours its key is still kept (§2). |

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
- **Phones whose clocks are far apart.** Room keys follow the clock (§3), so
  a phone whose clock is more than about an hour away from the others' cannot
  read their room messages, nor they its, until the clock is put right. That
  costs nothing permanent: keys are erased on the time that has really passed,
  not on what a wrong clock claims (§8). Only a phone that starts up with its
  clock already set far ahead has nothing to compare it with, and can erase
  keys early; the room's next rotation, or a new invite, recovers it. Phones
  that set their time automatically are minutes apart at worst, even off the
  network.
- **Phones on a build from before hourly keys.** Their messages cannot be
  opened, nor they ours: reading the old format would mean keeping the old,
  never-changing keys, which is what hourly keys exist to remove. The room
  names, once, anybody whose messages arrive in the old format, so they can
  update.
- **A compromised or unlocked phone.** On Android the database and every key are
  encrypted at rest (§4), so a copy of the app's files reads as noise. On iPhone
  the keys are in the Keychain, but the database has only iOS's own file
  encryption, which a copy taken after the phone's first unlock no longer has
  (§4). On either, an attacker running code as the app, or holding the unlocked
  phone, reads what the app reads.
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
SQLCipher on both apps, which is the standard for encrypting SQLite. Android uses
the Java cryptography APIs and the Android Keystore; iOS uses CryptoKit and the
Security framework.

**On the air: identical on both apps.** These have to be: a phone of either kind
must open what the other sealed.

| Purpose | Construction | Parameters |
|---|---|---|
| Sealing room content | AES-256-GCM | 32-byte key of one sender for one hour, 12-byte nonce (2 bytes of the hour, 10 random), 16-byte tag |
| Moving a room key on, once an hour | HKDF-SHA256 expand (HMAC-SHA256) | next hour's key from this hour's; each sender's key from the hour's key and their node number |
| Handing a room key to one phone | ECDH P-256 (one-off key) + HKDF-SHA256 + AES-256-GCM | 33-byte compressed keys, bound to room id + generation + recipient + hour |
| Direct messages between phones | ECDH P-256 (both phones' keys) + HMAC-SHA256 + AES-256-GCM, mixed with a shared room's hourly key when there is one | bound to sender + recipient, in that order; v2 nonce carries the room hour tag |
| Direct messages, radio layer | X25519 + AES-CCM | Meshtastic firmware PKI, 32-byte public keys; the outer layer only |
| Invite tokens | HMAC-SHA256 | truncated to 8 bytes |
| Key derivation | HMAC-SHA256 | context string + room id + generation |

**On the phone: each platform's own.**

| Purpose | Android | iPhone |
|---|---|---|
| Database at rest | SQLCipher 4 (AES-256 per page, HMAC-SHA512) under a random 32-byte key wrapped by the Keystore | SQLCipher 4 under a random 32-byte key in the Keychain (`AfterFirstUnlockThisDeviceOnly`), over iOS Data Protection (§4) |
| Room keys and other secrets | Wrapped by an Android Keystore AES-256-GCM key that never leaves secure hardware | Keychain, readable after first unlock, this device only, never synced to iCloud |
| The phone key (P-256) | Wrapped by the Keystore, like the room keys | Created inside the Secure Enclave, which never releases the private half |
| Randomness | `java.security.SecureRandom` | `SecRandomCopyBytes` and CryptoKit's generator |

Implemented in `core/crypto/RoomCipher.kt`, `core/crypto/SealedText.kt`,
`core/crypto/RoomRatchet.kt`, `core/crypto/KeyEnvelope.kt`,
`core/crypto/DirectSeal.kt`, `core/crypto/RoomCrypto.kt` and
`core/database/DatabaseEncryption.kt`; on iOS in `FirepitCrypto/RoomCipher.swift`
(`RoomCipher`, `SealedText`), `FirepitCrypto/RoomRatchet.swift`,
`FirepitCrypto/PhoneSeals.swift` (`KeyEnvelope`, `DirectSeal`) and
`FirepitCrypto/RoomCrypto.swift`.

**A peer's P-256 key is checked to be on the curve before it is used.** Agreeing
on a point that is not is how a long-lived private key leaks a few bits at a
time.

**Nonces are transmitted**, never derived from a packet id. A packet id is not
ours to guarantee unique, and a repeated nonce under the same key breaks GCM
completely. A room message's nonce starts with two bytes saying which hour
sealed it, so the receiver knows which key to derive, and ends with ten random
bytes. The key it is used with belongs to one sender for one hour, so no two
members ever share a key and nonce, however busy the room.

**Each sealed message opens once.** A recorded room message would otherwise
open again when played back, and a receipt, position, pin or roster change be
believed twice. Every phone remembers the nonce of each room message it opened
for as long as that message could still open, which is about three hours
(`SeenSeals`), on disk, so restarting the app does not reset it. A copy is
dropped however it arrives.

**A message in the first format**, sealed by a build from before hourly keys,
is recognised by its version byte and not opened (`SealedText.isFirstFormat`).
The room shows one line naming the sender, so a phone left on the old build is
spotted rather than heard as silence. Only for a member, on the room's own
slot: the version byte proves nothing, and anyone can address a packet to us.

**Sealed content is bound to its room and sender.** `SealedText.contextOf(roomId,
senderNodeNum)` is passed as GCM additional authenticated data — authenticated
but not transmitted. A sealed message lifted into another room, or re-attributed
to a different sender, fails to open. And a sealed message is only believed on
the slot of the room it names, or privately to us (`TrustRules.sealedPlacementOk`),
so someone in two rooms cannot have one room's words shown in the other. A
direct message is bound the same way to its sender and recipient
(`DirectSeal.contextOf`), so it cannot be turned round or re-addressed, and only
the sender's phone key can produce one that opens. Version 2 direct seals also
put the room hour tag in the nonce and mix the phone ECDH output with
`HMAC(E(h), "firepit-direct-room-v1" ‖ room ‖ generation ‖ hour ‖ 1)` for a
room both phones share. A sender chooses such a room only after it has opened a
seal from that person under the room's current generation; roster membership is
not enough. The receiver tries the room generations it still holds for that
hour; a second copy is refused by `SeenSeals`.

> Verify: `RoomCipherTest`, `SealedTextTest`, `RoomRatchetTest`, `SeenSealsTest`, `SealedRoomTextTest`,
> `SealedReceiptTest`, `KeyEnvelopeTest`, `DirectSealTest`, `TrustRulesTest`, and on a device
> `RoomKeyStoreTest`
>
> iOS: `RoomSealingTests`, `RoomRatchetTests`, `SeenSealsTests`, `PhoneSealTests`, `TrustRulesTests`, the
> key-store tests in `StoreAndSmallTypeTests`, and `AndroidInteropTests`, which opens what Android's code
> sealed and derives the same contexts, hour and sender keys, tokens and invite codes.
> `scripts/check-android-interop.sh` runs the reverse direction too: Android opens what iOS sealed

---

## 3. Key hierarchy

Three kinds of key, deliberately.

```
room PSK (32 bytes, random)          →  written to the radio
  └─ invite key   = HMAC(psk, "meshchat-invite-v1" || room_id || generation)

firepit key (32 bytes, random, one per generation)
  │                                   →  on phones only; travels only sealed to a phone key
  └─ hour key  E(h)  the firepit key is the key for the hour it was made in;
     │               E(h+1) = HMAC(E(h), "firepit-hour-v1" || room_id || generation || h+1 || 1)
     └─ sender key  = HMAC(E(h), "firepit-sender-v1" || room_id || generation || h || sender || 1)
          seals room text, positions, pins, person cards, receipts,
          roster events and syncs, key rotations and the notice that one happened

phone key (P-256, one per phone)     →  private half never leaves the phone
  ├─ receives a room's firepit key in a grant or a rotation
  └─ agrees a key with another phone for direct messages and their receipts;
     when the phones share a room, the agreed secret is mixed with that room's
     hourly key before sealing
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

**Keys move on every hour, one way** (`RoomRatchet`). Hours are counted in UTC
since 1970, so every member derives the same keys from the same clock without a
byte or a packet more. A phone keeps one key per generation: the hour just gone,
for packets the mesh delivers late. Later hours are derived when needed, and
earlier ones are destroyed every ten minutes whether or not anyone spoke
(`RoomKeyStore.erase`). No one can run the step backwards, so whoever takes the
phone, its key store or a backup reads nothing recorded before the hour that
preceded it. That is forward secrecy, in hours rather than per message, which
is what fits a mesh that cannot afford a reply to every message.

A message opens only if it was sealed in the hour just gone, this one or the
next (`RoomRatchet.opens`), which absorbs clocks a little apart. A new member,
or one handed a rotation they missed, is given **this hour's** key and nothing
older, so they read from the moment they were let in. Keys stored before this
scheme existed are taken to belong to a fixed hour, 2026-01-01T00:00Z, so phones
that updated at different times still agree on every later key.

A room key is never derived from a room name or anything guessable, and never
empty: the firmware treats an empty PSK as "inherit the primary", which would
silently create a room every nearby radio could read. A room whose slot has to
move is read back first, and a read that fails stops the move rather than
writing the room with no key.

---

## 4. Storage

Messages are stored opened, and so are positions, names, rosters, pins and
receipts, so the phone has to protect them. Room keys must be able to leave the
phone, sealed to another phone's key in a grant or a rotation, so they cannot
live inside secure hardware itself. Each platform protects them as strongly as
that allows.

### Android

Each key is wrapped by an AES-256-GCM key that never leaves secure hardware
(the Android Keystore), and only the wrapped form is written to preferences.

- `core/database/KeystoreWrapping.kt` — wrap/unwrap, one implementation
- `core/data/RoomKeyStore.kt` — room keys: one hour's key per generation
- `core/data/SeenSeals.kt` — the nonces of room messages already opened, in
  `noBackupFilesDir`. Not secret: nonces travel in the clear
- `core/data/PhoneKeyStore.kt` — this phone's own key pair
- `core/data/PrimaryBackup.kt` — the radio's original channel, which contains
  somebody else's mesh PSK
- `core/database/DatabaseEncryption.kt` — the database's key

**The database is encrypted.** The file itself is encrypted with SQLCipher
under a random key the Keystore wraps. Android's own disk encryption only
protects app files until the phone is first unlocked after a restart; a copy
taken after that — a forensic extraction of a locked phone, malware with root,
a backup the OS gets wrong — is noise without the Keystore. An unencrypted
database from an older version is rewritten encrypted on first open and the
plain file deleted; if that fails, it is deleted anyway rather than left
readable. The key stays usable while the phone is locked, because messages
arrive in a pocket.

If a wrapping key is gone — app data restored onto a different phone, or the
Keystore reset — unwrapping returns null rather than throwing. What it
protected is lost, which is correct, but it is not a reason to crash. Removing
the screen lock does not destroy these keys: they are not bound to user
authentication.

**Nothing leaves in a backup.** `allowBackup` is off and the extraction rules
exclude every storage area from cloud backup and device transfer.

### iPhone

Paths are under `ios/Packages/FirepitKit/Sources/FirepitData/`.

- `KeychainStore.swift` — every secret, as a Keychain item that is readable
  after the phone's first unlock, belongs to this device only, and is never
  synced to iCloud Keychain
- `RoomKeyStore.swift`, `PrimaryBackup.swift` — room keys (one hour's key per
  generation), and the radio's original channel, both in that store
- `SeenSeals.swift` — the nonces of room messages already opened, in the
  database folder, so it shares that folder's protection and stays out of
  backups
- `PhoneKeyStore.swift` — this phone's key pair, created inside the **Secure
  Enclave**. The private half never leaves it, not even to the app; the Keychain
  holds only a reference that the Enclave alone can use. (Simulators and tests,
  which have no Enclave, use a software key.)
- `FirepitDatabase.swift` — the database

**The database is encrypted with SQLCipher,** as Android's is (Stage 11, Phase 5).
The key is 32 random bytes kept in the Keychain as `AfterFirstUnlockThisDeviceOnly`
and handed to SQLCipher in its raw form, so no password stretching runs on each
open. A copy of the files taken from the phone is noise without it. Usable while
the phone is locked after its first unlock, which keeps Android's promise that
messages arrive in a pocket, since iOS may wake the app for Bluetooth then.
Underneath, the folder and files still use the Data Protection class
`completeUntilFirstUserAuthentication`. A database from before this build is
copied into an encrypted one with `sqlcipher_export` the first time the new build
opens it, keeping every row. GRDB is built on SQLCipher from a local copy
(`ios/Packages/GRDB-SQLCipher`), following GRDB's own instructions.
`DatabaseEncryptionTests` holds it: the file is not readable SQLite, the same key
reopens it, another key cannot, and an old database keeps its rows.

**Backups.** The database folder is excluded from iCloud and device backups,
and Keychain items marked "this device only" never restore onto another phone.
Settings (the radio last used, preferences, an active sharing choice) are
ordinary preferences and do go into the phone's own backups; they hold no keys
and no messages.

**Reinstalling.** Deleting the app removes its files, but iOS keeps the app's
Keychain items, so a reinstall finds the old phone key and room keys. Android
removes everything.

### Both apps

**Room keys are destroyed as they age** (§3). Each generation keeps one hour's
key, moved on every ten minutes, on real time rather than a clock that can be
set wrong (§8). A generation the room has rotated away from is
kept two hours more, so a packet sealed just before the rotation still opens
when the mesh delivers it late, and then deleted; it stays only while a member
who missed the rotation is still owed a key sealed under it. History itself is
stored opened, in the protected database, so destroying old keys costs the
reader nothing they had.

**Nothing is kept longer than asked.** The retention window deletes messages,
and with them positions older than the window, nodes not heard in it that share
no room with us, expired pins, and the map tiles cached while browsing. Person
cards and phone keys go when nobody they belong to shares a room with us, and
leaving a room takes its history, pins, roster and every key it had. Settings
can erase the history outright. A room nobody has spoken in for the chosen
lifetime is left automatically, judged on when it last had anything said,
pinned or shared, which is recorded as it happens.

---

## 5. What may go on the air

The rule is not "always encrypt" — that would make talking to people outside
Firepit impossible. It is **never downgrade silently**.

`core/protocol/MessagePrivacy.kt` decides carriage as data, not as branches
inside a sender:

| Conversation | Carriage | Readable by |
|---|---|---|
| Firepit room | `SealedRoom` — AES-GCM inside the channel cipher, on `PRIVATE_APP` | Room members holding the firepit key |
| One person who shares a room with us | `SealedDirect` — sealed to their phone key and a shared room's current hourly key, inside firmware PKI to their radio | That person's phone |
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
  A replayed copy, or one outside the hour window, is dropped without an answer.
  A seal that fails inside the window is answered with a `SealedDirectRefused`;
  if it was v2 and came from the intended peer, the sender clears the
  room/member evidence and replaces the original row with a resend under the
  next eligible v2 room. It never automatically downgrades that message to v1.
  If no other v2 room qualifies, it is marked "They could not open it." Refusal
  attempts expire after ten minutes, the app keeps at most 64, and a refusal
  for a message already confirmed by a sealed receipt is ignored. A real refusal
  also shares the sender's person card again.
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
turns that on. On Android they carry a public version for a secure lock screen
and are not bridged to a watch. On iPhone a lock screen that hides previews
shows "New message", and whether notifications reach an Apple Watch is the
owner's own Watch setting. Android keeps screens out of screenshots, screen
recordings and the Recents snapshot unless the owner allows it. iOS lets no app
block screenshots, so the iPhone app covers itself in the app switcher and
while the screen is recorded, mirrored or AirPlayed (`SecureWindow`). On both,
the invite screen is always protected this way, whatever the setting. On
Android every text field asks the keyboard not to learn from it; iOS offers no
such request.

> Verify: `MessagePrivacyTest`, `MeshPacketSafetyTest`, `PositionPrecisionTest`
> (`PositionSharingTest`), `TrustRulesTest`, `MessageStatusRulesTest`, `ReceiptRulesTest`,
> `ProtocolContractTest` (every sealed payload fits its packet at its limits)
>
> iOS: the same suites with an `s` (`MessagePrivacyTests`, `TrustRulesTests`, …)

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
   │    RoomGrant { room_psk, generation, key_hour, sealed_key = seal(phone_key, this hour's key) }
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
   earlier rotation, and carries every removal they missed. A member is
   reported as reached when their own radio acknowledges the packet
   (`MeshRepository.sendAwaitingAck`); our radio accepting it proves nothing
   about where it went.
4. Everyone owed the key is recorded (`pending_handovers`, no secrets) *before*
   the handovers go out, in the same all-or-nothing step as the notice and our
   own key change, so closing the app mid-rotation strands nobody.
5. **A record is cleared only by the member's app**, never by their radio: by
   anything they seal under the key they were handed, or a later one
   (`settleHandover`). A member shares their card under the new key as soon as
   they take it, so this is usually seconds. Until then they are handed the key
   again whenever anything is heard from them, at most every ten minutes, and
   up to three times their radio acknowledges; past that, only when they are
   heard still sealing under the old key, which proves the new one never
   reached their app. When another rotation comes before a member has
   confirmed the last, theirs is sealed under each generation they might hold,
   from the one they were last heard on to the newest; only the one they hold
   opens it. Every such generation is kept while a record names it, so it can
   always be sealed under.

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
backup (`app/privacy/SecureWindow.kt`). On iPhone, where no app can block a
screenshot, the invite screen always covers itself while the screen is recorded
or mirrored and in the app switcher (`Firepit/Features/Privacy/SecureWindow.swift`);
a screenshot taken deliberately on the phone itself is possible. Either way, a
camera pointed at the screen is still a camera — which is what the approval
step is for, and why a code stops working after about 30 seconds.

> Verify: `InvitePrivacyTest` (asserts on encoded bytes that no 32-byte key
> appears in an invite, and that a grant's key only opens for the joiner's
> phone), `RoomCryptoTest`, `ScanDisambiguationTest`, `KeyEnvelopeTest`,
> `KeyFingerprintTest`, `TrustRulesTest`, `ProtocolContractTest` (a sealed
> rotation and a sealed roster sync still fit one PKI packet), `PacketOriginTest`
> (every hop pair, including the impossible ones)
>
> iOS: `InviteTests` (including that an invite has nowhere to put a room key and a
> grant opens only for the joiner's phone), `ScanDisambiguationTests`,
> `KeyFingerprintTests`, `TrustRulesTests`, `ProtocolContractTests`, `PacketOriginTests`

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

**Room keys follow the phone clock** (§3), never a radio's: a radio's clock is
often set from the phone in the first place, and the app cannot tell a GPS fix
from that. A phone takes the current hour to be never earlier than the hour
whose key it holds, so a clock set back neither reopens destroyed hours nor
seals under one.

**Keys are erased on real time** (`KeyClock`). Sealing and opening follow the
phone's clock, because that is what members agree on. Erasing follows the
earlier of that clock and the time that has really passed since it was last
seen keeping step, measured by the phone's monotonic clock, which nothing can
set and which keeps counting while the phone sleeps. So a clock set ahead, by
hand or by a network with the wrong time, erases nothing early: put right, the
phone reads the room again. The anchor is kept across app restarts where the
phone says which boot it is in (Android always; an iPhone may not tell apps,
and then only while the app runs), and dropped when the phone itself
restarts, when there is nothing to compare with and the clock is taken as it
is. A fresh message from another member sealed within an hour of the phone's
clock also counts as the clock being right, which is how a clock put right
after the phone started is caught up with. The replay record is pruned on the
same real time. A message sealed outside the hours a phone can open is logged with both
hours, which is how a wrong clock shows up.

> Verify: `RadioClockTest`, `RoomRatchetTest`, `KeyClockTest`; iOS `KeyClockTests`

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

```bash
cd ios/Packages/FirepitKit
swift test               # iOS protocol, crypto and data layers
```

```bash
scripts/check-android-interop.sh   # each app opens what the other sealed
scripts/verify-all.sh              # both apps, every test and every parity check
```

Worth reading in order:

1. `core/protocol/MessagePrivacy.kt` — the carriage rule
2. `core/protocol/TrustRules.kt` — what is believed on the way in
3. `core/protocol/MeshPacketBuilder.kt` — what cannot be built
4. `core/crypto/RoomCipher.kt`, `core/crypto/SealedText.kt`,
   `core/crypto/RoomRatchet.kt` — the sealing construction and the hourly keys
5. `core/crypto/KeyEnvelope.kt` — how a room key reaches one phone
6. `core/crypto/DirectSeal.kt` — how one person's words reach one phone
7. `core/data/RoomRepository.kt` — invites, grants, rosters, rotation
8. `core/data/LocationRepository.kt`, `core/data/WaypointRepository.kt` — sealed positions and pins
9. `core/protocol/RadioSecurityCheck.kt` — what is checked on the radio itself
10. `core/data/RoomKeyStore.kt`, `core/data/KeyClock.kt`, `core/data/SeenSeals.kt`,
    `core/data/PhoneKeyStore.kt`, `core/database/DatabaseEncryption.kt` — storage

On iOS the same reading order works in `ios/Packages/FirepitKit/Sources`:
`FirepitProtocol/MessagePrivacy.swift`, `TrustRules.swift` and
`MeshPacketBuilder.swift`; `FirepitCrypto/RoomCipher.swift`, `RoomRatchet.swift`
and `PhoneSeals.swift` (key envelopes and direct seals); `FirepitData/RoomRepository.swift`,
`LocationRepository.swift` and `WaypointRepository.swift`;
`FirepitProtocol/RadioSecurityCheck.swift`; and for storage
`FirepitData/RoomKeyStore.swift`, `KeyClock.swift`, `SeenSeals.swift`, `PhoneKeyStore.swift`,
`KeychainStore.swift` and `FirepitDatabase.swift`. The cryptography tests are
grouped differently (`RoomSealingTests`, `RoomRatchetTests`, `PhoneSealTests`,
`InviteTests`, `AndroidInteropTests`);
the protocol tests share Android's names with an `s`.

Every `link.send` in the codebase is in `core/data` on Android and in
`FirepitData` on iOS — the same six files on both. No packet is constructed in
the UI layer, so there is no path around the rules above.

To watch a real exchange:

```bash
adb logcat -s FirepitRooms:I FirepitLink:I FirepitLocation:I
```

On iPhone, open Console on a Mac with the phone connected and filter by
subsystem `com.getfirepit.app`; the categories have the same names.

No key material is ever logged. Log lines report absence ("no public key for X")
but never contents.

---

## 11. Android and iPhone side by side

Everything on the air is identical (§2, §5, §6). These are the differences in
how each phone protects what it holds:

| Area | Android | iPhone |
|---|---|---|
| Database at rest | SQLCipher under a Keystore-wrapped key; a copy of the files is noise | SQLCipher under a Keychain key; a copy of the files is noise (§4) |
| Keys | Wrapped by the Keystore; the phone key too | Keychain, this device only; the phone key inside the Secure Enclave |
| Backups | None | Database excluded; keys never restore elsewhere; settings included |
| Reinstall | Removes everything | Keychain items survive and are found again |
| Screenshots | Blocked unless allowed | Cannot be blocked; the app covers itself in the switcher and while recorded or mirrored |
| Keyboard learning | Turned off in every field | No such switch on iOS |
| Watch | Notifications not bridged | The owner's Apple Watch settings decide |

---

## Reporting

Security issues are more useful than feature requests. If something here is
wrong, the claim and the code disagree, or a test passes while the property it
names does not hold, please say so.
