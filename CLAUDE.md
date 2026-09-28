# Firepit — instructions for coding agents

Firepit is a group chat and live map for small groups with no phone signal,
running on Meshtastic LoRa radios. Android first, iOS second, one protocol.

## Source-of-truth hierarchy

When sources disagree, resolve in this order. Never invent an answer.

1. **Protobuf definitions** in `protos/` (pinned, see `protos/UPSTREAM.md`) — the wire contract.
2. **Firmware source** at the pinned tag — actual behaviour: throttles, defaults, reboots.
3. **Reference clients** — Meshtastic-Android, Meshtastic-Apple, meshtastic/python.
4. **meshtastic.org docs** — concepts only; several pages are stale.
5. **`docs/meshchat-implementation-guide.md`** — verified findings with citations.
6. **`docs/meshchat-app-design.md`**, **`meshchat-ux-design.md`**, **`meshchat-v1-scope.md`** — product intent.

## Working rules

- **Pin versions.** Generate protobuf code from the pinned tag. Never hand-write message structs.
- **Never invent wire formats.** Stock portnums only, with one locked exception: private port `300`
  for `MeshChatControl`, one packet per event, never periodic.
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
  never "delivered". No read receipts, no online status — the radio cannot prove either.

## Layout

| Path | What |
|---|---|
| `android/` | Gradle root. Convention plugins in `build-logic/`. |
| `android/core/protocol` | Pure Kotlin. Wire-generated protos, packet builders, ACK state machine, slot manager. |
| `android/core/designsystem` | Ember tokens, typography, components. |
| `ios/` | Empty until Android v0.1 ships. |
| `protos/` | Vendored Meshtastic protos + `meshchat/meshchat.proto` + app-wide primary key. |
| `docs/` | Design docs and `build-plan.md`. |
| `design/` | Figma exports (outside the repo for now). |

Modules are created when their stage needs them, not pre-created empty.
See `docs/build-plan.md` for the stage list and the planned module set.

## Toolchain (verified 2026-09-28)

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

## Build

```bash
cd android
./gradlew build                      # compiles everything, runs JVM tests + lint
./gradlew :core:protocol:test        # protobuf contract canary
./gradlew :app:assembleDebug
```

## Screen support

Phone portrait and landscape, foldable unfolded (list-detail, hinge-aware), foldable folded outer
display (**320 dp width floor**), and tablet. No orientation locks, no `configChanges` shortcuts;
state survives fold/unfold. Every screen is built adaptive from the start — see `docs/build-plan.md`
Stage 3.

## Licensing

The vendored Meshtastic protobufs are **GPL-3.0**. Generating and linking code from them makes the
app a derivative work. Every official Meshtastic client is GPL-3.0. **Unresolved decision** — see
`docs/build-plan.md`.
