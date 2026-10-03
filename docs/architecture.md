# How Firepit works

A plain-language tour of the whole system: what runs where, what travels on the air, how the two apps are
built, and how we keep them identical. Every detail here is explained in more depth in the documents linked
from each section; start here, then follow the links.

**Names.** *Firepit* is the product and the app. *MeshChat* is the protocol's internal name: you will see it on
the radio's primary channel and in `protos/meshchat/meshchat.proto`. They are the same project.

---

## 1. The big picture

Firepit lets a small group chat and share their location with **no phone signal and no internet**. Each person
carries a small Meshtastic LoRa radio next to their phone. The phone talks to its radio over Bluetooth; the
radios talk to each other over long-range radio (LoRa), passing messages along for each other (a *mesh*).

```
 ┌──────────┐ Bluetooth ┌─────────┐  LoRa (km)  ┌─────────┐  LoRa  ┌─────────┐ Bluetooth ┌──────────┐
 │ Phone A  │◄─────────►│ Radio A │◄───────────►│ Radio C │◄──────►│ Radio B │◄─────────►│ Phone B  │
 │ Firepit  │           └─────────┘   relays    └─────────┘        └─────────┘           │ Firepit  │
 └──────────┘                                                                            └──────────┘
   seals and opens        carry ciphertext they cannot read            seals and opens
```

- **No servers, no accounts.** Nothing Firepit says goes through the internet. The only network use is
  downloading map tiles, which can be turned off once an area is saved for offline use.
- **The phone does the encryption.** Words, positions and pins are sealed on the sending phone and opened on
  the receiving phone. Radios, including your own, only ever carry ciphertext.
- **Any Meshtastic radio works** (firmware 2.7 or newer). Firepit uses the standard Meshtastic protocol, so
  other people's radios can relay its traffic without being able to read it.
- **Two native apps, one protocol.** The Android app (Kotlin) and the iPhone app (Swift) send and accept exactly
  the same bytes, so an iPhone and an Android phone can be in the same room.

---

## 2. What a radio holds: channels and rooms

A Meshtastic radio has **eight channel slots**. Each slot has a name and a key (the *PSK*); the radio encrypts
everything it sends on a channel with that channel's key. Firepit uses the slots like this:

| Slot | What it is | Who can read it |
|---|---|---|
| 0 | The **primary channel**. Radios announce themselves here (name, battery). Firepit can make it *Private to Firepit* with a key that ships with the app, so ordinary Meshtastic radios stop seeing yours. | Anyone with that key: every Firepit user. Nothing private is ever sent here. |
| 1–7 | **Rooms**, one per slot. | See the room kinds below. |

**Room kinds.** Every room is shown with a badge saying who can read it:

| Kind | What it is | Who can read it |
|---|---|---|
| **Firepit** | Firepit's own room. Everything in it is sealed by the phones with a key the radios never get. | Only the people you invited. |
| **Meshtastic** (private channel) | An ordinary Meshtastic channel, shared with other Meshtastic apps. | Anyone who has the channel's key. |
| **Meshtastic · public** | A channel with a published key. | Every Meshtastic radio in range. |
| **No key** | A channel with no encryption. Firepit refuses to send on it. | Everyone. |

Rooms are created, joined and left by writing channel slots on the radio. That never restarts the radio.

---

## 3. Keys, in one page

Three kinds of key, kept apart on purpose (full detail: [security.md §3](security.md#3-key-hierarchy)).

| Key | Where it lives | What it protects |
|---|---|---|
| **Room PSK** (one per room) | Written to the radio | The radio-to-radio layer. Anyone holding a member's radio could read it out, so it is never the thing that protects what people say. |
| **Firepit key** (one per room) | Only on the members' phones | Seals everything said or shared in the room: words, positions, pins, names, read receipts, member lists. |
| **Phone key** (one per phone) | Never leaves the phone (Android Keystore; iPhone Secure Enclave) | Receives a room's firepit key when you join, and seals direct messages between two phones. |

When someone joins a room, the room's firepit key is **sealed to their phone key** before it is sent, so even the
radios that carry it cannot open it.

---

## 4. What travels on the air

Firepit adds nothing new to the Meshtastic protocol except one message type, `MeshChatControl`
(`protos/meshchat/meshchat.proto`), sent on Meshtastic's reserved port for apps, `PRIVATE_APP` (256). It carries
everything Firepit seals. One packet per event; the only repeating message is your position, and only while you
choose to share it.

| What | How it is sent | Who can read it |
|---|---|---|
| A message in a Firepit room | Sealed with the room's firepit key (AES-256-GCM) | Room members' phones |
| A direct message to someone you share a room with | Sealed phone to phone, inside the radios' own encryption | That person's phone |
| A direct message to someone outside your rooms | The radios' own encryption only (the composer warns you) | That person, and whoever holds either radio |
| A message in a Meshtastic channel | Ordinary Meshtastic text | Anyone with the channel key, by design |
| Your position while sharing | Sealed to the room you share with | That room's members |
| A dropped pin | Sealed to the room | That room's members |
| "Read by" receipts | Sealed, like messages | The sender |

Everything that arrives is treated as untrusted input. Sealed content is only believed on the room it names, and
from the person who sealed it. The full rules are in [security.md §5](security.md#5-what-may-go-on-the-air).

A LoRa packet holds at most 233 bytes, so messages are short: up to 200 bytes of text, about 170 once sealed in a
Firepit room. The composer counts bytes, not letters, so Arabic or emoji show their real budget.

---

## 5. Joining a room

Invites are QR codes, shown and scanned **in person**:

1. The inviter opens the room's **Invite** screen. The QR code changes every few seconds and contains **no key**;
   a photo of it only lets someone *ask* to join, for a short time.
2. The joiner scans it. Their phone shows the inviter's key fingerprint, which they compare with the inviter's
   screen, and asks to join.
3. The inviter's phone shows **"Let them in?"** with the joiner's fingerprint. The inviter approves.
4. The inviter's phone seals the room's keys to the joiner's phone key and sends them. The joiner's phone sets up
   the room's channel on its own radio and keeps the firepit key. They are in.

Removing someone **changes the room's key** for everyone who stays; the removed person keeps what they already
received but can read nothing new.

---

## 6. Honest delivery ticks

A mesh cannot tell you who *read* a message, so Firepit never claims it.

| Tick | Meaning |
|---|---|
| Clock | Waiting for the radio |
| Grey tick | Handed to your radio; or, after retries, no radio repeated it |
| Green tick | Heard by the mesh: another radio repeated it. The final state for a room message. |
| Green double tick | Delivered to the recipient's radio (direct messages only) |
| Red warning sign | Failed; tap to retry |

"Read by" and "Received by" come only from sealed receipts sent by the members' own phones. There is no "online"
status anywhere: people show when they were last heard.

---

## 7. Location, pins and the map

- **Sharing your location** is chosen per room and for a set time (1 hour, 4 hours, 1 day, or until you stop).
  The phone seals its own GPS fix and sends it at the radio's beacon rate. The radio's own position broadcast
  stays off, so nothing is shared when the phone is away from the radio.
- **Pins** are dropped on the map and sealed to a room like messages.
- **The map** uses MapLibre with OpenFreeMap tiles. Online, it does not jump to where your group is until you tap
  *Show everyone*, because fetching those tiles would tell the tile server the place. Areas can be downloaded for
  offline use, and an offline-only mode stops tile requests altogether.
- **How far messages travel** (once the radio is private to Firepit): *Our nodes only* keeps traffic within
  Firepit radios; *Nearby radios too* lets other Meshtastic radios relay it, still unable to read it.

---

## 8. Inside the apps

Both apps have the same layers, with the same names. The Android app is the **reference**: when the two
disagree, Android's code decides, and the iPhone app is changed to match.

| Layer | What it does | Android (`android/`) | iOS (`ios/`) |
|---|---|---|---|
| Protos | Message definitions, generated code | `core/protocol` (Wire) | `FirepitProtos` (SwiftProtobuf) |
| Model | Plain data types | `core/model` | `FirepitModel` |
| Protocol | Packet building, trust rules, radio commands | `core/protocol` | `FirepitProtocol` |
| Crypto | Sealing, key envelopes, invites | `core/crypto` | `FirepitCrypto` (CryptoKit) |
| Transport | Bluetooth link to the radio | `core/transport` (Kable) | `FirepitTransport` (CoreBluetooth) |
| Data | Repositories, database, key stores | `core/data`, `core/database` | `FirepitData` (GRDB, Keychain) |
| Design system | Colours, type, shared components | `core/designsystem` | `Firepit/DesignSystem` |
| Screens | Chats, Map, Settings and the rest | `app/` (Jetpack Compose) | `Firepit/Features` (SwiftUI) |

**How data flows.** The radio link delivers packets → `MeshRepository` (the hub) decodes them and applies the
trust rules → specialised repositories handle rooms, receipts, locations and pins → results go into the
database → screens observe the database and redraw. Sending goes the other way. Every packet is built in the
data layer; screens never build packets, so there is no way around the rules.

**Storage.** Both apps use the same database schema (Android's Room schema, run by GRDB on iOS). Messages are
deleted after the retention window you choose (1 day, 1 week or 1 month); leaving a room deletes its history,
pins and keys.

---

## 9. How the two apps stay identical

| What could drift | What keeps it in step |
|---|---|
| Message formats | One copy of the protobufs in `protos/`, mirrored into the iOS package by `scripts/sync-ios-protos.sh`. CI fails if the two copies differ. |
| Encryption | `scripts/check-android-interop.sh` seals on each platform and opens on the other, for room messages, room keys, direct messages, invite codes and tokens, and channel links. |
| Code structure | Kotlin types are ported under the same names, with the same members, unless the platform has no equivalent (such as Android's Keystore wrapping). `scripts/check-port-parity.py`, run on each file as it is ported, fails if the Swift version is missing a member. |
| Database | `scripts/check-dao-parity.py` checks that iOS runs Android's exact SQL. |
| Icons people compare | `scripts/sync-ios-glyphs.py` copies Android's room, role and pin drawings into the iOS app; `--check` reports any drift. |
| Colours | Theme tokens on both, and an iOS test that checks every token against Android's values in light and dark. |
| Everything at once | `scripts/verify-all.sh` builds and tests both apps, checks the two protobuf copies match, runs the encryption check in both directions, and checks the database SQL. |

---

## 10. Where Android and iOS differ

Same features, screens, wording and encryption. These differences come from what each platform allows:

| Area | Android | iPhone |
|---|---|---|
| Screenshots | Blocked unless allowed in Settings | iOS cannot block them; Firepit hides itself in the app switcher and while the screen is recorded or mirrored |
| Keyboard learning | Turned off in every text field | No such switch exists on iOS |
| Running in the background | A foreground service keeps the radio connected, with a notification | iOS wakes the app for the radio when it decides to |
| Bluetooth pairings | Lists radios the phone has paired | iOS does not show apps the phone's pairings; only radios Firepit found are listed |
| Message database at rest | Encrypted with SQLCipher under a key the Keystore protects | Protected by iOS file encryption, which opens after the first unlock following a restart. Adding SQLCipher would match Android. |
| Reinstalling | Uninstalling wipes everything | iOS keeps the Keychain, so the phone key and room keys survive a reinstall |
| Large screens | List and conversation side by side on foldables and tablets | iPhone layout |
| Reading state | Leaving a chat keeps it selected (counted as read) | Leaving a chat stops counting it as read |

---

## 11. Technology

| | Android | iOS |
|---|---|---|
| Language and UI | Kotlin 2.4, Jetpack Compose (Material 3) | Swift 6.4, SwiftUI |
| Minimum OS | Android 10 (API 29) | iOS 17, iPhone |
| Protobufs | Wire 7.1 | SwiftProtobuf 1.38.1 |
| Bluetooth | Kable 0.45 | CoreBluetooth |
| Database | Room 2.8 + SQLCipher 4.19 | GRDB 7.11 |
| Cryptography | Platform (JCA), Android Keystore | CryptoKit, Keychain, Secure Enclave |
| Map | MapLibre Android 13.6 | MapLibre Native 6.31 |
| QR codes | ZXing, CameraX | CoreImage, AVFoundation |
| Dependency injection | Hilt | A plain composition root (`AppContainer`) |

Meshtastic protobufs are pinned at **v2.8.0**; firmware 2.7 and newer is supported, and 2.8 features are used only
when the radio reports it has them.

---

## 12. Glossary

| Term | Meaning |
|---|---|
| **Meshtastic** | Open-source firmware and protocol for LoRa radios that relay messages for each other |
| **LoRa** | Long-range, low-power radio. Slow (around 1 kbps) but reaches kilometres |
| **Node** | A radio on the mesh, identified by a number like `!5a3c91e2` |
| **Channel / slot** | One of a radio's eight configured channels, each with its own key |
| **PSK** | Pre-shared key: the key a channel is encrypted with |
| **Sealed** | Encrypted by Firepit on the phone, on top of the radio's own encryption |
| **PKI** | Meshtastic's public-key encryption between two radios, used for direct messages |
| **Personal / Base / Router** | The roles Firepit gives your radios: the one you carry, one left at camp, one placed for range |
| **Hop** | One radio relaying a packet to the next |
