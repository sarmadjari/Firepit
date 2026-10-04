# Firepit — build plan

How the apps were built, stage by stage: Android first (Stages 0–9), then the iPhone port (Stage 10).
Each stage records what it built, what broke, and what is still unverified.

Companion to `meshchat-app-design.md` (product), `meshchat-implementation-guide.md` (protocol),
`meshchat-ux-design.md` (screens), `meshchat-v1-scope.md` (decisions).

Eleven planned stages (0–10), each with a demoable outcome and an exit proof, plus seven the work
itself called for (7.5–7.9, 11 and 12). Adaptive/foldable work is a thread inside every UI stage —
retrofitting it later costs 3–4× — and Stage 12 is the exception that proves it: the 2026 foldables and
the map beside the chat change the shape of the whole app, not one screen.

**Decisions taken 2026-09-09:** Hilt for DI · Kable for BLE · RTL/Arabic supported (layouts
RTL-ready from Stage 3) · invite-link domain deferred until Stage 8.

## Status

| Stage | What it delivers | Status |
|---|---|---|
| 0–2 | Foundations, talking to a radio, the data layer and honest message pipeline | ✅ Built |
| 3–6 | Adaptive shell and design system, rooms and QR invites, chat, map and location | ✅ Built. The exit proofs that need people outdoors wait for the field test |
| 7–7.9 | Hardening, identity and privacy, sealed rooms, the two-phone bench test, two security reviews | ✅ Built. A full-afternoon field test and battery over 8 h are not yet measured |
| 8 | v1.0 features | ✅ Built, emoji reactions last (2026-10-04) |
| 9 | Release prep: accessibility and RTL pass, R8, store listing | ✅ Done 2026-10-04: accessibility, right to left, R8, 16 KB pages, store listing, no analytics. Left for release day: the release key and store submission |
| 10 | The iPhone app | ✅ Built. An iPhone and an Android phone in one room over real radios is still to be tested |
| 11 | Security within Meshtastic's limits: Signal-grade protections where the radio allows, with no message growing by a byte | In progress: Phases 1, 2, 3, 5 and 6 done on both apps; Phase 4 next |
| 12 | Large screens: the conversation and the map side by side on foldables, tablets and wide windows; the phone app in narrow ones | Built on both apps, 2026-10-04 (decisions U-8 to U-14, UX §11.4). Open: the chat-to-map actions, which need features not built yet; the iOS 27.1 fold APIs; Figma frames; device runs on an iPhone Duo, a Flip and a TriFold |

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
│  │  │                         JDK primitives only; invites are QR-only
│  │  ├─ database/              [Stage 2] Room entities/DAOs/migrations
│  │  └─ data/                  [Stage 2] repositories, single source of truth
├─ ios/                         [Stage 10] Xcode app + Packages/FirepitKit (one target per core/ module)
├─ protos/                      vendored Meshtastic @ v2.8.0 + meshchat.proto + primary key
├─ docs/                        design docs + this plan
├─ design/                      brand, Figma exports, reference renders (design/README.md)
├─ scripts/                     cross-platform checks and iOS tooling (verify-all.sh runs them all)
└─ archive/                     not maintained: design prototype, MeshChat-era design, reorg record
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
| `meshchat.proto` | Invite + `MeshChatControl` on `PortNum.PRIVATE_APP` |
| App-wide primary key | 32 random bytes, base64 |
| Ember tokens | light + dark, all 28 values diffed against the Figma token sheets — zero drift |
| Contract canary | `ProtocolContractTest`: payload budget 233, `PRIVATE_APP` is 256, well-known portnums, packet round-trip, 2.8 fields defaulting on 2.7 |
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

Superseded in part by Stage 12: on a wide window the map no longer takes turns with Chats, it sits
beside the conversation, and the rail gives way to the two panes (UX §6.11).

---

## Stage 4 — Rooms and QR invites

- **Slot manager** (slots 1–7, consecutive, keyed by `room_id`), reindex-on-leave as a
  property-tested pure function.
- Create room → 32-byte PSK, `set_channel` (no reboot), precision 32.
- Primary channel (slot 0): app-wide key, precision 0, Range mode = Group only.
- **Rotating QR**: HMAC-SHA256 invite key, 8 s window, ±2 window tolerance, `firepit://join?v=1&d=…`.
- **Join handshake in the mandated order**: NodeInfo hello on the room → ~5 s → PKI DM `JoinHello`
  on `PRIVATE_APP`. Reversing it fails — PKI needs the peer's key in NodeDB first.
- Favorite every member so NodeDB eviction cannot break the room.
- Admin plumbing: session passkey; the **reboot matrix** encoded as data so every write knows
  whether to warn "node restarts ~10 s".

**Exit proof:** A creates, B joins by scan, both rosters show "invited by"; 100 random leave
sequences keep slots consecutive with `room_id → settings` intact.

### Built

- `ChannelSlotManager` — slot allocation and reindex-on-leave, property-tested over 200 random
  leave sequences.
- `RoomCrypto` / `InviteCodec` — PSK generation, HMAC-SHA256 invite key, 8 s rotating token,
  `firepit://join?v=1&d=…`. JDK primitives only; invites are QR-only, so no Argon2 and no libsodium.
- `NodeAdminClient` — session passkey, `set_channel`, `get_channel`, `set_owner`, favorites.
- `RoomRepository` — create, invite, join (NodeInfo hello → 5 s → PKI DM on `PRIVATE_APP`), leave.
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

> **Superseded by Stage 7.7.** Two phones arrived, the handshake ran, and it failed for a reason
> the single-device build could not show. The NodeInfo-hello-then-wait ordering is gone, and the
> invite no longer carries the PSK. Read Stage 7.7 for what actually ships.

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

- **Quick replies** — built 2026-10-04 on both apps. ⚡ beside the message box opens a row of chips,
  and one tap sends one through the same paced queue as anything typed, without touching the draft;
  a reply being written applies to it too. Five defaults ("On my way", "Where are you?", "Wait for
  me", "I'm here", "OK"), up to ten, each at most 40 bytes, edited in Settings › Quick replies. The
  rules are one pure `QuickReplies` object on both apps, with the same tests.
- **Swipe-to-reply** — built 2026-10-04 on both apps. A message dragged towards the reading end follows
  the finger a short way over a reply arrow; past the mark a haptic says letting go will reply. Long-press
  and the Reply accessibility action still work.
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

### Built

- **Traceroute.** The roster can ask the mesh how it reaches a member. This exists because
  the obvious request — "how many nodes heard my message" — cannot be answered: implicit ACK
  stops at the first overheard rebroadcast, and no count is carried. Rather than invent a
  number, the app measures the path on demand and says plainly that a path is not a receipt.
- **Channel congestion warning** above the composer. Read from telemetry as it arrives, not
  once from NodeInfo at connection, which would have aged into a lie within minutes.
- **Automatic reconnect** to the last radio on launch, by scanning for its identifier rather
  than addressing it directly, so a radio that is off simply never appears. An explicit
  disconnect forgets it, so the next launch does not undo a deliberate choice.
- **Permission-denial paths.** Camera and location denial now explain themselves and offer
  the settings screen instead of failing silently.

### Bugs this stage exposed

- **The GPS ran while the app was backgrounded.** `DisposableEffect` fires when a screen
  leaves the composition, and backgrounding does not do that. `dumpsys location` showed our
  client active 33 of the last 35 minutes with `locations = 0` — burning power for a map
  nobody could see. Now bound to the lifecycle; verified by watching the system log a
  `-registration` on pressing Home.
- **Notification permission was handled by catching the failure.** A denied permission then
  looked exactly like a delivered notification. Now checked, and the suppression is logged.
- **The app never reconnected to your radio.** Every restart needed a manual trip through
  Settings. Found by trying to test reconnect storms and getting no link at all.

### Not verified

- **Battery over 8 h.** Cannot be measured while the phone is tethered for adb, which it must
  be to drive these tests. What was checked instead: no wakelocks are held, and the location
  request is `LOW_POWER`. The 8 h figure needs the phone unplugged and left alone.
- **Reconnect storms.** Bluetooth was cycled and the app survived without crashing, but with
  no radio connected at the time this proves only that nothing threw.
- **Crash-free session.** No crash or ANR across this session's repeated restarts, but that is
  an afternoon of hammering short of the claim.
- **The gate itself.** Still needs a second phone and a second node. Unmet since Stage 4.

---

## Stage 7.5 — Identity, privacy and receipts

Not a planned stage. It came out of asking what "private and secure by default" actually
requires, checking each answer against the firmware docs, and finding several places where
the app's claims were ahead of its code.

### Built

- **Several radios, one phone.** Devices are separated from nodes, each device says what it
  is for (Personal / Base / Router), and infrastructure can extend the mesh without crowding
  the map.
- **The person is separate from the radio they carry.** Name, tag and colour follow the
  person; the mesh still sees only the node.
- **Rooms can be left, and take their history with them.** Channel slots must stay
  consecutive, so leaving re-packs them; messages are keyed by slot, so they are moved with
  the rooms rather than silently re-attributed.
- **Message retention** — day, week or month, with forgetting the only option.
- **Crypto primitives** — `RoomCipher` (AES-256-GCM), `SealedText`, `RoomAdmin` (ECDSA
  P-256), `RoomKeyStore` (Android Keystore). Platform crypto only, no new dependencies.
- **Receipts.** Who holds a message and who has opened it, with times, batched 40 to a
  packet, sealed where they can be and not sent where they cannot. See the design doc.
- **The radio's clock.** The skew is measured from the packets themselves and the app offers
  to correct it, since a radio fifteen hours out files today's conversation under yesterday.

### Bugs this stage exposed

- **Opening a room crashed the app.** `Key "day-2026-09-11" was already used`. The list was
  ordered by the phone's clock and grouped by the radio's, and `rx_time = 0` — a radio that
  has never been told the time — was stored as 1970 rather than as no time. One 1970 message
  between two of today's gave `Today, 1970, Today`. The two neighbouring lines already
  guarded against a zero SNR and a zero RSSI; this one did not.
- **`leaveRoom` would have corrupted history**, for the slot-repacking reason above.
- **`INSERT OR IGNORE` does not cover foreign keys**, only uniqueness and check constraints.
  A receipt naming a message this phone never received would have thrown. Caught by a test
  written against a DAO comment that claimed otherwise.
- **Tokenless invites never expired.**
- **`allowBackup="true"`** — messages went to Google Drive by default.
- **Notifications showed message text on the lock screen.**
- **DM receipts would have been broadcast** on the channel the message arrived on,
  announcing to everyone in earshot what had just been read.
- **Receipts were accepted unauthenticated**, so anyone on a shared channel could forge one.
- **The read loop re-reported every visible message** on each list change, which would have
  re-sent the same receipts forever — the flood batching exists to prevent.

### Corrections to work done in this stage

- **`MESHCHAT_TEXT_PORT = 301` could never have worked.** The generated `PortNum` enum has no
  such value, and a commit message claimed sealed text travelled on it. Sealed messages are a
  `MeshChatControl` payload on `PRIVATE_APP` like everything else. `MESHCHAT_CONTROL_PORT`
  was removed for the same reason: nothing sent on 300 either.
- **`roomIdForChannel` and `channelKeyFor` were copied into two repositories**, one carrying a
  comment noting it mirrored the other.

### Not verified

- **Receipts crossing the air.** Needs a second phone running Firepit. The wire format,
  packet budget and sealing are covered by tests; the traffic is not.
- **Sealed room text end to end** — the primitives are tested, the composer is not wired.

---

## Stage 7.6 — Sealed rooms, receipts, key rotation

### Built

- **Sealed room words.** A room mints its own key into the Android keystore, an invite carries it,
  leaving forgets it. The radio never holds it. 171 bytes per packet once the version byte, nonce
  and tag are paid for.
- **One envelope for everything sealed.** Words and receipts are both an encrypted `MeshChatControl`
  wrapped in another, so traffic shape does not distinguish a conversation from an acknowledgement.
- **Receipts** — who holds a message and who has opened it, batched 40 to a packet, sealed where
  they can be and **not sent at all** where they cannot. Shown on the sender's own bubble.
- **Key rotation on removal** — new generation, new keys handed to each remaining member as a PKI
  direct message, a notice on the old key for whoever missed it, and a line in the room naming who
  was removed. History is stored opened, so it stays readable; since Stage 11 the old generation's
  key itself is deleted two hours after the move.
- **Room lifetimes** — optionally leave a room after a month or three months of silence. Off by
  default.
- **The radio's clock** — measured from the packets themselves, offered for correction when it is
  more than two minutes out.
- **Location never on the primary.** Only a room may carry position.
- **Invites are QR-only.** Link and PIN dropped; see design §6.

### Not built

- **Admin-signed invites.** `RoomAdmin` exists and is tested; nothing issues or verifies grants
  yet. Depends on rotation, which now exists: grants are bound to a generation, so bumping the
  generation is what demotes an admin.

### Deferred from this stage

- **Encrypted database** — deferred here, done in Stage 7.9. The reasoning at the time was that
  Android already encrypts a locked phone's disk; that holds only until the first unlock after a
  restart, which is almost never the state a phone is taken in.

  Tried once with SQLCipher and reverted, which is worth recording. The one-time migration of an
  existing plaintext database goes through `ATTACH` + `sqlcipher_export()`, and in
  `sqlcipher-android` 4.9.0 no encrypted file ever appeared. Stage 7.9 hit the same wall on 4.19.0
  and found a cause: `ATTACH` inherits the main connection's open flags, so a database opened
  read-write without `CREATE_IF_NECESSARY` cannot create the file it attaches (`SQLITE_CANTOPEN`).
  The migration now opens with that flag, on one connection out of WAL mode, and checks the copy
  before the plain file goes. The instrumented test that reads the bytes back caught it both times.

---

## Stage 7.7 — Two phones, and what they broke

The first stage with two radios on a bench. Nearly everything here is a correction rather than a
feature: the single-device build had been passing its tests while getting the join wrong.

### The join handshake, redesigned

The old flow broadcast a NodeInfo on the room, waited 5 s for it to propagate, then sent a PKI DM.
On hardware the DM simply never arrived: the joiner logged "asked … to be let into room" three
times and the inviter logged nothing. Both app databases held each other's public keys, and both
radios had heard each other minutes earlier.

**The app knowing a key is not the same as the radio knowing it.** The firmware encrypts from its
own NodeDB, which is bounded and evicts, and the app's database has no bearing on it. The NodeInfo
broadcast had been papering over this by chance.

What ships instead — and it removed a security hole rather than just a bug:

- **The invite carries no keys.** `room_psk` and `firepit_key` are `reserved` in `Invite`, with the
  reason written next to them in the proto. A photograph of the code yields a room name and the
  inviter's public key.
- **Both sides seed their own radio** with `AdminMessage.add_contact` / `SharedContact` — the
  joiner from the invite, the inviter on approval. No broadcast, no waiting.
- **The grant is encrypted to a key carried inside the sealed hello** (`JoinHello.joiner_key`),
  not to a NodeDB lookup that any radio can poison by claiming a node number.
- **A person taps "Let in".** A token proves the invite is genuine; it cannot prove the bearer is
  the person it was shown to. Declining sends `RoomGrant{ DECLINED }` so the joiner is told rather
  than left waiting.
- Rate limit of 5 attempts per node per minute, one-shot `invite_id`, and a zero-hop requirement:
  a code is shown to somebody in front of you, so anything relayed was read somewhere you cannot
  see.
- The invite screen sets `FLAG_SECURE`.

**Verified on hardware:** full handshake logged on both phones, room `-371805330` present in both
databases with both members.

### Built

- **Person cards** — name, tag and colour, shared automatically on joining and on being joined,
  jittered across `GREETING_SPREAD` so a join does not become a packet storm.
- **Map freshness** — fix ages beside each name ("4 min", "2 h"), a live ring at 2 minutes and a
  separate 15-minute staleness rule, sweeping only room members rather than every marker.
- **Position requests** — tap a marker to ask. `PositionAnswer` names every way it can fail
  (`NoFix`, `Silent`, `NotConnected`, `NoSharedRoom`) rather than spinning.
- **Clock correction** (`RadioClock`) — the phone's clock wins when the radio's is implausible, so
  messages are not filed in 1970 or next year.
- **Message alerts** (`MessageAlerts`) — phone only, or phone and node buzzer, preserving the
  GPIO pins already configured on the node.
- **Firmware floor** — 2.7.0. Below it `RadioLink` enters a terminal `Unsupported` state and says
  so, rather than failing later in a way that looks like a bug in the room.
- **Radio takeover undo** (`PrimaryBackup`) — the original slot 0 is recorded before Firepit
  claims it, so a borrowed radio can be given back.
- **Settings, reorganised** — You / Radio / Notifications / Messages / Map / Appearance / About,
  on shared `SettingsGroup`, `SettingsChoice` and `SettingRow` primitives.
- **One app bar** (`FirepitTopBar`) across chat, map and settings; sheet shapes unified.
- **Chat opening behaviour** — no keyboard on landing, and the list lands on the newest message.
  The old scroll used `messages.lastIndex` against a list that also renders day separators, so it
  was always short by the number of days.

### Bugs this stage exposed

- Chat showed **device names, not person names** — it read the node record instead of the card.
- **Role icons overrode card tags** in the avatar precedence.
- **Traceroute leaked on the public primary.** Now a `require()` in `MeshPacketBuilder`, alongside
  three other guards, so it cannot be sent from the wrong channel at all.
- Clearing focus on conversation change did not stop the keyboard — the detail pane's focus
  hand-off ran after it. The composer now refuses focus until a real tap
  (`focusProperties { canFocus = … }` flipped by `awaitFirstDown`).

### Accepted, not fixed

- **Room members can forge positions.** 2.7 does not sign channel traffic. The 2.8 signing badge
  is the answer and it is in Stage 8.
- **The primary key is community-wide**, by design (D-1). It protects against outsiders, not
  against other Firepit users.
- Six separate `OutboundPacer` instances. Correct today because each handles a distinct portnum;
  worth consolidating into one injected singleton before that stops being true.

### Two holes the second phone did not find

Both came out of asking what a *third* node would change, and neither needed one to fix.

- **The hop rule failed open.** It read `if (hop_start > 0 && hops > 0) reject`, so a packet with
  `hop_start = 0` skipped the check entirely. Neither field is authenticated: a sender three hops
  away can leave `hop_start` at zero to look unmeasurable while setting `hop_limit` high enough to
  be relayed, and it arrives claiming a *negative* distance. Now `PacketOrigin.arrivedDirectly`,
  which accepts only `hop_start == hop_limit` and is tested over every pair in 0..7.
- **A grant was believed from anyone.** `handleRoomGrant` checked `room_id` and `invite_id` against
  what we asked — but both of those travel in the QR code. Anyone who photographed it could answer
  first with their own PSK, putting the scanner in *their* room under the name they expected. PKI
  did not help: encrypting to a public key is something the public can do. The fix is to record
  who we scanned (`AwaitedRoom.inviter`) and require the reply to come from them; because step A
  seeds that node's key from the code, the firmware will only decrypt a reply its holder signed.

The second one is the more serious: it was a room-substitution attack reachable by exactly the
adversary the invite redesign was built around, and it survived the redesign because the checks
that replaced the old ones looked sufficient in isolation.

### Not verified

Three or more phones. What that would still tell us, now that relayed joins are refused by
construction:

- that 2.7.3 populates `hop_start` / `hop_limit` the way the protocol documents, on real relayed
  traffic — the rule is right given the documented semantics, but the semantics are assumed
- that a join *correctly fails* across a relay rather than failing for some unrelated reason
- roster sync against more than two members, though its worst case is pinned by
  `ProtocolContractTest` at 208 of 233 bytes using max-length varint node numbers, so the cap is
  arithmetic rather than hope

**By design, and worth saying plainly: a join cannot cross a relay.** Both people must be in RF
range of each other — the same requirement as pointing a camera at a screen. Out of range, the
request times out. This is the security property working, not a bug, but it is the first thing a
user will report.

---

## Stage 7.8 — Security review

A full review of what the mesh can make the app believe. Ten findings, all fixed; the rules now
live in `TrustRules` as data, each with the attack it stops written beside it as a test.

### The one that mattered most

**Anyone could join a room's roster, then take over its keys.** A `RosterSync` was accepted from
anybody who named a room id — and the room id is printed in every invite. The roster is the list a
rotation hands new keys to, and a rotation was accepted from anyone on it. A stock radio and the
python CLI were enough. Now a roster sync is only believed privately from the inviter who let us in,
`JOINED` only sealed on the room's slot, membership never from unsealed traffic, and a rotation only
when it is sealed under the key it replaces.

### Phone keys

The firepit key travelled inside PKI direct messages, which the receiving *radio* decrypts — and the
firmware hands a radio's private key to any phone that connects. Whoever held a member's radio could
therefore recover the room key from any recorded grant or rotation, which is exactly the person the
firepit key exists to shut out. Each phone now has its own P-256 key pair (`PhoneKeyStore`, wrapped
by the Keystore), and the firepit key only ever travels sealed to one (`KeyEnvelope`: ECDH, HKDF,
AES-GCM, all platform). `JoinHello`, `PersonCard` and sealed `JOINED` events carry phone keys;
`RoomGrant` and `KeyRotation` carry `sealed_key` instead of `firepit_key`. DB v11 adds `peer_keys`.
Wire-incompatible with builds before it — acceptable before release.

### Also fixed

- A join hello had to be PKI but its key was never checked against the key it came under, so a
  spoofed hello could redirect the grant. Now bound (`MeshPacket.public_key`), a second hello cannot
  swap keys, and both phones show the joiner's key fingerprint before anyone taps "Let in".
- Scanning a code acted at once and rewrote the radio's key for whatever node the code named. Now a
  scan is confirmed against the inviter's fingerprint first, a known key is never overwritten, and a
  grant cannot replace a room already held unless a member moves it forward.
- Invites were never actually spent: every redraw reset `usedBy`.
- Unsealed text on a room's slot was shown as the room's words, and plain-text DMs under the channel
  key as authenticated. Both dropped. Sealed messages are only believed on their own room's slot.
- Pins were taken from any channel, including the published primary, and locks were ignored.
- A Meshtastic link could silently replace a Firepit room with the same name.
- A radio read that timed out rewrote a moving room with an empty key — which the firmware reads as
  the primary's, a published one — and could forget a sharing deadline while the radio kept
  broadcasting.
- Leaving a room gave every room above it the key of the room below: each write named the slot a room
  was moving to, and the key was read from that slot rather than the one it was leaving. Pins now
  move with their rooms too, where before they stayed on the old slot number.
- Anyone could add themselves to a message's "read by" list.
- Notifications had no public version for a secure lock screen.

### Accepted, not fixed

Written up in `docs/security.md` §1: a member can forge sealed text as another member; positions and
telemetry are accepted from any channel; a member can announce a false phone key for someone else, which cannot let them read that
person's next key but can make that person miss it.

---

## Stage 7.9 — Data-flow assessment

A second review, of every path data takes: phone to radio, radio to air, air to other phones, and
what stays on each. Twenty-two findings, all fixed, plus one product decision: **locations and pins
are sealed like words**, so the radio's own position broadcast is off on every channel and sharing
pauses while the phone is away from its radio. The WisMesh Tag's button ping no longer reaches
anyone under Firepit.

### High

- **Removing a member could leave another member talking only to the removed person.** A rotation
  counted a member as reached once our own radio took the packet; one out of range kept the old
  keys, which the removed person also held. Now each member counts only on their own ACK, their key
  goes back into our radio first (`add_contact`), anyone missed is handed the key when next heard
  (`pending_handovers`), and a sealed `KEY_ROTATED` goes out under the old key before our radio
  moves, so a member who misses their copy stops sending (`RoomKind.FIREPIT_MOVED_ON`).
- **Direct messages were protected only by the radios**, which hand their private keys to any
  connected phone. Now sealed phone to phone (`DirectSeal`: ECDH P-256 between the two phones'
  keys, HKDF, AES-GCM) inside PKI; people whose phone key is unknown get PKI with a warning.
- **The Bluetooth link was never checked.** Screenless radios pair with `123456`. The radio's
  pairing, admin key, managed mode, admin channel, debug log and MQTT settings are now checked on
  every connection, each with a one-tap fix (`RadioSecurityCheck`), and a saved radio's identity
  is pinned.

### Medium

- The database is encrypted with SQLCipher 4.19 (`DatabaseEncryption`, key wrapped by the
  Keystore, opened on first use off the main thread); an old plaintext database is rewritten and
  checked before the plain file goes. DB v12.
- Timed sharing now lives on the phone, so it ends on time whichever radio is connected.
- Forgetting a radio can take Firepit's rooms off it and restore its primary; a lost radio can be
  rotated out of every room.
- The primary's key is described as what it is: shared by every copy of the app.
- "Leave quiet rooms" works (`room_activity`); positions, strangers, cards, phone keys, pins,
  tombstones and browsed map tiles now expire, and settings can erase history.
- The join fingerprint covers the joiner's phone key as well as their radio key.

### Low

Pins sealed (so locks answer to the room key); delivery only from the recipient's ACK; roster sync
and direct receipts sealed, none to strangers; every sealed room packet asks for an ACK; a room
whose key is missing refuses text; screenshots and Recents blocked by default; notifications name
nobody unless asked and stay off watches; the map asks before framing the group online and sends a
neutral User-Agent; keyboards told not to learn; history filed by room, not slot (`RoomHistory`);
the private-radio choice is per radio. Also: precise location restored on Android 12+, which a
library manifest had capped at API 30.

### What the review of the fixes caught

An independent review of this stage found eleven problems in the fixes themselves, all corrected:
leaving an id-less Meshtastic channel deleted every other one's history, and could pick the wrong
one (channels are now named by slot); leaving raced history placement (slot rewrites and placement
now share a lock, and a room's history is deleted by its id); a member who missed two rotations was
sealed a key they could not open (each handover is now sealed under the generation that member
holds); pending handovers were recorded only after the ACK waits; a notice naming a far-future
generation could stall a member for good; Meshtastic-channel history could be parked for ever; a
sealed direct message the other phone could not open read as delivered (`SealedDirectRefused`);
quiet-room detection ignored your own messages and shared positions; mutes were reset by the upgrade
and by switching radios (now kept per room); sharing survived leaving the room; and a radio could
pass the identity pin by reporting no key.

### Verified

493 JVM tests, lint and compiler clean; 19 instrumented tests on an API 37 emulator: the plaintext
database really is rewritten encrypted, Room opens it, every schema from 1 to 12 upgrades, and the
history queries keep each room's history with it. The app starts, its database file is ciphertext,
and a screenshot of it comes out black.

---

## Stage 8 — v1.0 features

In dependency order: DMs (`add_contact` before every DM) → alerts → reactions (6 fixed) → duration tiers + phone-GPS
fallback → Base/Router admin (roles, fixed position, favorites) → key rotation → Group + public
relays mode → offline map packs → dark theme → diagnostics → 2.8 signing badge.

**Status:** all built on both apps; reactions were the last, on 2026-10-04 (UX §5.4). Diagnostics are the radio details screen,
traceroute and the channel-congestion warning. The signing badge is "Signature: Verified" in the info
of any message the firmware marked as signed, and the radio's details say whether it can sign
("Signed messages").


## Stage 9 — Release prep

Accessibility sweep (48 dp targets, TalkBack labels on every tick glyph, contrast), **RTL/Arabic
pass**, R8 config, 16 KB page-alignment check on native libs (SQLCipher, MapLibre), Play listing,
no analytics confirmed.

**Done (2026-10-04):**
- **No analytics:** neither app has an analytics, crash-reporting or tracking library, and the only
  network calls are map tiles from OpenFreeMap, which offline-only mode stops.
- **Store listing:** `store-listing.md`, with the privacy answers from that audit.
- **Tick glyphs:** every status glyph already had its spoken label on both apps.
- **Contrast:** three light-theme tokens fell short of WCAG AA and are now the nearest passing shade
  of the same hue: `live` #22915C (was 2.45:1 on the surface, now 3.73), `stale` #888179 (2.49, now
  3.60) and `warn` #895F00 (4.39, now 5.31). `stale` is for rings and icons only; the four places
  that used it for text now use the secondary text colour, and the congestion warning uses `warn`.
  `DesignSystemTests` holds every pair: text at 4.5:1, rings, dots and ticks at 3:1.
- **Touch targets:** the one target under 48 dp, the × that clears a room's search, is now a full
  target on both apps.
- **R8** shrinks the release build: the arm64 APK is 20.8 MB instead of 68.6 MB. Libraries bring
  their own keep rules; `proguard-rules.pro` adds SQLCipher's, whose native code calls back by
  name. Run on the Galaxy Fold: chats, map, Settings, Devices and Quick replies with no crash. A local
  release build can be signed with the debug key to try it (`-Pfirepit.debugSignedRelease`); the store
  build uses the release key.
- **16 KB pages:** every native library (MapLibre, SQLCipher, CameraX, graphics) is 16 KB aligned in
  the APK and in its ELF load segments, as Android 15 requires on 16 KB devices.
- **Right to left:** both apps mirror. iOS was run in the demo world with right-to-left forced (the
  room, the chat list): the back button, bubbles, quote bar, composer and list rows all mirror.
  Android was run on the Galaxy Fold with Firepit set to Arabic: the bar's title and actions, the
  filter chips, the tabs and the new-room button all swap sides. The words stay English: there is no
  Arabic translation yet, which is a release decision rather than a layout one.

## Stage 10 — iOS ✅ built

A one-to-one port of the Android app, from the same specs and the same `protos/`. Android stays the
reference: when the two disagree, Android's code decides.

### How it is built

- **`ios/Packages/FirepitKit`**, a Swift package with one target per Android `core/` module:
  `FirepitProtos` (SwiftProtobuf 1.38.1, generated from a mirrored copy of `protos/`), `FirepitModel`,
  `FirepitProtocol`, `FirepitCrypto` (CryptoKit, Secure Enclave), `FirepitTransport` (CoreBluetooth)
  and `FirepitData` (GRDB 7.11.1, Keychain). Tested on the Mac with `swift test`.
- **`ios/Firepit`**, the SwiftUI app: `DesignSystem/` ports `core/designsystem`, `Features/` ports
  `app/` (Chat, Rooms, Map, Location, Radio, Settings, Notifications, Privacy), with MapLibre Native
  6.31.0 for maps. `AppContainer` wires everything by hand, in place of Hilt.
- **Same names, same SQL.** Types and members keep their Kotlin names (`scripts/check-port-parity.py`,
  run per file). The database runs Android's Room schema v12 and its exact DAO queries under GRDB
  (`scripts/check-dao-parity.py`, `SchemaFixtureTests`), so both apps store data the same way.
  SwiftData, the original plan, was dropped for this reason.
- **Demo mode** (debug builds): `-demo` runs the app against a pretend radio with populated rooms, and
  `-route <feature>.<screen>` opens one screen directly, so every screen can be reviewed without hardware.

### Verified

- Both crypto implementations open each other's output: room seals, key envelopes, direct messages,
  invite codes and tokens, and Meshtastic channel links (`scripts/check-android-interop.sh`).
- Two simulated phones on a simulated mesh run the room flows end to end: create, invite and
  approve, sealed room texts and replies, sealed direct messages, rotating a member out, handovers,
  leaving, receipts and person cards (`RoomEndToEndTests`, `SimulatedMesh`).
- Colours match Android's tokens in both themes (`DesignSystemTests`); screens were reviewed against
  Android's in light and dark.
- The app installs and runs on a real iPhone.

### Not verified

- **An iPhone and an Android phone in the same room over real radios.** The tests say they will
  interoperate, but no field test has shown it yet.
- Hours of background Bluetooth on iOS, which wakes the app when it decides to, and battery use.

### Where it differs

What iOS allows differs from Android in places: screenshots cannot be blocked, the Keychain survives
a reinstall, and there is no keyboard learning switch. (The database used to have iOS file encryption
only; since Stage 11 Phase 5 it is SQLCipher, as on Android.) `architecture.md` §10 and `security.md` §11 list them all.

---

## Stage 11 — Security within Meshtastic's limits

The goal is Signal-grade protection wherever the radio allows it, under two hard rules: no chat message,
receipt, position or pin grows by a single byte, and nothing about chatting changes. Signal's own methods
do not fit as they are: its ratchets send new public keys with messages and its post-quantum keys are
about 1 KB, larger than four LoRa packets. So each protection here is rebuilt from what the radio can carry.

**The byte budget stays.** Sealing cost 29 bytes before this stage: a version byte, a 12-byte random
nonce and a 16-byte tag. Version `02` keeps all 29 and changes only what the nonce carries: a 2-byte number for the
hour the key belongs to, then 10 random bytes. Each sender has a key of their own for each hour, so 10
random bytes are far more than a nonce needs.

### Phases

1. **Forward secrecy and replay protection for rooms.** Every hour each phone derives the next room key
   from the current one with HKDF, a one-way step, and deletes the old one after an hour's grace for late
   packets. No radio traffic is needed: every phone computes the same next key. Each sender seals with a
   key of their own derived from the hour's key. A phone remembers the nonces it has seen in the hours it
   can still open and refuses exact copies; once an hour's key is gone, a recording of it cannot be opened
   at all. A joiner receives the current hour's key rather than the room's first one, so they cannot read
   what was recorded before they joined. Old key generations are deleted after a grace period instead of
   being kept for good. Someone who later steals a phone's keys reads at most about the last hour of what
   they recorded, not the room's whole history.
2. **Forward secrecy for direct messages.** The phone-to-phone key is mixed with the current hourly key of
   a room both people share. An attacker then needs a phone's private key and that hour's room key, which
   no longer exists after the hour; other members still cannot read it, because they hold neither phone's
   private key. The receiver tries the rooms it shares with the sender, at most 7 rooms and 2 hours, so the
   message carries nothing extra.
3. **Quantum hedging through the in-person invite.** The invite QR gains a 16-byte random secret that is
   shown on screen and never transmitted, and it is mixed into the key that protects the Firepit room key
   inside the grant. Someone who records every radio packet and later has a quantum computer still cannot
   open that phone-sealed room key, so cannot open anything that follows from it. The radio PKI layer and
   channel PSK stay classical. Key changes are mixed with the current hourly key, so every link traces
   back to a secret that never went over the air. Radio bytes are unchanged; the QR is slightly denser.
4. **Recovery after a break-in.** Room keys change on a schedule (a setting: daily by default, weekly, or
   never) through the existing key-change messages, one small packet per member and never per chat
   message. Android's phone key moves into secure hardware through Keystore key agreement (Android 12 and
   newer), as the iPhone's already lives in the Secure Enclave: a key that cannot be copied means a thief is
   locked out at the next change. A contact's phone key changing raises an alert.
5. **Phone storage.** The iPhone's database is encrypted with SQLCipher, as on Android, and key material is
   wiped from memory where the platforms allow.
   - **Done 2026-10-04.** GRDB 7.11.1 is built on SQLCipher 4.19 from a local copy
     (`ios/Packages/GRDB-SQLCipher`, following GRDB's own "GRDB+SQLCipher" instructions). The key is 32
     random bytes in the Keychain (`AfterFirstUnlockThisDeviceOnly`), given to SQLCipher raw (`x'…'`), and
     the bytes read from the Keychain are wiped once the key is formed. An old unencrypted database is
     exported into an encrypted one on first open, keeping every row; checked on a simulator with a
     database written by the previous build. `DatabaseEncryptionTests`, and every FirepitKit test now
     runs on SQLCipher. Swift cannot promise a wiped `String`, so the key never becomes one.
6. **Proof of sender, within the limits.** Meshtastic 2.8 radio signatures are shown per message, at no cost
   to the message. Phone keys learned in person always win over later announcements, which closes the gap
   where a member could name a false key for someone else.

### Phase 1 record

Done on both apps, byte for byte, with no change to any chat, receipt, position or pin size
(`ProtocolContractTest`, `SealedTextTest`):

- `RoomRatchet`: hour `h` counts UTC hours since 1970; `E(h+1) = HMAC(E(h), "firepit-hour-v1" ‖ room ‖
  generation ‖ h+1 ‖ 1)`; a sender's key is `HMAC(E(h), "firepit-sender-v1" ‖ room ‖ generation ‖ h ‖
  sender ‖ 1)`. A message opens in the hour just gone, this one or the next.
- `SealedText` version `02`: the nonce starts with the hour's low 16 bits; version `01` is no longer
  read.
- `RoomKeyStore` keeps one hour's key per generation, moves it on and erases older hours every ten
  minutes, keeps a superseded generation two hours (or while a member is owed a key sealed under it),
  and stores keys from before this stage as belonging to 2026-01-01T00:00Z, so every phone migrates to
  the same keys without a word.
- `SeenSeals` refuses a second copy of any sealed room message, kept on disk across restarts.
- `RoomGrant.key_hour` and `KeyRotation.key_hour` (about 4 bytes, control messages only) say which
  hour's key is handed over; the hour is bound into the key envelope's context.
- Tests: known answers computed with plain HMAC (`RoomRatchetTest`/`RoomRatchetTests`), the key store
  on iOS and on an Android device (`RoomKeyStoreTest`), replay, and simulated-mesh scenarios for clocks
  forty minutes and three hours apart, a member back after three days, a recording played back, a
  recording kept past its hour, and a joiner. `scripts/check-android-interop.sh` opens each app's
  hourly keys and seals in the other.

Closed before Phase 2, after an independent review of the first pass:

- **A wrong clock erases nothing early** (`KeyClock`). Erasing follows the earlier of the phone's
  clock and the time really passed since it last kept step, measured by the monotonic clock, kept
  across app restarts. The plan named the radio's GPS time as the check; it is not one here, because
  Firepit takes positions from the phone and a radio's clock is often set from the phone, so the app
  cannot tell GPS time from the phone's own.
- **Handovers are settled by the member's app**, not their radio: by anything they seal under the
  new key. A member shares their card as soon as they take one. Retries stop after three radio
  acknowledgements unless the member is heard still sealing under the old key.
- **A member on an older build is named in the room**, once, instead of their messages failing in
  silence. Their messages still cannot open: reading the old format would keep the old keys alive.
- **The rotation bookkeeping has no gap** the hourly erase could fall into, `holds()` runs under the
  key store's lock, and temporary keys are wiped on Android.
- **A second review of these fixes** found four more, now closed: the anchor is only kept across app
  restarts where the phone gives a boot id, and a fresh message from the room re-anchors it; a
  rotation coming before a member confirmed the last is sealed under every generation they might
  hold; a retry in flight can no longer revive a settled handover (`touch` updates only the record
  it tried); proof a member still holds the old key has its own retry interval.
- **Android's `RoomKeyStoreTest` runs on a device**: 17 of 17 against the real Keystore, on the
  Android 17 emulator.

What remains is a rule for rollout, not a defect: every phone in a room needs this build. And before
release, an independent review by a person, which no automated review replaces.

### Phase 2 record

Done on both apps, byte for byte, with no change to any direct-message or receipt size
(`ProtocolContractTest`, `ProtocolContractTests`):

- `DirectSeal` version `02`: the nonce starts with `RoomRatchet.tagOf(hour)`, then the same ten random
  bytes used by room seals. Version `01` direct seals still open, for peers with no shared room key.
- When sealing to somebody who shares a Firepit room, the phone mixes that room's current hourly key
  into the static phone-to-phone ECDH secret:
  `roomPart = HMAC(E(h), "firepit-direct-room-v1" ‖ room_id ‖ generation ‖ h ‖ 1)`,
  `PRK = HMAC(ordered(phone publics), ECDH ‖ roomPart)`,
  `key = HMAC(PRK, "firepit-direct-v2" ‖ sender ‖ recipient ‖ 1)`.
- Receivers resolve the hour from the nonce tag with the same room-message window, then try the room
  generations they still hold. A replayed v2 direct seal is refused through `SeenSeals`.
- A room is used for v2 only on positive evidence: this phone has opened a current-generation seal
  from that member in that room. Roster entries and pending handovers are not enough, so a member who
  left, missed a third member's rotation or is in the middle of a handover gets the next eligible room,
  or v1.
- If a v2 direct seal is refused, the sender clears that room/member evidence and resends the same text
  using the next eligible v2 room only; the replacement takes the original row's place and keeps its
  timestamp. There is no automatic downgrade to v1. If no other v2 room is eligible, the original is
  marked "They could not open it." A refusal is honoured only from the peer the packet was sent to,
  attempts expire after ten minutes and are capped at 64, a refusal for a message already confirmed by a
  sealed receipt is ignored, and replayed or out-of-window v2 copies are dropped without a refusal. The
  sender's card is shared again after a real refusal.
- If no shared room key is eligible, direct messages and receipts fall back to v1 exactly as before.
- Tests: plain-HMAC known answers for `roomPart` and the v2 key (`DirectSealTest`/`PhoneSealTests`),
  v2 round trips, wrong-room-key and tamper failures, v1 compatibility, direct-message replay and stale
  generation fallbacks in the simulated mesh, Android `RoomKeyStoreTest` coverage for direct replay/window
  handling, and `scripts/check-android-interop.sh` for Android→iOS and iOS→Android v2 direct seals.

### Phase 3 record

Done on both apps, with no change to any radio packet size (`ProtocolContractTest`,
`ProtocolContractTests`):

- `Invite.secret` is 16 random bytes, shown only in the QR. It is generated once
  per `invite_id`, kept only in memory with the inviter's `IssuedInvite`, stable
  across QR redraws while that id is live, and dropped when the invite expires
  or is spent. Reopening an invite after the ledger TTL gets a fresh id and
  secret. An invite without exactly 16 bytes is unusable.
- A joiner keeps the scanned secret only in `AwaitedRoom` while waiting for the
  grant. It is never copied into `JoinHello`, never put on the radio and never
  persisted.
- `KeyEnvelope` v1 is unchanged when no hedge is supplied. With a hedge, both
  apps compute
  `inviteHedge = HMAC(secret, "firepit-invite-hedge-v1" ‖ room_id ‖ invite_id ‖ 1)`,
  then `PRK = HMAC(ephemeral ‖ recipient, ECDH ‖ inviteHedge)` and
  `key = HMAC(PRK, "firepit-key-envelope-v2" ‖ context ‖ 1)`. The envelope size
  stays 93 bytes.
- A `RoomGrant.sealed_key` is sealed and opened with the hedge for the invite it
  answers. A grant whose Firepit room key does not open with the scanned secret is refused; there
  is no v1 fallback. `KeyRotation` is unchanged because it already travels
  inside a `SealedMessage` under an hourly room key the member holds.
- Tests: plain-HMAC known answers and v1 compatibility in `KeyEnvelopeTest` /
  `PhoneSealTests`; invite secret round trips and stripped-invite refusal in
  `RoomCryptoTest` / `InviteTests`; iOS simulated mesh proves a normal join, a
  recorded grant will not open with only the joiner's phone key, and a stripped
  invite cannot be used. `scripts/check-android-interop.sh` writes and opens
  hedged envelopes in both directions.

### Phase 6 record

Done on both apps, with no change to any on-air message:

- Meshtastic 2.8 signed room messages carry a visible per-message mark and say "Signature: Verified" in
  message info. The mark is purely a display of the radio header bit; it adds no bytes.
- `peer_keys` is schema v14 and records whether a phone key was checked in person on this phone.
  Android migration 13→14 and iOS `v14_key_provenance` add `inPerson` with default false.
- `TrustRules.shouldStorePhoneKey` now takes `IN_PERSON`, `VOUCHED` and `ANNOUNCED` sources. First sight is
  kept; the same key seen in person is marked in-person; a different in-person key replaces any old key; a
  vouched `JOINED` replaces only a key that was not learned in person; a card never replaces.
- Replacing a different stored phone key writes a direct-chat notice: "Sam's phone key changed. If they did
  not get a new phone, check with them in person." It is a history row, not a notification.
- Tests cover the rule table on both apps, Room/GRDB migrations to v14, and direct notice filing/peer
  mapping. The sealed-`JOINED` existing-member attack is covered by the iOS simulated mesh; Android's
  JVM coverage stops at the shared rule table and UI/database peer mapping.

### Rules for every phase

- Android first; iOS ported with the same names and the same bytes.
- New interop vectors, opened in both directions by `scripts/check-android-interop.sh`.
- A contract test proves the sealing overhead and every message limit are unchanged.
- Simulated-mesh scenarios: a member back after days away, late packets across the hour, phone clocks an
  hour apart, replayed copies, joiners, and recordings that stay closed once keys are deleted, even with
  the phone's own key.
- A wrongly set clock must not make a phone delete keys it still needs; the phone's monotonic clock is
  the check (`KeyClock`).
- `security.md` and `architecture.md` change in the same commit as the behaviour.
- An independent review before release.

### Not possible without adding bytes

Per-message recovery after a break-in (it needs a new public key in every message), per-person signatures
inside a room (64 bytes each) and ML-KEM post-quantum keys (about 1 KB). Each is weighed again if the
zero-size rule is ever relaxed.

---

## Stage 12 — Side by side: foldables, tablets and split screen

On a phone, Firepit's conversation and map take turns. On a screen wide enough for both they should sit
side by side, with the user choosing the arrangement, and in narrow windows Firepit stays the phone app.
The design is UX §6.11; this is how it gets built.

**Why now**

- **The hardware.** Samsung's Galaxy Z Fold8 Ultra (8.0″ inner screen, about 10:9) and the new wide
  Galaxy Z Fold8 (7.6″, 4:3, landscape-first) shipped in July 2026, beside the Flip8; the Galaxy Z
  TriFold since December 2025; the Pixel 11 Pro Fold in August 2026. Apple's iPhone Duo was announced on
  9 September 2026 and goes on sale on 23 October with iOS 27.1. Its inner screen is landscape when open,
  and iOS reports it as regular width.
- **The platforms.** Android 17 removes the opt-out from resizing on screens 600 dp wide or more for
  apps targeting API 37, which Firepit does. With the iOS 27 SDK, supported orientations become a
  preference, and folding an iPhone Duo is a window resize.
- **The product.** A group on the move wants to talk and see where everyone is at once.

**What already exists**

- **Android (Stage 3):** a rail from 600 dp; list ∥ conversation with `NavigableListDetailPaneScaffold`;
  `foldAwarePaneDirective` (two panes from medium width, the hinge avoided); the tab survives a fold
  (`rememberSaveable`); no orientation locks; the 320 dp floor. The map is a tab of its own, and nothing
  links a conversation to it.
- **iOS:** iPhone only (`TARGETED_DEVICE_FAMILY = 1`), a three-tab `TabView`, no size-class logic.
- **MapLibre:** Android 13.6.1 and iOS 6.31.0 already include the fixes that matter here. The
  split-screen freeze (maplibre-native #3836) was fixed in 13.5.0, and the display scale now comes from the
  window's own screen (ios-v6.28.0), which matters on two-screen devices.

### Phases

1. **One layout rule, shared.** `PaneLayout` is a pure function in a Kotlin core module, tested on the
   JVM, with a Swift port in FirepitKit. Both use the same names and the same table of cases, and
   `check-port-parity.py` holds them together.
   - **In:** the window's width and height (dp or pt); the fold (none, lying flat at a position,
     separating down the screen, or separating across it, with its bounds); the narrowest chat side
     the current text size allows; and the user's choice (arrangement, map side, and divider position for
     upright and for wide windows).
   - **Out:** one pane (which one); two side by side (bounds, divider anchors, and whether the divider is
     locked); two stacked; or three. Also which navigation shows: bar, rail or none.
   - **Cases:** at least 40. They cover every device size in UX §6.11.2, both sides of 600 and 1200
     wide and of 480 tall, each pane minimum, right-to-left, and a locked divider.
2. **Android shell.**
   - The top level chooses one pane (today's tabs), two, three or stacked from `PaneLayout`.
   - The Chats and Map content are `movableContentOf`, so changing arrangement moves them instead of
     rebuilding them: the MapLibre view, the scroll position and the draft all stay.
   - The chat side's list-detail scaffold gets a directive from the chat pane's own width, not the
     window's. Otherwise it would split again inside a 376 dp pane.
   - **Divider:** a small custom layout (`SidePanes`). Material's pane expansion works inside one
     scaffold, and the two sides here are separate screens. While dragging, a line shows where the
     divider will land. On release both panes take their new width once, because MapLibre's
     `SurfaceView` blocks the UI thread on every size change. It settles on ⅓, ½, ⅔ or the fold, by the
     shared `PaneLayouts.settle`.
   - **Fold and posture** come from `currentWindowAdaptiveInfoV2().windowPosture` (hinge bounds,
     `isTabletop`). A separating fold locks the divider to it; tabletop stacks the panes.
   - **Navigation:** no rail with two or three panes. ⚙ on the chat list opens Settings across the
     window, and back acts on the side touched last.
   - **Keyboard:** `imePadding` moves from the shell to the chat side, and the map side ignores the
     keyboard.
   - **Preferences** live in SharedPreferences like the app's other settings (`LayoutPreferences`:
     arrangement, map side, and the divider for upright and wide windows). They are read synchronously,
     so the first frame already has the user's layout.
   - **Narrow windows:** less chrome below 320 dp, and still usable at 220 dp.
   - **Mouse and keyboard:** hover, right-click for the long-press menu, Enter and Shift+Enter, Esc,
     Ctrl+F, and Tab between the sides.
   - **Moving between screens** still recreates the activity, as today (no `configChanges` shortcuts,
     CLAUDE.md). State lives in ViewModels and saved state, and the map's camera in its ViewModel.
     `movableContentOf` covers changes within one window; recreation covers moving between screens.
   - **Built (2026-10-04):** the shell, the divider, Settings across the window, the layout menu in the
     chat bars and among the map's controls, Settings › Appearance › Wide screens, and sheets kept to
     their pane (`withinPane`, `paneSheetMaxWidth`). Checked on a Galaxy Z Fold flat, in book posture and
     tabletop, and on an emulator at 701, 841 and 1400 dp.
     - **Each pane is one node for the whole split,** placed by padding inside the window. The chat
       growing to the whole window, for an invite scan or for typing in tabletop, keeps the same node, so
       the composer keeps its focus and keyboard. Settings is a layer over the panes, so closing it finds
       the conversation and the map as they were.
     - **Insets per edge:** each side clears only the system bars on its outer edges. In tabletop, the map
       ignores the navigation bar and the conversation ignores the status bar.
     - **Found and fixed on the way:** `MapLibreView` was keyed on the window's size. The key never
       rebuilt the view, but it destroyed it while it was still on screen, and MapLibre crashed in
       `onSurfaceChanged` on the next resize. The view now lives as long as its place in the composition
       and resizes in place.
     - **Then:** the map's camera is kept in `MapViewModel` and a rebuilt map starts there, so unfolding
       or rotating keeps the view. Chips stay on one line and the chat filters scroll at 220 dp. Enter
       sends from a hardware keyboard, Ctrl+F opens a room's search wherever focus is, Esc goes back
       (never out of the app), and a right-click does what a long press does.
3. **iOS shell.**
   - **A universal app:** iPhone and iPad (U-14). Build with the iOS 27.1 SDK so the iPhone Duo gets
     edge-to-edge drawing and toolbars at the side. The scene lifecycle iOS 27 requires is already in use.
   - **`AdaptiveShell`** reads the window's size and size classes and asks the ported `PaneLayout`.
     - One pane: today's `TabView`.
     - Two or three panes: the chat side and the map side with a draggable divider, built from
       `DragGesture` and `GeometryReader`, since iOS has no ready-made split for this.
     - On iOS 27.1, `ArrangementView` is used where it fits the pose, and `ReservedRegion` and
       `onHingeChange` keep controls off the fold and the camera. iOS 17 to 27.0 fall back to the
       custom split.
   - **State lives in models, not views.** Switching between the tab view and the split rebuilds
     SwiftUI views. So drafts, scroll anchors, the map's camera and the open conversation live in
     observable models, and one `MLNMapView`, owned by the map's model, is reused.
   - **Storage and window size:** `@SceneStorage` holds the window's arrangement and `@AppStorage` the
     defaults. `UISceneSizeRestrictions.minimumSize` is 320 × 360 pt, and `isInteractivelyResizing`
     holds the map's redraw during a live resize.
   - **With two panes,** Settings opens in a sheet and the map side ignores the keyboard
     (`.ignoresSafeArea(.keyboard)`). There is one scene (`UIApplicationSupportsMultipleScenes` off).
   - **Built (2026-10-04):**
     - A universal app (device family 1,2, every iPad orientation).
     - `AppShell` measures the window and asks the ported `PaneLayouts`: the tab view in one pane, otherwise
       `SidePanes` with a divider made from `DragGesture`. The divider settles by the shared rule, is
       adjustable for VoiceOver with the four actions, and gives a light haptic.
     - Swapping sides flips the row's layout direction rather than reordering it, so neither side is rebuilt.
     - `Workspace` holds the chat and map models, the chats stack and the map's camera, so changing layout
       keeps them. The map's model lost a guard that stopped it observing after its screen was rebuilt;
       tabs had the same latent bug.
     - From 1200 points the chat side keeps its list, 320 wide, beside the conversation stack. The stack keeps
       its place in the view tree between two and three panes, and opening from the list replaces what is open,
       so what shows is always what Send goes to.
     - The map's open sheets, the list's filter and the last room searched live in `Workspace`, so a change of
       layout puts them back.
     - A rebuilt screen appears before the old one goes, so per-screen bookkeeping is keyed by screen:
       the invite QR's capture cover (`SecureWindow`) and the map's on-screen count both are.
     - The side used last is noted on touch-down by a window recognizer that never takes the touch, so panning
       the map counts and hovering does not.
     - Settings › Appearance › Wide screens; layout choices in `@AppStorage`.
     - Checked on iPad Pro 13″ and iPhone simulators.
     - **Not built:** the iOS 27.1 APIs (`ArrangementView`, `ReservedRegion`, `onHingeChange`), because
       Xcode 27.0 ships the 27.0 SDK. Until then iOS sees no fold, and the custom split is all it uses.
4. **The two sides together (both apps).**
   - **Map:** a filter that follows the open conversation, and a `focus` request (member, pin or point)
     the chat side can make. Both live in shared workspace state, not in either screen.
   - **Chat side:** Open map on location cards, pin messages and the member sheet; Show on map on a
     sender; the Sharing chip in the header.
   - **Map side:** Message opens the direct chat on the chat side; pins and sharing default to the open
     room; Show in chat on a pin.
   - **On a phone,** the same actions switch to the Map tab with the focus applied.
   - **Built on Android and iOS (2026-10-04), the same rules in both (`MapSelectionTest`, `MapSelectionTests`):**
     - The map follows the open Firepit room or direct chat while both sides are on screen. Choosing a
       filter stops following, and opening another conversation starts it again.
     - Message on the person card, Open the room on a pin, and pins and sharing defaulting to the open room.
     - The Sharing chip under the conversation's header.
     - The selection rules are a pure `MapSelection`, tested in `MapSelectionTest`.
     - The shell passes all of it as parameters; no shared singleton holds it.
     - **Not built:** the chat-to-map actions, which need location cards, pin messages, a member sheet
       and a sender menu that do not exist yet (UX §6.11.6).
5. **Continuity and resilience (both).** Every row of UX §6.11.5 becomes a test: fold and unfold in the
   middle of typing, scrolling, with each sheet open and during a QR scan; rotation; resizing across
   each threshold; and the app being closed by the system in the background.
6. **Accessibility and right-to-left (both).** Labelled regions, the divider's actions, focus order across
   the sides, large text falling back to one pane, mirroring, and Reduce Motion.
7. **Design and docs.** Figma frames and Android renders at the sizes in UX §12 item 6; UX §6.11
   corrected to what was built; architecture's Large screens row; the README.

**Decisions:** U-8 to U-14 (UX §11.4), locked by the owner on 2026-10-04 as proposed.

### Tests

- **`PaneLayout`:** one table on both apps (JVM and `swift test`), with the 2026 devices' estimated sizes
  and every boundary.
- **Android:**
  - `DeviceConfigurationOverride.ForcedSize` for sizes.
  - `window-testing` (`TestWindowLayoutInfo` and the test `FoldingFeature`) for book and tabletop.
  - The Espresso Device API for folding and unfolding.
  - Screenshots at 220, 320, 412, 600, 752, 840 and 1200 dp wide, and in each posture.
- **iOS:**
  - Xcode Device Hub's iPhone Duo simulator (open, close, fold, rotate) and its resize mode.
  - iPad windows.
  - UI and screenshot tests at the same sizes.
- **Performance:** dragging the divider and resizing a desktop window hold 60 fps, with the map pane at
  its old size until release, on both MapLibre renderers (Vulkan and OpenGL). No freeze in split screen.
- **Real devices:** Galaxy Z Fold8 Ultra, Galaxy Z Fold8, Galaxy Z Flip8 (half-folded and the cover
  screen), Galaxy Z TriFold and Pixel 11 Pro Fold, through Samsung Remote Test Lab and Android Device
  Streaming where none is on hand. The iPhone Duo from 23 October 2026, and an iPad.

### Exit proofs

- **Unfolding mid-conversation (Fold8 Ultra):** the conversation is on the chat side with the map beside
  it, following that room. The draft and scroll position are intact, the keyboard is up if it was, and
  folding again brings the conversation back.
- **Book posture:** no control, text or marker inside the hinge's bounds, checked on screenshots against
  the fold the device reports.
- **Tabletop on a Flip8:** map above, conversation below. Typing takes the whole screen, and the split
  comes back when the keyboard closes.
- **The divider** settles at ⅓, ½, ⅔ and on the fold, and the map redraws once per drag, not once per
  frame.
- **Half and a third of a split screen:** the phone app with nothing cut off, at 220 dp too.
- **iPhone Duo simulator:** the outer screen is the phone app, and the inner screen shows chat and map
  50/50 on the fold. Folding mid-conversation keeps the draft.
- **Both apps** make the same `PaneLayout` decisions, case for case.

### Risks

- **MapLibre resizing.** Its `SurfaceView` blocks on every size change, which is answered by resizing at
  the end of a drag. Two open issues to watch: #4622 (a crash when the surface detaches) and #4700
  (overlays lagging while panning).
- **Nested adaptive scaffolds** measuring the window instead of their own pane.
- **iOS 27.1's new APIs are in beta.** Everything that uses them sits behind availability checks, with
  the custom split as the fallback.
- **SwiftUI view identity.** Changing container rebuilds views, so state must already live in models.
- **Samsung does not publish screen densities.** The dp sizes in UX §6.11.2 are estimates until measured
  on a device.
- **Keyboards on foldables** may span both panes, cover one, or float. The chat side must cope with all
  three.

---

## Test matrix (from Stage 3 onward)

| Device | Why |
|---|---|
| Pixel-class phone | baseline |
| Resizable emulator — Foldable 7.6″ | book posture, hinge-aligned panes |
| Resizable emulator — Flip cover / 320 dp | the width floor |
| Tablet 10–13″ | 3-pane, nav rail |
| Any device, fold/unfold mid-action | state survival (camera + map) |
| Galaxy Z Fold8 Ultra and Pixel 11 Pro Fold (Remote Test Lab / Device Streaming) | two panes on the fold, book posture (Stage 12) |
| Galaxy Z Fold8, the wide model | two panes with the inner screen held landscape (Stage 12) |
| Galaxy Z Flip8 | tabletop: map above, chat below; the cover screen (Stage 12) |
| Galaxy Z TriFold | two and three panes, DeX on the device (Stage 12) |
| iPhone Duo (Device Hub simulator; device from 23 Oct 2026) | outer: phone app; inner: chat and map 50/50 (Stage 12) |
| iPad, full screen and in windows | two and three panes, live resizing (Stage 12) |

Espresso Device API (`setDisplaySize`, `setScreenOrientation`) for automated fold simulation;
`@PreviewScreenSizes` for review-time checks.

---

## Open decisions

| # | Decision | Status |
|---|---|---|
| 1 | **Licensing.** The vendored Meshtastic protobufs are GPL-3.0, so generated-and-linked code makes Firepit a derivative work. Every official Meshtastic client is GPL-3.0. Path of least resistance: license Firepit GPL-3.0. | **Needs owner decision before public release** |
| 2 | Invite-link domain registration | **Dropped.** Invites are QR-only; a link is forwardable and a QR has to be pointed at a camera |
| 3 | Arabic copy translation (layouts are RTL-ready regardless) | Ships in v1 |
| 4 | **Map engine.** MapLibre only. Google Maps and MapKit cannot pre-cache arbitrary regions, so they show a grey grid off-grid — the one occasion the app matters. Google Maps also needs Play Services and an API key, and a second SDK would double the marker, camera and fold/unfold handling. MapLibre has an iOS SDK, so one implementation serves both platforms. | **Decided** |
| 5 | **Tile hosting.** OpenFreeMap is donation-funded. Offline downloads must be capped by area and zoom; self-hosting or a bundled base map is the responsible move if usage grows. | Revisit before public release |
