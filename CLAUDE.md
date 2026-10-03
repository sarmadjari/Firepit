# Firepit — instructions for coding agents

Firepit is a group chat and live map for small groups with no phone signal, running on Meshtastic LoRa radios.
Two native apps, one protocol, one repository: Android (Kotlin + Jetpack Compose) and iOS (Swift 6 + SwiftUI).
The protocol's internal name, on the radio and in the protos, is **MeshChat**.

New to the code? `docs/architecture.md` explains the whole system in plain language, and `docs/README.md` lists
every document with what it answers.

## Source-of-truth hierarchy

When sources disagree, resolve in this order. Never invent an answer.

1. **Protobuf definitions** in `protos/` (pinned, see `protos/UPSTREAM.md`) — the wire contract.
2. **Firmware source** at the pinned tag — actual behaviour: throttles, defaults, reboots.
3. **Reference clients** — Meshtastic-Android, Meshtastic-Apple, meshtastic/python.
4. **meshtastic.org docs** — concepts only; several pages are stale.
5. **`docs/meshchat-implementation-guide.md`** — verified findings with citations.
6. **`docs/meshchat-app-design.md`**, **`meshchat-ux-design.md`**, **`meshchat-v1-scope.md`** — product intent.

`docs/security.md` states the security rules both apps implement, each with the code and test that hold it. A change
that touches what is sealed, trusted or stored must keep it true.

Between the two apps, **Android is the reference implementation**. The iOS app is a one-to-one port of it, and where
they differ, Android's code decides behaviour and wire format.

## Working rules

- **Pin versions.** Generate protobuf code from the pinned tag. Never hand-write message structs.
- **Never invent wire formats.** Stock portnums only, with one locked exception: `PRIVATE_APP` (256)
  for `MeshChatControl`, one packet per event. The one periodic message is a sealed position, sent
  at the beacon rate only while the user shares; the radio's own position broadcast stays off.
- **Seal everything a person says or shares.** Words, positions, pins, names and receipts travel
  under the room key or phone to phone. The radio and its channel key must never be what protects
  them: anyone holding a radio can read both.
- **Seal and open room content only through `RoomKeyStore`** (`seal`, `open`). Room keys move on
  every hour, one way, each sender has a key of their own, and each message opens once
  (security.md §3); a stored key used directly would skip all three. No sealed message grows by a byte.
- **Verify before relying on a default.** Defaults changed between firmware 2.7 and 2.8. Set what you need explicitly.
- **Gate on capability fields, not version strings** (`DeviceMetadata.has_xeddsa`, `MeshPacket.xeddsa_signed`).
- **Prefer no-reboot operations.** Channel edits do not reboot; most `set_config` writes do.
- **Everything is rate-limited.** Text 1/2s, position/waypoint/alert/telemetry 1/10s per portnum,
  traceroute 1/30s. Rate-limited packets are dropped *silently while still returning a normal
  `QueueStatus`* — the app must own the spacing. Build queues, not retry loops.
- **Always set `hop_limit` explicitly.** A phone-built packet with `hop_limit = 0` and no `want_ack`
  is transmitted but never rebroadcast.
- **Treat everything from the mesh as untrusted input.** Names, text, waypoints and positions are
  attacker-controlled bytes.
- **Never claim delivery you cannot prove.** Room messages get "heard by the mesh" (implicit ACK),
  never "delivered" by the radio. A direct message is delivered only on the recipient's own ACK.
  Receipts count only when they arrive sealed from the recipient's phone. No online status.
- **No analytics, and no network calls except map tiles.**
- **Colours only via theme tokens**, never literals: `android/core/designsystem` on Android,
  `ios/Firepit/DesignSystem/Theme` (`FirepitColors`, `IdentityColors`) on iOS. Text and icons on primary surfaces
  use `onPrimary`.
- **One primary-channel key.** `protos/meshchat-primary-key.txt` is embedded byte-for-byte by both apps and checked
  by tests on both sides. Rotating it is a breaking protocol change.
- When firmware behaviour matters, cite the file in `refs/` (run `scripts/fetch-refs.sh`) rather than guessing.

## Layout

| Path | What |
|---|---|
| `android/` | Gradle root. Convention plugins in `build-logic/`. Package `com.getfirepit.app`. |
| `android/core/protocol` | Pure Kotlin. Wire-generated protos, packet builders, ACK state machine, slot manager. |
| `android/core/designsystem` | Ember tokens, typography, components. |
| `ios/` | Xcode project `Firepit.xcodeproj`, bundle id `com.getfirepit.app`, iOS 17+, iPhone. |
| `ios/Packages/FirepitKit` | Non-UI layers, one target per Android `core/` module: `FirepitProtos` (generated), `FirepitModel`, `FirepitProtocol`, `FirepitCrypto`, `FirepitTransport`, `FirepitData`. `Protos/` is a vendored copy of `protos/`. |
| `ios/Firepit` | App target: `DesignSystem/` (port of `core/designsystem`) and `Features/` (port of `app/`). |
| `protos/` | Vendored Meshtastic protos + `meshchat/meshchat.proto` + app-wide primary key. |
| `docs/` | Start with `architecture.md`; `README.md` there indexes the rest: design docs, `build-plan.md`, `security.md`, `ios-ui-porting-rules.md`, the WisMesh Tag guide. |
| `design/` | Brand (the icon both apps use), Figma exports, reference renders. See `design/README.md`. No build reads it. |
| `website/` | The public site, firepit.sarmad.no: static HTML/CSS, no third-party requests, deployed by `.github/workflows/pages.yml`. Screenshots come from `scripts/capture-site-screens.sh`, the demo invite codes from `scripts/make-site-qr.py`. |
| `scripts/` | Cross-platform checks and iOS tooling. `verify-all.sh` runs the full gate. |
| `archive/` | Not maintained and not built: the Android design prototype, the MeshChat-era design, the 2026-09-30 reorganisation record. |
| `refs/` | Git-ignored: reference clones (`scripts/fetch-refs.sh`) and tool builds. |

Modules are created when their stage needs them, not pre-created empty.
See `docs/build-plan.md` for the stage list and the planned module set.

## Android

### Toolchain (verified 2026-09-28)

- AGP **9.4.1**. AGP applies Kotlin itself: **do not** add `org.jetbrains.kotlin.android`.
- Kotlin **2.4.20**, declared in the version catalog. AGP 9.4 *defaults* to Kotlin 2.2.10, but the
  catalog version overrides it — verified by compiling a library whose metadata 2.2.10 cannot read.
- **Library metadata rule:** a Kotlin compiler reads metadata up to one minor above itself
  (2.4.x reads 2.5.0). When a dependency fails with "compiled with an incompatible version of
  Kotlin", raise the catalog's `kotlin` before downgrading the library.
- **KSP can still be the ceiling.** Hilt and Room both need it. KSP's version no longer tracks
  Kotlin's: KSP 2.3.12 builds this project on Kotlin 2.4.20. Check that a KSP release supports
  the new Kotlin before any Kotlin bump.
- AGP 9 DSL changes: `CommonExtension` has **no type parameters**; `defaultConfig` and
  `compileOptions` are getters only at that level (use property access, not lambdas).
- Gradle **9.8**, configuration cache **on**. Daemon JDK 25, modules compile to Java 17. AGP 9.4.1
  still calls `Configuration.setVisible`, which Gradle 9.8 deprecates; that one notice is AGP's.
- Wire **7.1.0**. `nanopb.proto` is on the `protoPath`, not the `sourcePath` — it extends
  `google.protobuf` descriptors Wire does not generate. Its generated decoders trip Kotlin 2.4's
  `UNNECESSARY_NOT_NULL_ASSERTION`, which `core/protocol` suppresses for that reason alone.
- **Lint and the compiler run clean: keep them at zero warnings.** Fix a new warning rather than
  suppress it; the few suppressions that exist each say why.
- minSdk **29**, so `java.time` is available natively; no core library desugaring.

### Build

```bash
cd android
./gradlew build                      # compiles everything, runs JVM tests + lint
./gradlew :core:protocol:test        # protobuf contract canary
./gradlew :app:assembleDebug
```

### Screen support

Phone portrait and landscape, foldable unfolded (list-detail, hinge-aware), foldable folded outer
display (**320 dp width floor**), and tablet. No orientation locks, no `configChanges` shortcuts;
state survives fold/unfold. Every screen is built adaptive from the start — see `docs/build-plan.md`
Stage 3.

## iOS

- **A one-to-one port of the Android app.** Keep Kotlin names, members, parameters and SQL identical:
  `scripts/check-port-parity.py` and `scripts/check-dao-parity.py` check it, and
  `scripts/check-android-interop.sh` proves both apps open each other's seals, keys, tokens and invites.
  Screens follow `docs/ios-ui-porting-rules.md`.
- **Pinned exactly:** SwiftProtobuf **1.38.1** and GRDB.swift **7.11.1** in `ios/Packages/FirepitKit/Package.swift`
  (the generated code must match SwiftProtobuf), MapLibre Native **6.31.0** in the Xcode project. Xcode 27 / Swift 6.4.
- **Protos:** after any change under `protos/`, run `scripts/sync-ios-protos.sh` (copies into
  `FirepitKit/Protos` and regenerates `FirepitProtos`). CI fails if the two trees differ.
- The app target defaults to `@MainActor`; mark pure value types and `Shape`s `nonisolated`.
- **Shared glyphs:** room icons, the Personal/Base/Router glyphs and the map pin are Android's drawings on both apps,
  because people compare them across phones. After changing one of those drawables run `scripts/sync-ios-glyphs.py`;
  never swap in an SF Symbol. Interface chrome (search, close, share…) stays SF Symbols.
- Keep builds warning-free. Check UI changes in light, dark, an accessibility text size and right-to-left, using the
  debug-only `-demo` world and `-route <feature>.<screen>` launch arguments (README).

```bash
swift test --package-path ios/Packages/FirepitKit     # non-UI layers, on the Mac
xcodebuild test -project ios/Firepit.xcodeproj -scheme Firepit \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro'   # app build + every test bundle
```

## Workflow

- Small, verified steps: build and test after each change, then device test with two phones and two nodes
  (scope doc §4.8). `scripts/verify-all.sh` runs the full gate for both apps before a push: both builds and
  test suites, the protos copy, crypto interop in both directions and DAO parity.
- Keep docs in sync: a behaviour change updates the relevant doc section in the same commit.
- Commits: Conventional Commits with a scope, subject in plain English — `feat(android): …`, `fix(ios): …`,
  `docs: …`. Scopes: `android`, `ios`, `protos`, `design`, `docs`, `website`, `scripts`, `ci`, `repo`.

## Licensing

The vendored Meshtastic protobufs are **GPL-3.0**. Generating and linking code from them makes the
app a derivative work. Every official Meshtastic client is GPL-3.0. **Unresolved decision** — see
`docs/build-plan.md`.
