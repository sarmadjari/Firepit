# Firepit — Android build plan

Companion to `meshchat-app-design.md` (product), `meshchat-implementation-guide.md` (protocol),
`meshchat-ux-design.md` (screens), `meshchat-v1-scope.md` (decisions).

Ten stages, each with a demoable outcome and an exit proof. Adaptive/foldable work is a thread
inside every UI stage, never a stage of its own — retrofitting it later costs 3–4×.

**Decisions taken 2026-09-09:** Hilt for DI · Kable for BLE · RTL/Arabic supported (layouts
RTL-ready from Stage 3) · invite-link domain deferred until Stage 8.

---

## Module layout

```
Firepit/
├─ android/                     Gradle root
│  ├─ build-logic/              convention plugins (firepit.android.*, firepit.jvm.library)
│  ├─ app/                      MainActivity, DI graph, navigation host,
│  │                            and the feature screens: chat · rooms · map · radio · settings
│  ├─ core/
│  │  ├─ protocol/              [Stage 0] Wire protos, packet builders, ACK state machine,
│  │  │                         slot manager, precision math — pure Kotlin, fast JVM tests
│  │  ├─ designsystem/          [Stage 0] Ember tokens, type, components, icons,
│  │  │                         and adaptive/ — window size class + hinge posture + pane scaffolds
│  │  ├─ model/                 [Stage 1] domain types
│  │  ├─ transport/             [Stage 1] Kable BLE, PhoneAPI session FSM, FromRadio pump, ToRadio pacer
│  │  ├─ crypto/                [Stage 4] PSK generation, invite HMAC, rotating QR token.
│  │  │                         JDK primitives only; libsodium arrives with the Stage 8 link+PIN invite
│  │  ├─ database/              [Stage 2] Room entities/DAOs/migrations
│  │  └─ data/                  [Stage 2] repositories, single source of truth
├─ ios/                         [Stage 10]
├─ protos/                      vendored Meshtastic @ v2.8.0 + meshchat.proto + primary key
├─ docs/                        design docs + this plan
└─ design/                      Figma exports
```

Modules are created when their stage needs them. Empty modules cost configuration time and
enforce nothing.

Two planned modules were not created, because the code turned out not to need the seam. The
foreground service lives in `app` alongside the radio screens it serves, since it only anchors the
process and mirrors `RadioLink` state. Feature screens live in `app` rather than a `feature/` tree:
there is one app, and the split would have bought nothing but build files. `adaptive` is a package
in `designsystem` for the same reason. A shared `testing` module has not been needed either — the
pure-Kotlin layers are testable without fakes, which is most of why they are pure.

---

## Stage 0 — Foundations ✅ complete

| Task | Result |
|---|---|
| Repo root moved to `Firepit/` | 47 renames, history intact |
| Toolchain verified | AGP 9.4.0 → built-in Kotlin 2.2.10 (POM-confirmed); `CommonExtension` lost its type parameters; `defaultConfig`/`compileOptions` are getters only |
| Convention plugins | `firepit.android.application`, `.library`, `.library.compose`, `firepit.jvm.library` |
| Protos vendored | tag v2.8.0, commit `7b2464c`; `nanopb.proto` on protoPath |
| `meshchat.proto` | Invite + `MeshChatControl` (port 300) |
| App-wide primary key | 32 random bytes, base64 |
| Ember tokens | light + dark, all 28 values diffed against the Figma token sheets — zero drift |
| Contract canary | `ProtocolContractTest`: payload budget 233, port 300 legality, well-known portnums, packet round-trip, 2.8 fields defaulting on 2.7 |
| CI | build + JVM tests + lint on push/PR |

**Exit proof met:** `./gradlew build` green; the canary fails loudly if the proto pin moves.

---

## Stage 1 — Talk to a radio ✅ complete

Hardware-verified on a RAK WisMesh Tag (firmware **2.7.3**, EU_868) with a Galaxy
Fold (SM-F976B, Android 17).

| Proof | Result |
|---|---|
| Scan filtered by service UUID | only Meshtastic radios listed |
| Connect + config download | `Connecting → Downloading → Ready` in **4.6 s** |
| Snapshot contents | MyNodeInfo, DeviceMetadata, **8 channels**, LoRa config, 11 NodeInfos |
| Capability gating | PKI `true`, signing `false` — correct for 2.7.3 |
| Survives a radio reset | two cycles, each back to Ready in **~12 s**, then stable |

**Bug this stage existed to find:** a radio reset dropped the BLE link but the
app stayed on "Connected" forever. Kable's `observe()` Flow survives disconnects
by design, and an idle session issues no I/O, so nothing ever threw. Fixed by
racing the session against `peripheral.state`. Waiting for I/O to fail is not a
valid disconnect detector.

**Known rough edge:** after a reset, `PhoneApiSession.run()` can return normally
(the FromNum flow completes rather than failing), causing one redundant
reconnect with `attempt=0` before settling. Self-heals; tidy up in Stage 2 by
treating a completed notification flow as a lost link.

### Still to build in this area (moves to Stage 2)

- `OutboundPacer` and `MeshPacketBuilder` are written and unit-tested but not yet
  exercised against hardware — nothing sends mesh packets until Stage 2.
- `FakeTransport` lives in `:core:protocol` test sources; promote to
  `:core:testing` when `:core:data` needs it.

---

## Stage 2 — Data layer + honest message pipeline ✅ complete

Hardware-verified: two WisMesh Tags (SJ1 ↔ SJ2) on a shared secondary channel.

| Proof | Result |
|---|---|
| Outgoing ticks | `QUEUED → SENT_TO_NODE → REACHED_MESH` in ~4 s |
| Implicit vs explicit ACK | ACK arrived from **our own** node number → `REACHED_MESH`, never `DELIVERED` |
| Incoming text | received from SJ1 with SNR 5.75 / 6.5 and hop count |
| Unicode / RTL | Arabic `مرحبا` stored and rendered intact |
| Node table | 11 real mesh nodes, battery `101` correctly read as USB-powered |
| Persistence | messages and nodes survived an app restart |

Architecture: `:core:model` (domain types) → `:core:database` (Room, provides its
own Hilt bindings so `RoomDatabase` never leaks upward) → `:core:data`
(`MeshRepository`, the only thing that talks to the transport).

Status is **derived, never assumed**, and advances monotonically so an
out-of-order packet cannot downgrade a confirmed delivery. Room broadcasts stop
at "heard by the mesh".

Also fixed the Stage 1 rough edge: a completed notification flow now raises
`TransportClosed` rather than returning normally.

### Deferred from this stage

- Foreground service for the Personal-node session — the link currently dies with
  the process. Needed before any real field use; carry into Stage 5.
- `hopsAway` renders as "0 hops"; should read "direct". UI polish for Stage 5.
- Incoming messages are stored with `REACHED_MESH`, which is meaningless for a
  received message. Harmless (no tick is drawn) but worth a dedicated value.

---

## Stage 3 — Adaptive shell + design system  ← the foldable stage

Built before the feature screens so no screen is ever written phone-only.

**Navigation:** `NavigationSuiteScaffold` → bottom bar (compact) / nav rail (medium+) for the 3 tabs.

**Panes:** `NavigableListDetailPaneScaffold` + `SupportingPaneScaffold`, with `PaneScaffoldDirective`
derived from `WindowInfoTracker` folding features so the split **aligns to the hinge**
(`HingePolicy.AvoidSeparating`) in book posture.

| Screen | Compact (phone / folded outer) | Medium (unfolded, small tablet) | Expanded (tablet landscape) |
|---|---|---|---|
| Chats | list → push chat | list ∥ chat | list ∥ chat ∥ room info |
| Room chat | full width | detail pane | detail + room info |
| Map | full-bleed + bottom sheet | map ∥ docked people pane | map ∥ people ∥ member detail |
| Settings | list → push | list ∥ detail | list ∥ detail |
| Invite / Join / Node wizard | full-screen | centered dialog, max 600 dp | same |

**Folded outer display:** design to a **320 dp width floor and short heights**. No fixed widths.
Composer collapses `＋`/`⚡` into overflow. Bubbles keep the 78 % max-width rule. QR renders at
min(width, height) − padding.

**Fold/unfold survival:** single activity, `resizeableActivity=true`, no orientation locks, no
`configChanges` shortcuts. State in ViewModels + `rememberSaveable`. The two fragile cases are
CameraX (QR scanner) and the MapLibre view — both get explicit save/restore and a resize test.

**Components from the frames:** list row, chat bubble (incoming outlined, outgoing filled, time +
tick inline bottom-right), tick glyphs, identity avatar (2-char tag; label inverts with the theme),
room avatar (bubble-out fill + primary glyph), filter chips, system chip, date separator, ALERT
bubble (2 px warn border, bell + label), location card, **squircle FAB**, send button (↑ in a
primary circle), warn callout (warn at ~12–14 % over surface-2, body text in `text`, icon in `warn`),
PIN entry boxes, QR card (**stays white in dark mode** — scannability beats theming).

**Exit proof:** kitchen-sink screen and Chats shell render correctly at 320/480/600/840/1200 dp;
folding mid-scroll and mid-QR-scan loses no state; `@PreviewScreenSizes` + `@PreviewFontScale`
snapshots checked in.

---

## Stage 4 — Rooms and QR invites

- **Slot manager** (slots 1–7, consecutive, keyed by `room_id`), reindex-on-leave as a
  property-tested pure function.
- Create room → 32-byte PSK, `set_channel` (no reboot), precision 32.
- Primary channel (slot 0): app-wide key, precision 0, Range mode = Group only.
- **Rotating QR**: HMAC-SHA256 invite key, 8 s window, ±2 window tolerance, `firepit://join?v=1&d=…`.
- **Join handshake in the mandated order**: NodeInfo hello on the room → ~5 s → PKI DM `JoinHello`
  on port 300. Reversing it fails — PKI needs the peer's key in NodeDB first.
- Favorite every member so NodeDB eviction cannot break the room.
- Admin plumbing: session passkey; the **reboot matrix** encoded as data so every write knows
  whether to warn "node restarts ~10 s".

**Exit proof:** A creates, B joins by scan, both rosters show "invited by"; 100 random leave
sequences keep slots consecutive with `room_id → settings` intact.

### Built

- `ChannelSlotManager` — slot allocation and reindex-on-leave, property-tested over 200 random
  leave sequences.
- `RoomCrypto` / `InviteCodec` — PSK generation, HMAC-SHA256 invite key, 8 s rotating token,
  `firepit://join?v=1&d=…`. JDK primitives only; libsodium arrives with the Stage 8 link+PIN invite.
- `NodeAdminClient` — session passkey, `set_channel`, `get_channel`, `set_owner`, favorites.
- `RoomRepository` — create, invite, join (NodeInfo hello → 5 s → PKI DM on port 300), leave.
- UI — create dialog with an 11-byte counter, rotating QR invite, CameraX + ZXing scanner.
  ZXing does both generation and decoding, which keeps ML Kit and Play Services out of the build.
- **Roster** (`room_members`, DB v2) — who we have seen in a room, with `invited_by` provenance.
  Receiving `JoinHello` and broadcasting `RosterEvent` were designed in `meshchat.proto` but had
  never been implemented; the joiner was talking to nothing.
- **Roster sync** (DB v3) — on a proved join the inviter DMs its roster to the joiner, so a new
  member does not wait for everyone to speak. Directed rather than answering a broadcast
  "who is here?", because on LoRa every member replying is a packet storm on every join.
  Capped at 14 entries; a contract test pins the worst case at 208 of 233 bytes.

Two gaps in the invite chain surfaced while wiring the receiving side. `JoinHello` echoes a token
but not the window it was minted in, so it could not be verified at all — hence
`RoomCrypto.matchesRecentToken`, which searches ~2 minutes of windows. And `invite_id` was
regenerated on every 8 s rotation, so an arriving hello could never be tied back to a room; it is
now stable per room while the invite screen is open, with the rotating token still doing the
security work.

### Not verified

**The join handshake has never run against a real radio.** It needs two phones driving two nodes
and only one Android device is available. `JoinHello` verification, the `RosterEvent` broadcast and
the roster sync are therefore unproven on hardware, as is firmware-level PKI encryption of the DM
and whether 5 s is enough for the NodeInfo hello to propagate.

Verified on hardware: DB migrations v1→v2→v3 against a live database with messages and nodes
intact, and the rooms UI rendering on the Fold.

Two ways to close most of this without a second phone, both deferred by decision:

- A loopback-transport integration test driving two `RoomRepository` instances, which would cover
  the inviter side and run in CI permanently.
- A synthetic invite minted on a desktop and scanned by the phone, which would exercise the real
  `set_channel` write — the riskiest untested call.

### Deferred from this stage

- `buildInvite` hardcodes `generation = 1`. Stage 8 key rotation must store it per room, or a
  rotated room keeps minting invites for the old key.
- The camera permission path is untested: builds so far were pre-granted with `pm grant`.

---

## Stage 5 — Chat complete

Composer (byte counter at 150, hard stop 200, 165-byte signing hint on 2.8 only), reply-to via
native `reply_id`, quick replies, message info (hops, SNR, timeline), system chips, notifications
with per-room mute, unread state, search.

**Exit proof:** an afternoon of three-person chat with no false delivery claims and no rate-limit
errors reaching the user.

### Built

- **Foreground service** — `RadioService` anchors the process so the session survives backgrounding.
  It does not own the link; `RadioLink` stays the singleton owner and the service only mirrors state
  into a notification. `START_NOT_STICKY`, because a system-restarted service has no radio to
  reconnect to and a notification claiming otherwise would be false.
- **Composer** — hard stop at 200 bytes via `MeshConstants.truncateToBytes`, which walks code points
  so Arabic and emoji are never split. The 165-byte signing hint appears only when the radio reports
  `supportsSigning`, so 2.7 firmware shows nothing rather than warning about a feature it lacks.
- **Replies** — native `reply_id`, with the quote inside the bubble: accent bar, panel washed in the
  original sender's identity colour, tap to jump to the original.
- **Message info** — hops, SNR, RSSI, signature, status. Values the radio did not report are omitted
  rather than shown as zero, because "0 dB" and "not measured" are different claims.
- **Chat styling** — `buildChatItems` groups runs from one sender and inserts day separators, kept
  pure so the rules are testable without a screen.
- **Unread and mute** (DB v4) — per-channel read position and mute preference. Unread counts come
  from a SQL join against the read mark, so they cannot drift from the messages themselves. Muting
  still counts unread; it only stops the interruption.
- **Notifications** — raised only when nobody is looking: `ChatPresence` reports the open
  conversation and app foreground state, and duplicates are suppressed because only genuinely new
  rows are emitted.
- **Search** — filters the open room, and pauses autoscroll so jumping to the newest message does not
  fight the reader.

Two protocol facts settled here. Text compression is already done by the firmware with Unishox2 and
`portnums.proto` says apps should not do it themselves, so Firepit does not. That check found us
decoding `TEXT_MESSAGE_COMPRESSED_APP` as UTF-8, which would have stored mojibake as somebody's
words; the port is now dropped with a log.

Received messages also got their own `MessageStatus.RECEIVED` instead of borrowing `REACHED_MESH`,
and delivery updates now refuse to touch anything not outgoing, so a packet-id collision cannot
rewrite a message somebody sent us.

### Not verified

The exit proof needs three people and only one Android device is available, so multi-party chat is
unproven. Delivery-status logic is unit-tested and was verified two-way on hardware in Stage 2.

Verified on hardware: DB migration v3→v4 against a live database, and the foreground service holding
the session through 70 s of confirmed Dozing with zero link transitions.

### Deferred from this stage

- **Quick replies** — not built.
- **Swipe-to-reply** — long-press works and carries an accessibility label, but there is no visible
  affordance and swipe is the gesture people reach for. Best done alongside quick replies, since
  both belong in the same gesture layer.
- Notification actions (reply from the shade, mark read) — the notification only opens the room.
- Pre-existing incoming rows keep `REACHED_MESH`. Never rendered, so not worth a data migration.

---

## Stage 6 — Map and location

- MapLibre + OpenFreeMap; markers = 2-char tag in identity colour, **live ring in `live` green**,
  stale at 50 % opacity with a grey ring and age label.
- Share my location: exactly one channel with non-zero precision, ever — enforced in the repository
  and asserted on every reconnect.
- Pins = native waypoints; delete = re-send with `expire = 1`.
- Precision truncation matching firmware `truncateCoordinate` bit-for-bit (property test).
- Liveness uses `last_heard`, not positions alone — 2.8 relays dedupe identical positions for up to 5 h.

**Exit proof:** two people find each other outdoors using only the app.

### Built

- **`PositionPrecision`** — truncation matching the firmware bit for bit: mask the low bits, then add
  half a cell so the point sits at the centre of its area rather than a corner. Property-tested over
  500 random inputs, including the invariant that a result always lands on a cell midpoint. Full and
  disabled precision are special-cased because Kotlin masks `Int` shift counts to five bits, so
  `1 shl -1` would silently become `1 shl 31` and move the point across the planet.
- **`PositionSharing`** — the one-channel rule as data, re-asserted whenever the radio reports its
  channels rather than trusted to the UI. Two enabled channels means two audiences, one of which the
  user never chose, so it fails closed by disabling both.
- **Phone GPS** — the platform `LocationManager`, not Play Services: an off-grid app should not need
  Google services to know where it is. Fed to the radio via `localPacket` with hop limit 0, so the
  injection never goes on air itself.
- **Map** — MapLibre + OpenFreeMap, node markers as identity-coloured tag discs with a live ring,
  a blue you-are-here dot for yourself, and reduced opacity when the sender truncated their fix.
- **Waypoints** — native Meshtastic waypoints, so other clients see our pins and we see theirs.
  Deletion is re-broadcast with `expire = 1`, because there is no delete on the wire. New pins are
  `locked_to` the person who dropped them.
- **Offline areas** — `OfflineManager` regions with a name, download date, tile count and estimated
  size, plus update and delete. `TileEstimate` computes exact slippy-map tile counts; the byte figure
  is presented as an estimate because vector tiles vary hugely between open country and a city.
- **Settings tree** — Radio → Nodes, Map → Offline areas, About.

### Not verified

The exit proof needs two people outdoors and has not been attempted. Everything below was confirmed
on hardware: DB migrations v4→v5→v6 against a live database, positions arriving from five other
nodes over the mesh, our own fix stored from the phone, and markers, pins and the self dot rendering
(checked by sampling screenshot pixels, not by reading logs).

### Bugs this stage exposed

Four silent failures, all of the same shape — an early return that discarded a real condition:

- `MeshRepository` had no `POSITION_APP` branch at all, so every position the mesh delivered was
  dropped. The map could never have worked.
- `storeOwnPosition` returned silently when the node number was unknown, hiding the reason our own
  dot was missing.
- `MarkerLayer.draw` returned when the map style had not finished loading, discarding the markers.
  The style loads asynchronously, so this was the normal case, not an edge case.
- `frameAll` returned early when the map was not ready, but the caller latched "already framed"
  regardless, leaving the camera at 0°,0° in the Atlantic permanently.

The bug that actually kept the markers invisible was none of those: OpenFreeMap serves glyphs only
for **Noto Sans**, while MapLibre's `SymbolManager` defaults to asking for `"Open Sans Regular"`.
The glyph fetch failed and the symbols never drew, with nothing in the log. Every symbol now names
its font. `iconAllowOverlap` is also on, since a node hidden by collision is a person missing from
the map.

### Deferred from this stage

- Marker taps opening node detail.
- "Open in Maps" handoff for turn-by-turn, which platform map apps do better than we should try to.
- Position auto-stop after a chosen duration, and its persistence across restart.
- Multi-node sessions. The docs call this core: one persistent link to the Personal node plus
  independent on-demand sessions for Base and Router nodes, never letting an on-demand session steal
  the Personal node's slot. `RadioLink` is currently a singleton built for exactly one radio, so this
  needs a session manager rather than a UI change.

---

## Stage 7 — v0.1 MVP hardening + field test

Battery profile over 8 h, ChUtil observation, reconnect storms, empty/error states,
permission-denial paths, crash-free session.

**Gate:** two phones + two nodes — create → invite → chat → map, a full afternoon, no internet.

---

## Stage 8 — v1.0 features

In dependency order: DMs (`add_contact` before every DM) → link+PIN invites (Argon2id 64 MiB,
XChaCha20-Poly1305, 8-digit PIN) → alerts → reactions (6 fixed) → duration tiers + phone-GPS
fallback → Base/Router admin (roles, fixed position, favorites) → key rotation → Group + public
relays mode → offline map packs → dark theme → diagnostics → 2.8 signing badge.

Register the invite-link domain before the link+PIN work.

## Stage 9 — Release prep

Accessibility sweep (48 dp targets, TalkBack labels on every tick glyph, contrast), **RTL/Arabic
pass**, R8 config, 16 KB page-alignment check on native libs (libsodium, MapLibre), Play listing,
no analytics confirmed.

## Stage 10 — iOS

Same specs, same test cases, same `protos/`; SwiftUI + SwiftProtobuf + CoreBluetooth + SwiftData +
MapLibre.

---

## Test matrix (from Stage 3 onward)

| Device | Why |
|---|---|
| Pixel-class phone | baseline |
| Resizable emulator — Foldable 7.6″ | book posture, hinge-aligned panes |
| Resizable emulator — Flip cover / 320 dp | the width floor |
| Tablet 10–13″ | 3-pane, nav rail |
| Any device, fold/unfold mid-action | state survival (camera + map) |

Espresso Device API (`setDisplaySize`, `setScreenOrientation`) for automated fold simulation;
`@PreviewScreenSizes` for review-time checks.

---

## Open decisions

| # | Decision | Status |
|---|---|---|
| 1 | **Licensing.** The vendored Meshtastic protobufs are GPL-3.0, so generated-and-linked code makes Firepit a derivative work. Every official Meshtastic client is GPL-3.0. Path of least resistance: license Firepit GPL-3.0. | **Needs owner decision before public release** |
| 2 | Invite-link domain registration | Deferred to Stage 8 |
| 3 | Arabic copy translation (layouts are RTL-ready regardless) | Ships in v1 |
| 4 | **Map engine.** MapLibre only. Google Maps and MapKit cannot pre-cache arbitrary regions, so they show a grey grid off-grid — the one occasion the app matters. Google Maps also needs Play Services and an API key, and a second SDK would double the marker, camera and fold/unfold handling. MapLibre has an iOS SDK, so one implementation serves both platforms. | **Decided** |
| 5 | **Tile hosting.** OpenFreeMap is donation-funded. Offline downloads must be capped by area and zoom; self-hosting or a bundled base map is the responsible move if usage grows. | Revisit before public release |
