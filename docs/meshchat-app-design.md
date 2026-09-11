# Firepit (MeshChat) — App Design Document

**Author:** Sarmad Jari
**Product name:** Firepit (chosen 2026-09-09). "MeshChat" remains the internal protocol/channel name.
**Status:** rev 3 — decisions locked 2026-09-09 (see §11 Decision log)
**Firmware target:** Meshtastic v2.7+ (2.8 features such as message signing are capability-gated)

## 1. The idea

MeshChat is a simple mobile app for small groups of friends (festivals, hikes, off-grid trips) to chat and share location without internet or cellular signal. It runs on top of the Meshtastic LoRa mesh network. Anyone can create a private "room," invite friends with a QR code or a link + PIN, and chat and share location as a group — encrypted, no phone signal needed.

Kept intentionally simple: no accounts, no servers, no public directory. Just a room, a few friends, and a mesh.

**Design rule:** use stock Meshtastic protocol wherever possible. Every custom addition costs airtime on a ~1 kbps shared link, so the bar for inventing anything new is high.

## 2. Features

### Rooms
- Create a room — maps to a Meshtastic secondary channel (name + PSK)
- **Hard limit: 7 rooms per user.** Meshtastic has 8 channel slots; slot 0 is the reserved primary channel. Channels must be consecutive — no gaps — so leaving a room reindexes the ones after it
- Join via rotating QR code — in-person, scan to join
- Join via link + PIN — remote/async invites
- The room's PSK is what grants access; there is no separate access-control layer

### Chat
- Group chat (channel broadcast)
- Direct messages — only between people who share at least one room
- Delivery indicators — note these are two different mechanisms:
  - **DMs:** explicit ACK. Sent with `want_ack`, the destination returns a Routing ACK packet. Reliable
  - **Group messages:** implicit ACK only — the sender hears its own message rebroadcast. Confirms it entered the mesh, *not* that anyone received it. The UI must not imply otherwise
- Canned/quick-reply messages — fast to send, short on air
- Alert messages — sent on `ALERT_APP` portnum with `ALERT` priority, for urgent pings ("meet up now")

### Location
- **Live location sharing** for a set duration (minutes / hours / days), auto-expiring
  - **One room at a time.** Meshtastic sends automatic position broadcasts to a single channel — the lowest-indexed secondary channel with location sharing enabled. The app makes this explicit: the user picks an **active location room**, and switching rooms moves live sharing with it
  - Broadcast interval auto-scales with the chosen duration
- **Location requests** — works in *every* room, not just the active one. Any member can ask for someone's location in group or direct chat:
  - *Current location* → native position exchange (`want_response=true`). The target's firmware answers automatically, no interaction needed
  - *Live sharing* → prompts the target to pick a duration. Starting live sharing is always their own decision
- **Pin drops with a comment** — native Waypoint packets (name, description, expiry, lock-to-creator)
- **Position precision** — always full precision in v1: finding each other in a crowd is the point. Per-room reduced precision is deferred. Native Meshtastic feature, no custom work
- **Live map** — everyone shown by name with an auto-assigned color; live vs. stale last-known positions look visually different
- **Smart GPS source** — prefer the node's own GPS; briefly fall back to phone GPS over BLE only when the node has no fix yet; throttle GPS use when node battery is low

### Status
- **Node battery** for everyone — native Telemetry packets, read-only, no extra traffic
- **Signal quality** per person (SNR / RSSI / hop count) — shows who's drifting out of range
- Visual indicators only. No alert popups, no threshold notifications — keeps the app quiet
- **Phone battery is not shared.** Meshtastic's Telemetry and Position schemas are fixed; a byte can't be appended to them. Sending phone battery would require a separate `PRIVATE_APP` packet — its own transmission, its own airtime, for low value. Cut

### Member list
- Shows name, device type, node battery, signal, and who invited them
- Node admin is covered in its own section below

## 3. Node types & roles

| Type | Meshtastic role | Notes |
|---|---|---|
| Personal Node | `CLIENT` | Default. Carried by a person, full chat + location |
| Base Node | `CLIENT_BASE` | Shared reference point (e.g. camp). Prioritizes the group's favorited nodes. Static via Fixed Position, or dynamic. Multiple allowed |
| Router Node | `ROUTER_LATE` | Extends coverage; rebroadcasts only after other paths have had their turn. Location sharing optional. Multiple allowed |

**On roles:** `REPEATER` is deprecated as of firmware 2.7.11 — it suppressed its own NodeInfo/Position/Telemetry, which created holes in the rebroadcast chain. Plain `ROUTER` is reserved for genuinely well-sited, powered, elevated deployments only; using it casually degrades the mesh for everyone by preempting other nodes.

**Map icons:** Base and Router nodes each get a distinct icon and one reserved color (infrastructure). Personal nodes are plain dots with an auto-assigned color per person. Multiple bases share the same icon/color and are distinguished by label, not by adding colors.

**Hop limit:** leave at the default of 3 unless there's a specific proven need. Raising it multiplies traffic across the whole mesh.

**In-app tip, not a feature:** placing a Base or Router node somewhere central and elevated (tent, held-up backpack) meaningfully extends coverage across a spread-out area.

## 4. Node admin

Managing more than one node from one phone is a core feature, not a convenience. A user typically owns a Personal Node and may also be responsible for a shared Base or Router node. The official Meshtastic app can save several radios but keeps only one active at a time — it disconnects one before connecting the next. MeshChat runs independent sessions instead.

### How the connection model works

- The phone is a BLE **central** and can hold several simultaneous connections to different peripherals
- Each *node* accepts only **one** PhoneAPI connection at a time. This is a per-node firmware constraint: two phones cannot share one node, and BLE + serial to the same node conflicts. It says nothing about one phone talking to several different nodes — those are unrelated links
- Platform ceilings are roughly 7 concurrent GATT connections on Android and about 6–7 on modern iOS (Apple does not document this). MeshChat needs 1–2
- **A node's BLE connection is completely separate from its LoRa mesh participation.** A Base node keeps relaying whether or not a phone is attached. Connecting to admin it never interrupts the mesh

### Connection strategy

| Node | Connection |
|---|---|
| Personal Node | Persistent — carries chat, location, and telemetry |
| Base Node | On demand — connect to admin, then disconnect |
| Router Node | On demand — connect to admin, then disconnect |

Holding only one persistent link saves phone battery and stays far inside every platform ceiling. There is no benefit to keeping infrastructure nodes attached.

### Adding a node

1. Power on the node and put the phone in range
2. The app scans and lists nearby Meshtastic nodes
3. Pair using the node's **6-digit BLE PIN** — one pairing per node, remembered afterward
4. Set the **LoRa region** if it has not been set. A node cannot transmit at all until this is done
5. Assign the node's type in MeshChat (Personal / Base / Router), which sets its Meshtastic role
6. Provision the rooms it should join

Onboarding adds nodes **one at a time**. Never assume several nodes appear or pair together.

### What admin can change

Per node, over BLE only:

- **Role** — `CLIENT`, `CLIENT_BASE`, or `ROUTER_LATE`
- **Rooms/channels** — provision, remove, and keep channel slots consecutive
- **Position** — Fixed Position (for a static Base node), position broadcast interval, position precision per room, GPS on/off
- **Favorites** — for a `CLIENT_BASE` node, the list of group nodes whose traffic it prioritizes. This list is what makes a Base node serve *your* group rather than acting as open infrastructure
- **Device basics** — name, region, hop limit (leave at 3 unless proven otherwise)

### Rules and cautions

- **Admin is BLE-only, deliberately.** Meshtastic supports remote admin over an admin channel, but anyone holding that channel's PSK can reconfigure devices remotely — a full device-control key sitting on the mesh. Out of scope for v1 and not recommended later without a clear reason
- **Role changes restart the node.** Expect a brief disconnect and reconnect; the UI should say so rather than looking like a failure
- **Physical access is required for admin.** Since admin is BLE-only, a Base node placed somewhere awkward must be reachable to reconfigure. Worth flagging in the placement tip
- **Warn before setting plain `ROUTER`.** MeshChat should not offer it casually. If it is ever exposed, warn that it preempts other nodes' rebroadcasts across the whole mesh and is meant for well-sited, powered, elevated deployments only
- **Admin traffic never touches LoRa** — it is BLE only, so configuring nodes consumes no mesh airtime

## 5. App architecture

- **Room = Meshtastic secondary channel.** Name + PSK. The key is the access boundary; there's no separate permission system
- **Primary channel (slot 0) = hidden "MeshChat" channel** with an app-wide key. It carries names and battery between MeshChat nodes and never carries location. **Range mode** per node: *Group only* (default; own frequency slot, quietest) or *Group + public relays* (empty primary name → the public LongFast frequency slot, so public Meshtastic nodes rebroadcast our encrypted traffic; names and battery stay private). A group must share one mode; invites carry it and joiners align
- **Control messages** (join hello, roster events, receipts, sealed room messages, live-location requests) → `PortNum.PRIVATE_APP` (256), one small packet per event, never periodic. The `MeshChatControl` `oneof` distinguishes them; other clients ignore the port and the radio's own screen does not display them
- **Identity tag** → each person picks a 2-character tag stored in Meshtastic's `short_name` (default: first + last initials, e.g. SJ), shown inside avatars and map markers together with a per-node colour
- **Room slot manager** — tracks the 7 available channel slots, keeps them consecutive, and handles reindexing when a room is left
- **Multi-node connection manager** — each connected node keeps its own independent session and state, rather than the official app's single-active-node model. See section 4 for the full connection model
- **Location**
  - Pin drops → native Waypoint packets
  - Live location → firmware auto-broadcast on the active location room; the app manages duration and interval, then stops
  - Location requests → native position exchange (`want_response=true`), works on any room
- **Battery/telemetry** → native Telemetry packets, read-only
- **Identity** → native NodeInfo packets. The contact list is naturally scoped to whoever shares a room with you, because that's the only way their NodeInfo reaches you
- **Local-first UI** — messages appear immediately and update state asynchronously. Nothing blocks on a multi-second round trip

## 6. Security strategy

Encryption is always on. Nothing to configure, no way to turn it off.

### Encryption — all native
- **Group messages:** Meshtastic channel encryption using the room's PSK (AES256)
- **Direct messages:** Meshtastic's native PKI encryption (`pki_encrypted` + `public_key`), giving DMs a pairwise layer on top of the channel key
- **Broadcast authenticity:** firmware 2.8+ signs broadcast messages with XEdDSA, so a group message can be verified as genuinely from its claimed sender — not just decryptable by key-holders. Shown as a "verified" badge only when the node reports signing support; 2.7 nodes rely on PKI for DMs and key possession for rooms

**A custom E2E layer was considered and rejected, and that decision has since been partly reversed.** The original reasoning — that X25519 + XChaCha20-Poly1305 would duplicate what native PKI DMs already provide, cost ~40 bytes of a ~200-byte budget, and need group key management that did not exist — still holds for **direct messages**, which remain native PKI and gain nothing from a second layer.

It does not hold for **rooms**. A room's PSK lives on the radio, so anyone holding the hardware can read the room, and every relay carrying the traffic is trusted with the same key the members use. A layer the radio never sees is the only thing that changes that. What exists now:

- `RoomCipher` — AES-256-GCM, 28 bytes of overhead, platform crypto only
- `SealedText` — a version byte plus ciphertext, 171 characters of room inside one packet
- `RoomKeyStore` — room keys wrapped by the Android Keystore, so copying the phone's files yields nothing
- `RoomAdmin` — ECDSA P-256, the signatures admin-only invites will rest on

**Sealed room text is not yet wired to the composer**; the primitives are built and tested, and key distribution is the next step. Receipts already travel sealed (see below).

### Who has read what

Every message records which phones reported holding it and which reported opening it, each with a time, shown in the message's info sheet.

- **Collected, not per message.** One packet carries 40 ids, so a morning's reading costs one transmission rather than forty. Read receipts leave after ~3 s, delivery receipts after ~30 s, both jittered; read supersedes delivered rather than adding to it
- **Sent only where they can be private.** Sealed under the room key for rooms, encrypted to one person for DMs, and **not sent at all** on an ordinary channel — announcing what you have been reading to everyone in earshot is worse than having no receipt
- **Accepted only where they were authenticated.** A receipt that arrived neither sealed nor PKI-encrypted is ignored, because anyone on a shared channel can put bytes on it under any name
- **Never claims more than it was told.** Only phones that reported appear. Nobody is ever listed as *not* having read something: a phone that says nothing has told us nothing

Room receipts are currently sealed under a key derived from the room PSK (`RoomCrypto.channelKey`), which every member already holds. That keeps relays and non-members out but not someone holding the radio; it moves to the Keystore-held room key once key distribution lands.

### The radio's clock

The radio stamps every message it hands over, and a unit with no GPS and no battery-backed clock stamps them with whatever it believes the time is — one on the test bench was fifteen hours behind, which filed a message that had just arrived under yesterday.

The skew is read from the packets themselves: the radio passes a packet up as soon as it has it, so its stamp and the phone's clock should agree. Past two minutes the app offers to set the clock from the phone. It asks rather than acting, because writing someone's hardware is their decision, and declining lasts until the radio connects again. `AdminMessage.set_time_only` is filed by the firmware as Net quality, below GPS, so a radio with a fix keeps the better time it has. Costs no airtime.

The display distrusts an implausible radio clock independently, since the radios of *other* people are not ours to set.

### How people join
- The room creator makes the first invite — as the only member, they are necessarily the first gate
- After that, any member can invite someone new
- **Generating and sending an invite is the approval.** There is no separate approve-the-request step, because by the time someone holds a QR or link+PIN they already hold the key — a later confirmation tap would gate nothing
- The roster shows who invited whom, so the trust chain stays visible

### QR invites — in-person, real-time
- Rotates every 5–10 seconds, HMAC-SHA256-based, combining channel provisioning with identity exchange
- Rotation narrows the *capture window* to whoever is physically present at that moment. It does not limit access afterward — once scanned, the joiner holds the room's PSK permanently

### Link + PIN invites — remote, async
- A fresh, unique URL + PIN pair per invite. Not on the QR's fast clock, which would kill async sharing. The PIN is 8 digits (shown 4-4) and the link payload is encrypted with a key derived from it (Argon2id + XChaCha20-Poly1305), so a captured link alone opens nothing
- Expires after 10–15 minutes unused, or immediately once used — whichever comes first
- Never extended. A stale invite means generating a new one, so every join traces to exactly one invite action
- Sender sees status (pending / expired / joined). A dead link shows "ask for a new invite," not a generic error

### What this does and does not guarantee
- The real access boundary is **who holds the PSK**. There is no per-person decryption gate on a shared broadcast key
- Single-use tracking on link+PIN invites is soft enforcement — the app can flag reuse, but cannot cryptographically prevent a copied invite from working within its window
- Group chat confidentiality depends on trusting everyone currently holding the room's key. Message *signing* (2.8+) proves who sent something; it does not stop a key-holder from reading everything
- Possession of invite material is the trust decision. The app shows who vouched for whom; it cannot evaluate their judgement
- Location requests are answered automatically at firmware level, with no prompt on the target's device. Room membership is the trust boundary here, consistent with everywhere else in this design
- State all of the above plainly in the UI, not just in this document

### Removing someone / leaving
- No server means no selective revocation of a shared broadcast key
- Removing someone requires **rotating the room's PSK and re-inviting everyone who stays** — a real cost, not a menu item. Design the flow to make that cost visible up front
- A removed member can no longer decrypt new traffic, but still hears the mesh at the radio layer, retains anything captured earlier, and may linger in other nodes' NodeDB until evicted
- Leaving a room frees its channel slot and triggers reindexing of the remaining rooms

## 7. Technology stack

- **App:** native per platform — Kotlin + Jetpack Compose (Android), Swift + SwiftUI (iOS). Two codebases, not shared
  - Cross-platform frameworks were ruled out: React Native has BLE issues under its New Architecture, JS bridge latency on binary protobuf data, and background BLE limitations. Native avoids all of it — each platform talks to BLE and parses protobuf with its own APIs
- **Firmware target:** Meshtastic v2.7+ protobuf API (generated from the 2.8 protobufs, a superset; 2.8-only fields decode as defaults on 2.7)
- **Transports:** BLE (primary), Wi-Fi/HTTP, Serial/USB
- **Libraries (decided):** protobuf code generated from Meshtastic protobufs tag v2.8.0 (Wire on Android, SwiftProtobuf on iOS); libsodium for invite cryptography; MapLibre + OpenFreeMap tiles with offline regions (OS maps rejected: no third-party offline mode); Room (Android) / SwiftData (iOS) storage; no analytics or tracking SDKs. Minimum iOS 17 / Android 10. Details in `meshchat-v1-scope.md`
- **Test hardware:** LILYGO T-Echo (nRF52840, L76K GNSS, e-ink), RAK WisMesh Tag / LW010-R (nRF52840, AT6558R GNSS, IP66)

## 8. Constraints & bandwidth strategy

**Hard limits:**

| Constraint | Value |
|---|---|
| Max packet size | 256 bytes |
| Max payload | 237 bytes (~200 usable for application data after Data-frame overhead) |
| Bandwidth | ~1 kbps shared across the whole mesh |
| Duty cycle (EU) | 10% |
| Latency | multi-second — not real-time |
| Channel slots | 8 total (1 primary + 7 rooms) |
| Default hop limit | 3 |
| NodeDB | device-dependent; **verify on the actual nRF52840 hardware rather than assuming ~100** |

**How the design responds:**

- Native packet priority: `ALERT` for urgent pings, `RELIABLE` for chat, background priority for routine position and telemetry — so chat never queues behind background traffic
- No custom periodic traffic. Chat, position, telemetry, identity and waypoints ride stock portnums; `PortNum.PRIVATE_APP` carries MeshChat's event-only control messages (join hello, roster events, receipts, sealed room messages, live-location requests), one packet each — decision D-2 in the implementation guide
- Live location on one room only, with interval scaling by duration and further backoff when channel utilization (ChUtil / AirUtilTX) runs high
- Character counter in the composer — messages that fragment across packets are slower and less reliable
- No app-invented polling or heartbeats. Only what Meshtastic already broadcasts
- Admin traffic stays on BLE — configuring a node never consumes LoRa capacity
- Position precision reduction where full precision isn't needed

## 9. Out of scope for v1

- **Phone battery sharing** — can't ride existing packets; not worth a dedicated one
- **Environmental telemetry** (temp/humidity/pressure) — not needed for this use case
- **Custom E2E encryption layer** — native encryption plus 2.8 signing is sufficient; revisit only on a changed threat model
- **Store & Forward** (offline catch-up) — needs more flash than the test devices have
- **Neighbor Info / topology view** — power-user feature
- **Remote admin channel** — BLE-only admin is safer and sufficient
- **Approval prompts on location requests** — reconsider if groups ever want tighter control
- **Open/public mesh chat** — MeshChat never joins public channels. An optional "Group + public relays" range mode lets public Meshtastic nodes rebroadcast the group's encrypted traffic (decision D-1); content, identity and telemetry stay private in both modes

## 10. Open items to verify on hardware

- Actual NodeDB capacity on the T-Echo and WisMesh Tag
- Real battery life with live location active at each interval tier
- Practical range and hop behavior in a dense crowd (bodies and structures absorb 868/915 MHz badly)
- Whether 2.8 signing is stable on both test devices
- BLE reconnection behavior when a phone holds two node sessions simultaneously
- Whether changing the primary channel name (Range mode switch) re-tunes the radio without a restart
- Which `set_config` writes restart the node on 2.7.26 vs 2.8 (reboot matrix, implementation guide §6.7)

## 11. Decision log (locked 2026-09-09)

| Decision | Choice | Where detailed |
|---|---|---|
| Product name | **Firepit**; "MeshChat" stays the internal protocol name (channel, port, protobuf package). Scheme `firepit://`; domain `getfirepit.com` (available, not yet registered) | scope doc §3, §7 |
| Firmware support | 2.7+ baseline; 2.8 features (signing badge) capability-gated | guide §2 |
| Range mode (primary channel) | Group only (default) / Group + public relays; app-wide key; per node, carried in invites | guide §9 D-1, §6.1 |
| Control messages | `PortNum.PRIVATE_APP` (256), `MeshChatControl` protobuf, event-only | guide §9 D-2, §6.8.5 |
| Link + PIN | 8 digits, Argon2id, XChaCha20-Poly1305, 15-min soft expiry, single use | guide §9 D-4, §6.8.4 |
| Roster trust chain | inviter broadcasts a JOINED event to the room | guide D-3 |
| Room messages | sent with `want_ack` for the "heard by the mesh" tick; no delivery claim | guide D-5, UX §7.2 |
| Live location | precision toggle per room for start/stop; duration tier change may restart the node; presets 15 min · 1 h · 8 h · Custom | guide D-6, UX U-6 |
| Telemetry | device telemetry on, every 30 min, primary channel | guide D-7 |
| Favorites | all room members favorited on the Personal node | guide D-8 |
| Reactions | yes, six fixed emoji 👍 ❤️ 😂 😮 😢 🙏 | UX U-1 |
| Precision | every room precise (32 bits); per-room reduced precision deferred | UX U-2 |
| Identity tag | 2 characters, default first + last initials, always editable, duplicate hint | UX §5.1, §7.1 |
| Room avatar | eight fixed icons (tent default), no emoji | UX U-5 |
| Nearby non-members | hidden (Diagnostics count only) | UX U-3 |
| Location cards | text card + "Open map", no map snippet | UX U-4 |
| Platform idioms | one design language (tokens, copy, flows); iOS uses HIG components, Android uses Material 3 components — mapping table in UX doc §9.6; reference renders `design/Firepit iOS UI.pdf` and `design/Firepit Android UI.pdf` | UX §9.6 |
| Tech stack | protobufs v2.8.0 + Wire/SwiftProtobuf; libsodium; MapLibre + OpenFreeMap; Room/SwiftData; real-device testing; no analytics; iOS 17+/Android 10+ | scope doc §4 |
| MVP cut | Personal node, QR rooms, room text, map + share location + pins, light theme | scope doc §2 |
| Still open | platform order (recommended Android first); Arabic in v1 or later | scope doc §6 |
