# Firepit security

What Firepit protects, how, and where each claim is implemented. Written to be
checked rather than believed: every guarantee below names the file that provides
it and the test that holds it in place.

Firepit is a group messenger that runs over the [Meshtastic](https://meshtastic.org)
LoRa mesh. There is no server, no account and no internet. Everything here is
about what travels on the air and what sits on the phone.

- **Platform:** Android, `minSdk 29`. iOS is not built yet.
- **Radio firmware:** Meshtastic 2.7 or newer. Older is refused, not degraded.
- **Wire formats:** `protos/meshchat/meshchat.proto`, generated with Wire.

---

## 1. What we defend against

| Adversary | Position | Outcome |
|---|---|---|
| Passive listener on the mesh | Any radio in range | Sees traffic exists. Cannot read room messages or direct messages. |
| Another Meshtastic user | Holds the published primary key | Sees a node broadcast NodeInfo. Cannot read any conversation. |
| Someone who photographs an invite | Camera on the QR | Gets no key. Can ask to join, which a person must approve, for about 30 seconds. |
| A relay carrying our packets | Between two nodes | Forwards ciphertext. Cannot read or alter room text undetected. |
| Someone holding a member's radio | Physical access to hardware | Reads the channel layer. **Cannot** read sealed room text, which never reaches the radio. |

### What we do not defend against

Stated plainly, because a threat model that claims everything is worthless.

- **A member of a room.** Anyone invited holds the room key and can read and
  keep everything. Rotation (§6) is the only recourse and it is not retroactive.
- **A compromised phone.** Keys are wrapped by hardware (§4) but an attacker
  with the unlocked device reads what the app reads.
- **Traffic analysis.** LoRa is broadcast. Who transmits, when, and how often is
  visible to anyone listening, regardless of encryption.
- **Forged positions inside a room.** Firmware-generated position and NodeInfo
  packets ride the channel cipher only (§7), which is unauthenticated. A room
  member can spoof another member's position. Signing exists in firmware 2.8 but
  not 2.7, so it cannot be relied on.

---

## 2. Primitives

No cryptography is invented here. Everything is platform-provided.

| Purpose | Construction | Parameters |
|---|---|---|
| Sealing room content | AES-256-GCM | 32-byte key, 12-byte random nonce, 16-byte tag |
| Invite tokens | HMAC-SHA256 | truncated to 8 bytes |
| Key derivation | HMAC-SHA256 | context string + room id + generation |
| Direct messages | X25519 + AES-CCM | Meshtastic firmware PKI, 32-byte public keys |
| Randomness | `java.security.SecureRandom` | all keys, nonces, room ids |

Implemented in `core/crypto/RoomCipher.kt` and `core/crypto/RoomCrypto.kt`.

**Nonces are random and transmitted**, never derived from a packet id. A packet
id is not ours to guarantee unique, and a repeated nonce under the same key
breaks GCM completely.

**Sealed content is bound to its room and sender.** `SealedText.contextOf(roomId,
senderNodeNum)` is passed as GCM additional authenticated data — authenticated
but not transmitted. A sealed message lifted into another room, or re-attributed
to a different sender, fails to open.

> Verify: `RoomCipherTest`, `SealedTextTest`, `SealedRoomTextTest`, `SealedReceiptTest`

---

## 3. Key hierarchy

Two independent keys per room, deliberately.

```
room PSK (32 bytes, random)          →  written to the radio
  └─ invite key   = HMAC(psk, "meshchat-invite-v1" || room_id || generation)
  └─ channel key  = HMAC(psk, "meshchat-channel-v1" || room_id || generation)

firepit key (32 bytes, random)       →  never leaves the phone
  └─ seals room text, person cards, receipts
```

The split is the point. The **room PSK** is what the firmware encrypts the
channel with, so anyone holding the radio — or a stolen radio — has it. The
**firepit key** exists only in phone storage, so the same person gets a
timestamp and a node number and nothing to read.

A room key is never derived from a room name or anything guessable, and never
empty: the firmware treats an empty PSK as "inherit the primary", which would
silently create a room every nearby radio could read.

---

## 4. Key storage

Room keys must stay exportable — a grant hands one to a new member — so they
cannot live inside the Android Keystore itself. Instead each is wrapped by an
AES-256-GCM key that never leaves secure hardware, and only the wrapped form is
written to preferences.

- `core/data/KeystoreWrapping.kt` — wrap/unwrap, one implementation
- `core/data/RoomKeyStore.kt` — room keys, per generation
- `core/data/PrimaryBackup.kt` — the radio's original channel, which contains
  somebody else's mesh PSK

If the wrapping key is gone — screen lock removed, or app data restored onto a
different phone — unwrapping returns null rather than throwing. What it
protected is lost, which is correct, but it is not a reason to crash.

**Old generations are kept.** Rotating a room does not make its history
unreadable; messages already on the phone were sealed under the key of their
day, and discarding it would delete the conversation rather than protect it.

---

## 5. What may go on the air

The rule is not "always encrypt" — that would make talking to people outside
Firepit impossible. It is **never downgrade silently**.

`core/protocol/MessagePrivacy.kt` decides carriage as data, not as branches
inside a sender:

| Conversation | Carriage | Readable by |
|---|---|---|
| Firepit room | `SealedRoom` — AES-GCM inside the channel cipher, on `PRIVATE_APP` | Room members holding the firepit key |
| One person | `ToOneNode` — firmware PKI to their public key | That person |
| Meshtastic channel | `OpenChannel` — ordinary `TEXT_MESSAGE_APP` | Anyone with the channel key, by design |
| Anything else | `Refused` | Not sent |

A refusal is surfaced to the user with a reason (`NO_PEER_KEY`, `NOT_A_ROOM`,
`NOT_ENCRYPTED`). Firepit never falls back to the channel key for a direct
message — that is precisely what Meshtastic did before 2.5, where "anyone in the
channel could read all your direct messages".

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

> Verify: `MessagePrivacyTest`, `MeshPacketSafetyTest`, `PositionPrecisionTest`

---

## 6. Invites

The part most worth auditing, because an invite is how a stranger becomes
someone who can read everything.

### The code carries no keys

```protobuf
message Invite {
  reserved 4, 13;                 // held open: a key here is in every photograph
  uint32 version = 1;
  fixed32 room_id = 2;
  string room_name = 3;
  uint32 position_precision = 5;
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
   │
   │        JoinHello { invite_id, token, joiner_key }
   │  ◄──────────────────────────────────── PKI to inviter's key
   │
   ├─ rate limit · one-shot · 0 hops · token check
   ├─ ask the person holding the phone
   │
   │        RoomGrant { room_psk, firepit_key, … }
   │ ────────────────────────────────────►  PKI to joiner_key
   │                                        writes the channel, stores keys
```

Every check on the inviter's side narrows what a stolen code is worth:

| Check | Why | Where |
|---|---|---|
| Rate limit, 5/minute per node | A stranger who cannot pass the token check has no reason to keep trying | `admitAttempt` |
| Invite not already used | A code stops being worth presenting once the person it was shown to is in | `IssuedInvite.usedBy` |
| Zero hops | A code is shown to somebody in front of you; anything relayed was read somewhere you cannot see | `PacketOrigin.arrivedDirectly` |
| Token minted by us, recently | Proves the code is genuine | `RoomCrypto.matchesRecentToken` |
| **A person says yes** | Proves the bearer is the person it was shown to — which no token can | `approveJoin` |

The last row is the one that matters. A token proves the *invite* is genuine, not
that the *bearer* was authorised. Without human approval, a photographed code
used inside the window would still yield the keys.

**The hop rule fails closed.** `hop_start` and `hop_limit` are not authenticated,
so the count derived from them is a claim rather than a measurement. A sender
three hops away can leave `hop_start` at zero to make the distance look
unknowable while setting `hop_limit` high enough to be relayed anyway — the
packet then arrives claiming a *negative* distance, which no honest packet can.
Only `hop_start == hop_limit` is treated as adjacent; everything else, including
every impossible pair, is refused.

**The grant is encrypted to the key inside the sealed hello**, not to one looked
up in the node database. Meshtastic NodeInfo is unauthenticated, so a node
claiming someone else's number could otherwise have received the keys.

**The joiner accepts a grant only from the node it scanned.** The room id and
invite id both travel in the QR code, so anyone who photographed it can name them
and could otherwise answer first with their own keys — putting the scanner in
*their* room, under a name they expected, without either real party noticing.
Answering as the scanned node is a different matter: the joiner seeded that
node's public key from the code itself (`add_contact`, step A), so the firmware
will only decrypt a reply that was genuinely signed by its holder. The check is
`packet.from == awaited.inviter` on a packet that must already be
`pki_encrypted`; either alone is insufficient.

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

The invite screen sets `FLAG_SECURE`, so the code cannot be screenshotted or
screen-recorded and cannot reach a photo backup. A camera pointed at the screen
is still a camera — which is what the approval step is for.

> Verify: `InvitePrivacyTest` (asserts on encoded bytes that no 32-byte key
> appears in an invite), `RoomCryptoTest`, `ScanDisambiguationTest`,
> `PacketOriginTest` (every hop pair, including the impossible ones)

---

## 7. Metadata

What an observer learns at each layer, assuming they hold the relevant key.

| Layer | Visible without any key | Visible with the channel key | Visible with the firepit key |
|---|---|---|---|
| LoRa | that a transmission happened, its length, sender node number | — | — |
| Primary channel | — | node names, battery, hardware model | — |
| Room channel | — | that a `PRIVATE_APP` packet exists, its room id, sender, timestamp | the words |

The primary channel's key is **published in this repository**
(`protos/meshchat-primary-key.txt`). It is a community key, not a secret. Its
purpose is that Firepit radios find each other; it protects nothing. Nothing
sensitive is placed on it — no positions, no waypoints, no traceroutes, no
conversation.

Firepit rooms never bridge to MQTT (`uplink_enabled` and `downlink_enabled` are
both false on every room channel).

---

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
firmware.

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
2. `core/protocol/MeshPacketBuilder.kt` — what cannot be built
3. `core/crypto/RoomCipher.kt` — the sealing construction
4. `core/data/RoomRepository.kt` — invites, grants, rotation
5. `core/data/RoomKeyStore.kt` — key storage

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
