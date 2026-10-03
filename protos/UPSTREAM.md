# Vendored protobuf definitions

## `meshtastic/`, `nanopb.proto` — upstream, do not edit

| | |
|---|---|
| Source | https://github.com/meshtastic/protobufs |
| Tag | **v2.8.0** |
| Commit | `7b2464c9b8c1521f93852261e4123826e5b25e11` |
| Vendored | 2026-09-09 |
| License | **GPL-3.0** (`meshtastic/LICENSE`) |

v2.8.0 is a superset of the 2.7 runtime target: fields a 2.7 node never sends
decode as defaults. Runtime support is firmware 2.7+, with 2.8 features gated on
capability fields (`DeviceMetadata.has_xeddsa`, `MeshPacket.xeddsa_signed`),
never on version strings.

Verified present at this pin (the constants the app depends on):

| Fact | Location |
|---|---|
| `DATA_PAYLOAD_LEN = 233` | `mesh.proto` `Constants` |
| `Data.payload max_size:233` | `mesh.options` |
| `Data.xeddsa_signature = 10`, max 64 B | `mesh.proto`, `mesh.options` |
| `MeshPacket.xeddsa_signed = 22` | `mesh.proto` |
| `NodeInfo.has_xeddsa_signed = 14` | `mesh.proto` |
| `DeviceMetadata.has_xeddsa = 14` | `mesh.proto` |
| `PRIVATE_APP = 256`, `MAX = 511` | `portnums.proto` — `MeshChatControl` travels on `PRIVATE_APP` |

`ProtocolContractTest` in `:core:protocol` (Android) and `ProtocolContractTests` in
`FirepitProtocolTests` (iOS) assert these, and fail the build if the pin moves
underneath us.

**To re-pin:** bump the tag, re-copy, re-run the tests, and re-verify every
firmware behaviour cited in `docs/meshchat-implementation-guide.md` §2.2.

### `.options` files

nanopb field-size limits used by the firmware. Wire and SwiftProtobuf ignore them
(they are not `.proto`), but they are the authority for the app's own input
validation — channel name ≤ 11 bytes, `long_name` ≤ 23 bytes, `short_name` ≤ 4
bytes, payload ≤ 233 bytes.

## `meshchat/meshchat.proto` — ours

App-level definitions only: the invite payload (shown as a QR code, never
transmitted) and `MeshChatControl` on `PRIVATE_APP` (256), one packet per event;
the one periodic message is a sealed position, sent only while someone shares.
See `docs/meshchat-implementation-guide.md` §6.8.2 and §6.8.5.

## `meshchat-primary-key.txt` — the app-wide primary channel key

32 random bytes, base64, single line, generated once on 2026-09-09.

**This is not a secret.** It ships inside every copy of the app, so anyone can
extract it. Its only job is to keep channel slot 0 — which carries names and
battery telemetry between Firepit nodes and sets the frequency slot — off the
stock public key, so Firepit nodes recognise each other and stranger nodes stay
out of the NodeDB. The real secrets are each room's keys, which are generated on
the phone and never travel inside an invite: a new member receives them sealed
to their own phone's key (`docs/security.md` §3, §6).

Both the Android and iOS apps must embed this byte-for-byte. Rotating it is a
breaking protocol change and requires a version bump, because nodes on the old
key and the new key cannot see each other.
