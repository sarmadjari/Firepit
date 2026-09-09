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
│  ├─ app/                      MainActivity, DI graph, navigation host
│  ├─ core/
│  │  ├─ protocol/              [Stage 0] Wire protos, packet builders, ACK state machine,
│  │  │                         slot manager, precision math — pure Kotlin, fast JVM tests
│  │  ├─ designsystem/          [Stage 0] Ember tokens, type, components
│  │  ├─ model/                 [Stage 1] domain types
│  │  ├─ transport/             [Stage 1] Kable BLE, PhoneAPI session FSM, FromRadio pump, ToRadio pacer
│  │  ├─ crypto/                [Stage 4] libsodium: PSK gen, QR HMAC, Argon2id + XChaCha
│  │  ├─ database/              [Stage 2] Room entities/DAOs/migrations
│  │  ├─ data/                  [Stage 2] repositories, single source of truth
│  │  ├─ adaptive/              [Stage 3] window size class + hinge posture + pane scaffolds
│  │  ├─ service/               [Stage 2] foreground service for the Personal-node session
│  │  └─ testing/               [Stage 1] FakeTransport, packet fixtures, in-memory DAOs
│  └─ feature/                  onboarding · chats · rooms · map · settings
├─ ios/                         [Stage 10]
├─ protos/                      vendored Meshtastic @ v2.8.0 + meshchat.proto + primary key
├─ docs/                        design docs + this plan
└─ design/                      Figma exports
```

Modules are created when their stage needs them. Empty modules cost configuration time and
enforce nothing.

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

## Stage 2 — Data layer + honest message pipeline

- Room schema per UX §5.2.
- Repositories expose Flows; UI never touches transport.
- **Message status state machine** as pure logic:
  `Queued → SentToNode → ReachedMesh → Delivered / Unheard / Failed / Unknown`, driven by
  `QueueStatus` + `Routing`, distinguishing ACK-from-self (implicit) from ACK-from-peer (explicit),
  with the 120 s unknown timeout. Table-driven tests over every `Routing.Error`.
- Dedupe by `(from, id)`; validate all mesh strings at the boundary.
- Foreground service (`connectedDevice`) owning the Personal-node session, backoff reconnect.

**Exit proof:** message from phone A appears on phone B; ticks progress clock → grey → green.
Recorded fixtures reproduce every tick state in unit tests.

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

---

## Stage 5 — Chat complete

Composer (byte counter at 150, hard stop 200, 165-byte signing hint on 2.8 only), reply-to via
native `reply_id`, quick replies, message info (hops, SNR, timeline), system chips, notifications
with per-room mute, unread state, search.

**Exit proof:** an afternoon of three-person chat with no false delivery claims and no rate-limit
errors reaching the user.

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
