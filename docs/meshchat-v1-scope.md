# Firepit (MeshChat) — v1 Scope, Technical Decisions & Readiness

**Companion to:** `meshchat-app-design.md` (product), `meshchat-implementation-guide.md` (protocol/firmware), `meshchat-ux-design.md` (screens/flows)
**Status:** decision record, rev 1 (2026-09-09). Both apps are built from it. Where building changed a decision, the row says so; `build-plan.md` has the build status.

---

## 1. What "MeshChat" is, in one paragraph

A native iOS + Android app that turns Meshtastic LoRa radios into a WhatsApp-like group chat and live map for small groups with no phone signal. Private rooms (QR invites, scanned in person), honest delivery ticks, live location, pins, alerts, battery and signal per person, and simple admin of a Personal node plus optional Base/Router nodes. Works on firmware 2.7+, uses 2.8 features when present. Internally the protocol/channel name stays **MeshChat** regardless of the store name (§3).

---

## 2. Release cut

### v0.1 — MVP (internal, friends test)

| Area | In | Out (later) |
|---|---|---|
| Nodes | Personal node only; first run (pair → region → name + 2-char tag); reconnect; node status line | Base/Router admin |
| Rooms | create; join via **QR**; 7-room slot manager; leave; key rotation on removal | remote invites of any kind |
| Chat | room text, honest ticks, quick replies, reply-to | DMs, reactions, alerts |
| Map | members by tag/colour, live vs stale, share my location (one tier, 1 h default), pins | duration tiers, phone-GPS fallback, location requests |
| Status | node battery + signal in member sheet | — |
| Range mode | Group only | Group + public relays |
| Theme | light | dark |
| Maps | online tiles + cached viewed area | offline region download |

Exit criteria: two phones + two nodes, create room → invite by QR → chat → see each other on the map, all without internet, for a full afternoon.

**As built:** all of v0.1 on both apps except quick replies. The full-afternoon exit test has not been run yet.

### v1.0 — public beta

Everything in the design doc: DMs (PKI), reactions (6 fixed), alerts, location requests, duration tiers with phone-GPS fallback, Base/Router node admin with favorites and fixed position, Group + public relays mode, offline map packs, dark theme, diagnostics, 2.8 signing badge.

**As built:** all of it on both apps, except reactions.

### Not in v1 (from the design doc)

Phone-battery sharing, environmental telemetry, custom E2E layer, store-and-forward, neighbour/topology view, remote admin, approval prompts on location requests, public-channel chat.

---

## 3. App name

"MeshChat" collides with an existing Meshtastic web client (liamcottle/meshchat). Keep **MeshChat** as the internal protocol name (primary channel name, control port, invite scheme) so renaming the product never changes the radio behaviour. Store-name shortlist, checked 2026-09-09 for messenger collisions (trademark search still required before launch):

| Name | Why | Collision check |
|---|---|---|
| **Firepit** — **chosen 2026-09-09** | gather around the fire, matches the Ember palette, memorable, outdoors | no messenger found; discontinued "FireChat" is a different name |
| Lodestar | the star you navigate by; finding each other | only unrelated utility apps |
| Cairn | trail marker of stacked stones | a hiking-safety app already uses it |
| Hearth | warmth, home base | several small apps |
| Kindling | spark of connection | taken by a dating app |

Product name **Firepit**; internal protocol name **MeshChat** (primary channel name, control port `PRIVATE_APP`, protobuf package). Custom URL scheme `firepit://` for the QR payload only, never a shared link. The iOS bundle id and the Android application id are both `com.getfirepit.app`. The in-app title, tab and first-run copy say "Firepit"; the Figma frames still show "MeshChat" in the Chats header — update when the remaining screens are designed.

---

## 4. Technical decisions (plain-language explanation + choice)

| # | Topic | What it means | Decision |
|---|---|---|---|
| 1 | Platform order — **decided: Android first** | which app is written first; both follow the same docs | Android first (fastest Bluetooth iteration, the official Meshtastic app is Kotlin so patterns are reusable, no signing hurdles), iOS second reusing the exact same specs and test cases. If the team is iPhone-only, flip it — the docs do not change |
| 2 | Message definitions (`meshchat.proto`) | Meshtastic describes every radio message in `.proto` files; a tool turns those into Kotlin/Swift classes so both apps read the same bytes. MeshChat needs a small file of its own for the invite QR/link content and the control messages (join hello, roster events, live-location request) | Create `protos/meshchat/meshchat.proto` from guide §6.8.2 and §6.8.5; generate code for both platforms from the same file |
| 3 | Meshtastic protobufs version | which version of Meshtastic's message definitions the apps are built from | Generate from **protobufs tag v2.8.0** (superset of 2.7; fields a 2.7 node never sends just read as empty). Runtime target 2.7+. Pin the tag in the repo, bump deliberately |
| 4 | Code generation tools | the tool that converts `.proto` into app classes | Android: **Wire** (what the official app uses); iOS: **SwiftProtobuf** (what the official app uses) |
| 5 | App-wide primary key | the hidden "slot 0" channel needs a shared key so all MeshChat nodes can exchange names and battery. It is not a secret (it ships inside the app); real secrets are the room keys | Generate 32 random bytes once, store as `protos/meshchat-primary-key.txt` (base64), embed identically in both apps; never rotate without a protocol version bump |
| 6 | Cryptography library | code that derives a key from the invite PIN and encrypts the link payload; must be a reviewed library, never home-made | ~~libsodium on both platforms~~ **Superseded:** invites became QR-only, with no PIN and no link, so nothing needs a PIN-derived key. Both apps use their platform's reviewed cryptography (Java crypto and the Android Keystore; CryptoKit and the Secure Enclave), with HMAC-SHA256 for QR tokens. See `security.md` §2 |
| 7 | Maps | drawing the map and keeping it usable offline. OS maps (Apple Maps, Google Maps SDK) give third-party apps no reliable offline mode — they cache a little of what you viewed and evict it, and Google Maps' "offline areas" only work inside Google's own app | **MapLibre** (open source, iOS + Android) + **OpenFreeMap** vector tiles (free, no API key). Offline: MapLibre offline regions — download ~15 km around the current location when a room is created/joined on Wi-Fi/cellular. Fallback if OpenFreeMap changes terms: self-host Protomaps PMTiles |
| 8 | Testing | how correctness is checked | **Real devices**: 2 phones, 2–3 nodes (T-Echo + WisMesh Tag) on 2.7.26, one extra node on 2.8 for compatibility. Automated unit tests for pure logic (protobuf round-trips, invite crypto, slot manager, tick state machine, precision math). No simulator/Docker required |
| 9 | Repo + agent instructions | one Git repository holding both apps, the shared `.proto` files, docs, and a `CLAUDE.md` that tells AI coding agents the rules and where sources live | Create at coding start: `android/`, `ios/`, `protos/`, `docs/`, `design/`; `CLAUDE.md` = source map + pinned versions + rules from the guide §0.2 |
| 10 | Bluetooth libraries | how the app talks to the node | Android: **Kable** (chosen; multiplatform BLE, used by the official app); iOS: CoreBluetooth directly (as the official app) |
| 11 | Local database | where messages, members, rooms are stored on the phone | Android: Room (SQLite), encrypted with SQLCipher. iOS: **GRDB** running Android's Room schema and SQL (chosen over SwiftData/Core Data so both apps share one schema). Schema in UX doc §5.2 |
| 12 | Analytics / crash reporting | sending usage data to a server | **None.** No accounts, no telemetry, no third-party SDKs that phone home. Crash logs stay local and can be exported by the user |
| 13 | Languages (v1) | UI translations | English first; layout RTL-ready from day one so Arabic can be added without redesign (UX doc §9.5). Confirm whether Arabic ships in v1 |
| 14 | Minimum OS | oldest phones supported | iOS 17+, Android 10+ (API 29) — covers BLE and background needs without legacy branches |

---

## 5. Before the first line of code (checklist)

Kept as a record. Ticked items are done; `build-plan.md` tells the rest of the story.

- [x] Choose platform order (§4.1) and store name (§3); register bundle/app ids. (The invite-link domain was dropped: invites are QR-only.)
- [x] Create the repository and `CLAUDE.md`; copy the three docs + this file into `docs/`.
- [x] Write `protos/meshchat/meshchat.proto`; vendor Meshtastic protobufs at tag v2.8.0; set up Wire / SwiftProtobuf generation; commit generated code or generate in CI.
- [x] Generate and commit the app-wide primary key (§4.5).
- [x] Implement design tokens (Ember light/dark) as theme files first (UX doc §9.1). Android reference renders: `design/android/firepit-android-ui.pdf` (2026-09-10).
- [ ] Flash test nodes: two on 2.7.26 stable, one on the current 2.8 release; note their node ids.
- [x] Vertical slice: pair → config download → send/receive one room message with honest ticks (guide §4, §6.2). Everything else builds on this.

---

## 6. Open questions for the owner

1. ~~Platform order~~ Android first (started 2026-09-09 in Android Studio), iOS second from the same specs. Both are built.
2. ~~Store name~~ Firepit chosen. ~~Domain for links~~ not needed: invites are QR-only (§7).
3. Arabic in v1 or later. Layouts are RTL-ready on both apps; no translation exists yet.

---

## 7. Domain for invite links (checked 2026-09-09 via RDAP/whois)

| Domain | Status | Note |
|---|---|---|
| **getfirepit.com** | **available** | recommended: `https://getfirepit.com/i#…` universal links on iOS + app links on Android; also serves as the app's website |
| firepit.app | taken | — |
| firepit.chat | taken | — |
| firepitapp.com | taken | — |
| firepit.io | taken | — |

No longer needed for invites. Invites are QR-only and use the custom scheme `firepit://`, which never leaves the screen it is displayed on. A domain is still wanted for a website and store listing, but nothing in the product waits on it.
