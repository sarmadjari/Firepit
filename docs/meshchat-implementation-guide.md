# Firepit (MeshChat) — Technical Implementation Guide for AI Coding Agents

**Companion to:** `meshchat-app-design.md` (rev 2, protocol-aligned) · product name **Firepit**; "MeshChat" in this guide is the internal protocol name (channel, port, protobuf package)
**Audience:** an AI coding agent implementing MeshChat on Android (Kotlin/Compose) and iOS (Swift/SwiftUI)
**Firmware compatibility:** Meshtastic **2.7.x baseline** (validated against tag `v2.7.26.54e0d8d`), **2.8.x supported** with capability gating (validated against firmware `master` @ `73c4110`, version 2.8.1-dev, 2026-09-09)
**Status:** implementation guide, rev 1

---

## 0. How to use this guide

### 0.1 Source-of-truth hierarchy

When this guide, the design doc, and upstream disagree, resolve in this order:

1. **Protobuf definitions** at the pinned tag (`meshtastic/protobufs`). They are the wire contract.
2. **Firmware source** at the pinned tag (`meshtastic/firmware`). It defines actual behaviour (throttles, defaults, reboots, what gets signed).
3. **Reference clients** (`Meshtastic-Android`, `Meshtastic-Apple`, `meshtastic/python`). They show how the protocol is used in practice.
4. **meshtastic.org docs.** Useful for concepts; several pages are stale (e.g. position-broadcast default is documented as 15 min but the code says 1 h).
5. **This guide.** Every non-obvious claim below cites where it was verified. If you change firmware pin, re-verify the cited spots.
6. `meshchat-app-design.md` for product intent. Where the design assumes something the firmware does not do, this guide says so explicitly (see §2.3 and §9).

### 0.2 Working rules for the agent

- **Pin versions.** Generate protobuf code from a pinned protobufs tag. Do not hand-write message structs.
- **Never invent wire formats.** Everything on the air uses stock portnums, with one locked exception (D-2): MeshChat control events (join hello, roster events, receipts, sealed room messages, sealed pins, sealed direct messages, location requests) ride `PortNum.PRIVATE_APP` (256) as a single `MeshChatControl` protobuf whose `oneof` says which — one packet per event. The one periodic message is a sealed position (D-2, amended 2026-09-29), which replaces the firmware's own position broadcast rather than adding to it. `portnums.proto` sanctions this directly: *"To simplify initial development and testing you can use PRIVATE_APP in your code without needing to rebuild protobuf files."* Claiming 300 would mean editing the vendored protos, and the generated `PortNum` enum cannot express a number they do not declare. App-level formats (QR, links, local DB) are yours to define; they are specified in §6.8.
- **Verify before relying on a default.** Firmware defaults changed between 2.7 and 2.8 (position precision, telemetry, node numbers). MeshChat must set what it needs explicitly.
- **Prefer no-reboot operations.** Channel edits do not reboot the node; most `set_config` writes do. Design flows around that (§6.3, §6.7).
- **Everything the node does for you is rate-limited.** Positions, NodeInfo, and telemetry replies are throttled by the firmware; the phone API also rate-limits outgoing text (2 s) and position/waypoint/alert/telemetry (10 s per portnum). Build queues, not retries-in-a-loop.
- **Treat everything from the mesh as untrusted input.** Names, text, waypoint fields, and positions are attacker-controlled bytes.

### 0.3 Terminology

| Term | Meaning |
|---|---|
| node / radio | The Meshtastic LoRa device (T-Echo, WisMesh Tag, …) |
| phone / app / client | MeshChat on Android/iOS, talking to a node over the PhoneAPI |
| nodenum | `uint32` node number (`MeshPacket.from/to`). Display form is `!%08x` (`User.id`) |
| room | A Meshtastic **secondary channel** (name + PSK) in slots 1–7 |
| primary | Channel slot 0 (`Channel.Role.PRIMARY`); sets the frequency slot; carries NodeInfo and telemetry broadcasts |
| PhoneAPI | The `ToRadio` / `FromRadio` protobuf stream over BLE, TCP, HTTP, or serial |
| PKI / PKC | Public-key encryption for direct messages (X25519 + AES-CCM) |

---

## 1. Source map — where to look

### 1.1 Repositories and pins

```bash
# Protobufs (wire contract)
git clone https://github.com/meshtastic/protobufs.git && git -C protobufs checkout v2.8.0    # code-generation source (superset; runtime target 2.7+)
# behaviour baseline for validation: firmware tag v2.7.26.54e0d8d (below); 2.8-only fields decode as defaults on 2.7 nodes

# Firmware (behaviour)
git clone --depth 1 --branch v2.7.26.54e0d8d https://github.com/meshtastic/firmware.git firmware-2.7
git clone --depth 1 https://github.com/meshtastic/firmware.git firmware-master   # 2.8.x

# Reference clients
git clone --depth 1 https://github.com/meshtastic/Meshtastic-Android.git
git clone --depth 1 https://github.com/meshtastic/Meshtastic-Apple.git
git clone --depth 1 https://github.com/meshtastic/python.git
```

Commits this guide was verified against: protobufs `ca2cb1a` (2026-09-08) and tag `v2.7.26`; firmware `73c4110` (master, 2.8.1) and tag `v2.7.26.54e0d8d`; Android `1fe8083`; Apple `14a4796`; python `0539a96`.

### 1.2 Topic → file index

| Topic | Primary source | Secondary |
|---|---|---|
| Packet, Data, Position, User, NodeInfo, Routing, Waypoint, FromRadio/ToRadio, priorities, error codes | `protobufs/meshtastic/mesh.proto`, `mesh.options` | docs: `/docs/development/reference/protobufs` |
| Channels (PSK semantics, precision, roles) | `protobufs/meshtastic/channel.proto`, `channel.options` | firmware `src/mesh/Channels.cpp` |
| Device role, position config, LoRa/region, Bluetooth, security | `protobufs/meshtastic/config.proto` | docs `/docs/configuration/radio/*` |
| Telemetry module config, traffic management (2.8) | `protobufs/meshtastic/module_config.proto` | firmware `src/modules/Telemetry/DeviceTelemetry.cpp` |
| Admin messages, session passkey, SharedContact | `protobufs/meshtastic/admin.proto` | firmware `src/modules/AdminModule.cpp` |
| Port numbers | `protobufs/meshtastic/portnums.proto` | — |
| Channel URL (`ChannelSet`) | `protobufs/meshtastic/apponly.proto` | python `meshtastic/node.py` (`getURL`, `setURL`) |
| PhoneAPI handshake and state machine | firmware `src/mesh/PhoneAPI.cpp/.h` | Android `core/ble/.../KableMeshtasticRadioProfile.kt` |
| BLE UUIDs | firmware `src/BluetoothCommon.h` | Android `core/ble/.../MeshtasticBleConstants.kt`, Apple `Accessory/Transports/Bluetooth Low Energy/BLEConnection.swift` |
| nRF52 BLE pairing / PIN | firmware `src/platform/nrf52/NRF52Bluetooth.cpp` | docs `/docs/configuration/radio/bluetooth` |
| Phone → mesh path, rate limits, phone-queue behaviour | firmware `src/mesh/MeshService.cpp`, `PhoneAPI.cpp` (`handleToRadioPacket`) | — |
| Encryption, PKI decision, signing (2.8) | firmware `src/mesh/Router.cpp` (`perhapsEncode`, `perhapsDecode`, `checkXeddsaReceivePolicy`), `src/mesh/CryptoEngine.*` | docs `/docs/overview/encryption` |
| ACK / implicit ACK / retransmit | firmware `src/mesh/ReliableRouter.cpp`, `NextHopRouter.h` (constants) | docs `/docs/overview/mesh-algo` |
| Rebroadcast rules by role, favorites | firmware `src/mesh/FloodingRouter.cpp`, `Router.cpp` (hop-limit preservation) | docs `/docs/configuration/radio/device` |
| Position broadcast channel choice, precision | firmware `src/mesh/PositionPrecision.cpp`, `src/modules/PositionModule.cpp`, `src/mesh/MeshService.cpp` (`trySendPosition`) | Android `core/model/.../PositionPrecision.kt` |
| NodeInfo exchange throttles | firmware `src/modules/NodeInfoModule.cpp`, `MeshService.cpp` (`handleFromRadio`) | — |
| Telemetry broadcast | firmware `src/modules/Telemetry/DeviceTelemetry.cpp` | — |
| Waypoints | firmware `src/modules/WaypointModule.cpp` | python `sendWaypoint` |
| Airtime gates, duty cycle | firmware `src/airtime.cpp/.h`, `Router.cpp` (`send`) | — |
| Default intervals, hop limit | firmware `src/mesh/Default.h/.cpp` | — |
| NodeDB capacity, favorites, contacts | firmware `src/mesh/mesh-pb-constants.h`, `src/mesh/NodeDB.cpp` | — |
| Constants (header length, PKC overhead) | firmware `src/mesh/RadioInterface.h`, `src/mesh/MeshTypes.h` | — |
| Frequency slot derivation | firmware `src/mesh/RadioInterface.cpp` (`applyModemConfig`, ~line 1213) | Apple `Helpers/LoRaChannelCalculator.swift` |
| Message status semantics in a client | Android `core/data/.../manager/MeshDataHandlerImpl.kt` (`handleAckNak`), `PacketHandlerImpl.kt` | Apple `Helpers/MeshPackets.swift` (`routingPacket`) |
| Admin session handling in a client | Android `core/repository/.../SessionManager.kt`, `core/data/.../AdminPacketHandlerImpl.kt`, `CommandSenderImpl.kt` | python `node.py` (`ensureSessionKey`, `_sendAdmin`) |
| Phone GPS → node | Apple `Accessory/Accessory Manager/AccessoryManager+Position.swift` | Android `core/service/.../QueryControllerImpl.kt`, `core/data/.../LocationRepositoryImpl.kt` |
| Contact URL (`meshtastic.org/v/#`) | python `node.py` (`getContactURL` region), Apple `Helpers/ContactURLHandler.swift`, Android `core/model/.../SharedContact.kt` | — |
| Protobuf tooling | Android: Wire-generated KMP models published as `org.meshtastic:protobufs` (see `gradle/libs.versions.toml`); Apple: `MeshtasticProtobufs` SwiftPM package on `swift-protobuf` ≥ 1.33 | — |

---

## 2. Firmware compatibility (2.7 baseline, 2.8 gated)

### 2.1 Detecting capabilities

After the config handshake (§4.3) you hold `DeviceMetadata` (`FromRadio.metadata`, sent as `STATE_SEND_METADATA`) and `MyNodeInfo`.

```text
DeviceMetadata.firmware_version   string, e.g. "2.7.26.54e0d8d" or "2.8.0.47db0e3" → parse major.minor.patch
DeviceMetadata.hasPKC             bool  (true on 2.5+)
DeviceMetadata.has_xeddsa         bool  (field 14; absent on 2.7 → decodes as false)   ← gate for signing UI
MyNodeInfo.min_app_version        uint32; warn if greater than the protocol version you implement
MyNodeInfo.nodedb_count           uint32 (2.8 only; 0 on 2.7)
```

Gate features on **fields**, not on version strings, wherever a field exists (`has_xeddsa`, `MeshPacket.xeddsa_signed`, `NodeInfo.has_xeddsa_signed`). Use the version string only for behaviour differences that have no field (table below).

### 2.2 Behaviour differences that affect MeshChat

| Area | 2.7.x (tag v2.7.26) | 2.8.x (master) | What MeshChat must do |
|---|---|---|---|
| Primary channel default position precision | `13` bits on channel 0 by default (`Channels.cpp:139`) → node shares ~2.9 km positions on the primary out of the box | `0` (fail closed; `Channels.cpp:163`, `PositionPrecision.cpp`) | Always write `module_settings.position_precision` explicitly for **every** channel you provision, including slot 0 (§6.1, §6.3) |
| Precision cap on publicly decryptable channels | none | clamped to `MAX_POSITION_PRECISION_PUBLIC_KEY = 15` when the channel's effective key is a well-known key (`PositionPrecision.cpp`, `Channels::usesPublicKey`) | Rooms use random 32-byte PSKs → unaffected. Never rely on full precision on a default-key channel |
| Device telemetry to mesh | gated by `ModuleConfig.TelemetryConfig.device_telemetry_enabled` (checked in `DeviceTelemetry.cpp:33`); default not set (false) | same gate; default explicitly `false` (`NodeDB.cpp:1308`) | Set `device_telemetry_enabled = true` during provisioning if members' battery must be visible (§6.4) |
| Node number origin | MAC-derived, random on collision (`NodeDB::pickNewNodeNum`) | CRC32 of the node's public key on first boot (`NodeDB.cpp:531`), persisted | Key by nodenum + public key in the app DB; expect a nodenum change after factory reset on either version |
| Packet signing (XEdDSA) | none | firmware signs **broadcasts it originates** when the signed Data still fits (`Router.cpp` `perhapsEncode`, `signedDataFits`); receiver policy `Config.SecurityConfig.packet_signature_policy` (COMPATIBLE / BALANCED / STRICT) | Show "verified" only when `MeshPacket.xeddsa_signed == true`. Never set STRICT in a mixed 2.7/2.8 group (it drops all unsigned packets, i.e. every 2.7 node) |
| NodeDB capacity (nRF52840) | `MAX_NUM_NODES = 80` | `120` hot entries + a "warm" key store (`WarmNodeStore`) | Favorite room members (`set_favorite_node`) so they are never evicted; mirror everything in the app DB; re-send `add_contact` before DMs (§6.2) |
| NodeDB admission | only decoded packets create entries (`NodeDB::updateFrom`) | same, plus rate-limited admission when full | Private primary keeps strangers out of the NodeDB (§9 D-1) |
| Region / preset model | fixed enum; new nodes default LongFast | `FromRadio.region_presets` (`LoRaRegionPresetMap`) sent during config; new US nodes default **LongTurbo**; presets filtered per region | Invite carries the inviter's LoRa profile; joiner aligns `modem_preset` (§6.1). LongTurbo and LongFast cannot hear each other |
| Hop scaling | none | `HopScalingModule` may lower `hop_limit` of the node's own periodic POSITION/TELEMETRY/NODEINFO broadcasts (never above `lora.hop_limit`) | Nothing; keep `hop_limit = 3` |
| Traffic management | absent (`enabled` etc. fields) | `TrafficManagementConfig` (`position_min_interval_secs` default 5 h between identical positions, rate limits, unknown-packet threshold). Field numbers 1,2,3,5,7,10,12,13,14 are **reserved** in 2.8 | Do not touch. Expect stationary nodes' identical positions to be de-duplicated by 2.8 relays; use `last_heard` for liveness, not only positions |
| `MeshPacket.rx_time`, `rx_rssi` | plain | `optional` | Treat 0/absent as unknown on both |
| New portnums | — | `MESH_BEACON_APP=37`, `PAGING_APP=38`, `LORA_OTA_APP=79` | Ignore; `PAGING_APP` is a candidate for acknowledged alerts in a later version |
| Heartbeat nonce 1 | plain keepalive | `ToRadio.heartbeat{nonce:1}` forces a NodeInfo broadcast (60 s cooldown) | Optional 2.8-only shortcut; §6.8 uses a phone-built NodeInfo instead so it works on both |
| Long name storage | 39 bytes | decode width 40, but firmware **stores/transmits at most 24 bytes** (`mesh.options` comment) | Limit `long_name` to ≤ 23 UTF-8 bytes; `short_name` ≤ 4 bytes |

Protobuf diff 2.7.26 → master (verified): `mesh.proto` adds `Data.xeddsa_signature`, `MeshPacket.xeddsa_signed`, `NodeInfo.has_xeddsa_signed/heard_on_current_lora`, waypoint geofence fields, `LoRaRegionPresetMap`, `DeviceMetadata.has_xeddsa`; `config.proto` adds `PacketSignaturePolicy`, regions 34–37, presets 14–16; `admin.proto` adds `MESHBEACON_CONFIG` and lockdown fields; `channel.proto` is **unchanged**. Everything MeshChat needs exists in 2.7.26 (PKI, `add_contact`/`SharedContact`, `key_verification`, session passkey, `set_fixed_position`, favorites, `CLIENT_BASE`, `ROUTER_LATE`).

### 2.3 Design-document assumptions corrected by source

| Design doc says | Verified reality | Consequence |
|---|---|---|
| "Firmware target 2.8+ … because of XEdDSA signing" | Signing exists only in 2.8; user requirement is 2.7+ | Signing is a gated enhancement, not a baseline. Authenticity on 2.7 comes from PKI on DMs and channel-key possession on broadcasts |
| "Group messages: implicit ACK only" | The firmware only reports an implicit ACK to the phone if the packet had `want_ack` set (a pending retransmission entry must exist, `ReliableRouter::shouldFilterReceived`) | Send group messages with `want_ack = true`; the firmware retransmits up to 3× until it hears a rebroadcast (§6.2) |
| "Max payload 237 bytes (~200 usable)" | `Constants.DATA_PAYLOAD_LEN = 233`; encrypted Data must fit 239 bytes (255 − 16 header); PKI costs 12 more; a 2.8 signature costs 66 more | Text budget: 200 bytes UI cap (matches Apple), ~221 hard for DMs, ~233 hard for unsigned broadcasts, ~165 if you want a 2.8 broadcast to still be signed (§7) |
| "Channels must be consecutive" | Firmware tolerates DISABLED gaps (`Channels::setChannel` just stores); consecutiveness is a client convention (python `deleteChannel` shifts down) and the docs' rule | Keep them consecutive anyway (§6.1); nothing breaks if a foreign client leaves a gap |
| "Position broadcasts go to the lowest-indexed secondary channel with location sharing enabled" | They go to the **first channel (index 0..7) with non-zero precision**, primary included (`PositionModule::sendOurPosition`, `findPositionChannel`) | Keep slot 0 precision at 0 so the room logic holds |
| "Node battery … no extra traffic" | Device telemetry is broadcast on **channel 0 only**, BACKGROUND priority, default every 1 h scaled by mesh size, and only if `device_telemetry_enabled` | Battery visibility requires all members to share the primary key and the flag to be on (§6.4, D-1) |
| "NodeDB ~100" | 80 (2.7, nRF52) / 120 + warm store (2.8) | §2.2 |
| "Admin over BLE: session passkey" | Firmware checks `session_passkey` only when `MeshPacket.from != 0` (remote). Local phone admin passes without it, unless `security.is_managed` | Still obtain and send it (cheap, future-proof) |
| "Role changes restart the node" | True; also LoRa radio params, Bluetooth, Security, Network, some Power fields, and **Position config** (`handleSetConfig` defaults `requiresReboot = true`) reboot. Channel edits, owner, favorites, fixed position do **not** | Design live-location and admin flows around reboots (§6.3, §6.7) |
| "Phone battery … PRIVATE_APP" | Correct; out of scope | — |

---

## 3. Protocol primer (verified facts you will need constantly)

### 3.1 Packet anatomy

```text
LoRa frame ≤ 255 bytes (MAX_LORA_PAYLOAD_LEN)
├─ 16-byte cleartext header (MESHTASTIC_HEADER_LENGTH): to, from, id, flags(hop_limit:3, want_ack, via_mqtt, hop_start:3), channel hash, next_hop, relay_node
└─ ≤ 239 bytes encrypted `Data` protobuf
     Data { portnum=1, payload=2 (≤233 bytes), want_response=3, dest=4, source=5, request_id=6, reply_id=7, emoji=8, bitfield=9, xeddsa_signature=10 (64 bytes, 2.8) }
PKI (DM) adds MESHTASTIC_PKC_OVERHEAD = 12 bytes (8-byte tag + 4-byte extra nonce) inside the 239.
```

Source: `RadioInterface.h` lines 20–28; `mesh.proto`; `mesh.options` (`Data.payload max_size:233`, `Data.xeddsa_signature max_size:64`); `CryptoEngine.h` (`XEDDSA_SIGNATURE_FIELD_BYTES = 66`).

`MeshPacket` fields the app sets on send: `to`, `channel` (**index** 0–7; the firmware converts to the hash: `Router::perhapsEncode`), `id` (generate yourself: non-zero random 32-bit, you need it to correlate ACKs), `hop_limit` (**always set**; see below), `want_ack`, `priority`, `pki_encrypted` (+ optional `public_key`), `decoded`.
Fields the firmware fills on receive: `from`, `rx_time`, `rx_snr`, `rx_rssi`, `hop_start`, `relay_node`, `channel` (index after decode), `pki_encrypted`, `xeddsa_signed` (2.8), `public_key` (sender key, PKI packets).

**hop_limit trap:** `MeshService::handleToRadio` does not default `hop_limit`. `Router::sendLocal` fills it only when `want_ack && hop_limit == 0`. A phone-built packet with `hop_limit = 0` and no `want_ack` is transmitted with hop limit 0 and is never rebroadcast. Always set `hop_limit = config.lora.hop_limit` (0 in config means the default 3; `Default::getConfiguredOrDefaultHopLimit`, cap 7).

**priority:** if left `UNSET`, `MeshPacketQueue::fixPriority` assigns: ROUTING → ACK(120); TEXT_MESSAGE/ADMIN → HIGH(100); has `request_id` → RESPONSE(80); `want_response` or `want_ack` → RELIABLE(70); else DEFAULT(64). Set explicitly only where you want something else (`ALERT` for alerts, `BACKGROUND` for phone-injected positions).

### 3.2 Channels

- 8 slots (`ChannelFile.channels max_count 8`, `MAX_NUM_CHANNELS`). Index 0 must be `PRIMARY`; others `SECONDARY` or `DISABLED`. Setting another channel PRIMARY demotes the old one (`Channels::setChannel`).
- `ChannelSettings.name`: nanopb `max_size:12` → **≤ 11 UTF-8 bytes** (NUL-terminated). Empty name means "use the modem preset name" (e.g. `LongFast`).
- `ChannelSettings.psk`: 0 bytes = no crypto; 1 byte = index into the well-known default key (`0` none, `1` default key, `2..10` default key with last byte + n−1); 16 bytes = AES-128; 32 bytes = AES-256. **A secondary channel with an empty PSK inherits the primary's key** (`Channels::getKey`) — always write an explicit 32-byte PSK for rooms.
- `ChannelSettings.id` (`fixed32`): free for the app. MeshChat uses it as the stable room identifier across renames and re-indexing.
- `ModuleSettings.position_precision`: 0 = never send position on this channel; 32 = full; 10–19 = degraded (§Appendix C). `ModuleSettings.is_muted`: device/UI mute hint only.
- Channel hash byte on the wire = `xorHash(name) ^ xorHash(key)` (8-bit; collisions are possible and harmless — the firmware tries matching channels).
- Frequency slot: `lora.channel_num` if set, else `hash(primary channel display name) % numFreqSlots` (`RadioInterface.cpp` ~1213). Changing the primary channel **name** changes the frequency slot. All nodes in a group must land on the same slot and modem preset.

### 3.3 Encryption model

- Channel traffic: AES-CTR with the channel PSK, nonce from packet id + sender. **No integrity, no sender authentication** on 2.7 (docs: "trivial to impersonate anyone else if you have access to the channel key"). 2.8 adds XEdDSA signatures on broadcasts.
- Direct messages: PKI (X25519 shared secret → AES-CCM). `Router::perhapsEncode` uses PKI when: packet is from us, destination is unicast, `security.private_key` is 32 bytes, portnum is not TRACEROUTE/NODEINFO/ROUTING/POSITION, and the **NodeDB holds a 32-byte public key for the destination** (else `Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY = 39`). Wire `channel` hash is 0 for PKI packets. A channel-encrypted text DM to a non-licensed node is **rejected on receive** ("Rejecting legacy DM", both 2.7 and 2.8) — DMs must be PKI.
- PKI decrypt on receive requires the receiver's NodeDB to hold the **sender's** key (`perhapsDecode`). A first DM from a node whose NodeInfo was never received fails to decrypt. §6.8 orders the join handshake accordingly.
- 2.8 signing: applies to non-PKI broadcasts (and unicasts only in licensed/ham mode). Receiver policy: COMPATIBLE accepts everything; BALANCED (see `checkXeddsaReceivePolicy`) drops unsigned broadcasts from nodes previously seen signing; STRICT drops all unsigned packets. Firmware default comes from `USERPREFS_CONFIG_SECURITY_PACKET_SIGNATURE_POLICY` (build-time); read it back from `Config.security` rather than assuming.

### 3.4 Routing, ACKs, retransmission

- Broadcasts: managed flooding. Every node with `hop_limit > 0` decrements and rebroadcasts once after an SNR-weighted contention window; CLIENT cancels its rebroadcast if it hears someone else do it; ROUTER/ROUTER_LATE/CLIENT_BASE(for favorites) always rebroadcast; ROUTER_LATE waits for everyone else (`clampToLateRebroadcastWindow`).
- Direct messages: next-hop routing (2.6+). Floods until a route is learned, then unicasts via the learned relay, falls back to flooding on the last attempt.
- `want_ack` (any destination): `ReliableRouter::send` copies the packet and schedules retransmissions: `NUM_RELIABLE_RETX = 3` for broadcasts, `NUM_RELIABLE_UNICAST_ATTEMPTS = 5` for unicast (`NextHopRouter.h`). Hearing our own broadcast rebroadcast = **implicit ACK**: the firmware stops retransmitting and delivers a `Routing{error_reason: NONE}` packet **from our own nodenum** to the phone (`ReliableRouter::shouldFilterReceived` → `sendAckNak`). A destination node returns an explicit ACK **from its nodenum**; for text DMs the ACK itself is sent with `want_ack` and, if received directly, as a 0-hop ACK.
- Failures arrive as `Routing{error_reason}` with `Data.request_id = original packet id`: `MAX_RETRANSMIT(5)`, `NO_CHANNEL(6)`, `TOO_LARGE(7)`, `NO_RESPONSE(8)`, `DUTY_CYCLE_LIMIT(9)`, `PKI_FAILED(34)`, `PKI_UNKNOWN_PUBKEY(35)` (receiver could not decrypt a want_ack DM), `ADMIN_BAD_SESSION_KEY(36)`, `RATE_LIMIT_EXCEEDED(38)`, `PKI_SEND_FAIL_PUBLIC_KEY(39)`.
- Duplicate suppression is by `(from, id)`; keep your own dedupe cache in the app, too.

### 3.5 Airtime gates (why "just send more" fails)

| Gate | Threshold | Applies to |
|---|---|---|
| `AirTime::isTxAllowedChannelUtil(polite=true)` | channel utilization < 25 % | periodic position, telemetry, reactive NodeInfo exchange |
| `isTxAllowedChannelUtil(false)` | < 40 % | NodeInfo replies |
| `isTxAllowedAirUtil()` | own TX utilization < half the regional duty cycle (EU_868: 10 % → 5 %) | periodic modules |
| `Router::send` duty-cycle check | hourly TX % > regional duty cycle → `DUTY_CYCLE_LIMIT` NAK to the phone (unless `lora.override_duty_cycle`) | every packet, including chat |
| PhoneAPI rate limits (`PhoneAPI::handleToRadioPacket`) | TEXT: 1 per 2 s (`RATE_LIMIT_EXCEEDED` NAK); POSITION/WAYPOINT/ALERT/TELEMETRY: 1 per 10 s per portnum — the packet is **dropped silently and a normal-looking `QueueStatus{res: 0}` is still sent**, so the app cannot detect the drop and must enforce the spacing itself; TRACEROUTE: 1 per 30 s | packets from the phone |

Your own node reports `DeviceMetrics.channel_utilization` and `air_util_tx` to the phone about once a minute (`DeviceTelemetry::runOnce` → `sendTelemetry(…, phoneOnly=true)`). Use those numbers for the backoff logic in §6.3 and the composer hints in §7.

---

## 4. Transport layer

### 4.1 BLE (primary transport)

**GATT** (firmware `src/BluetoothCommon.h`; same values in Android `MeshtasticBleConstants.kt` and Apple `BLEConnection.swift`):

| Characteristic | UUID | Properties |
|---|---|---|
| Service | `6ba1b218-15a8-461f-9fa8-5dcae273eafd` | — |
| ToRadio | `f75c76d2-129e-4dad-a1dd-7866124401e7` | write (with response) |
| FromRadio | `2c55e69e-4993-11ed-b878-0242ac120002` | read |
| FromNum | `ed9da18c-a800-4f66-a670-aa7547e34453` | read, notify |
| LogRadio | `5a3d6e49-06e6-4423-9944-e9de8cdf9547` | notify/indicate (optional) |
| Legacy LogRadio | `6c6fd238-78fa-436b-aacf-15c5be1ef2e2` | ignore |

**Protocol:**

1. Scan for the service UUID. Advertised name = node's device name.
2. Connect, discover the service and the four characteristics.
3. Request MTU 512 on Android (`requestMtu(512)`; the official app does this). nRF52 firmware uses `Bluefruit.configPrphBandwidth(BANDWIDTH_MAX)`; expect ~247 negotiated on T-Echo/WisMesh Tag. `ToRadio`/`FromRadio` messages are ≤ 512 bytes (`MAX_TO_FROM_RADIO_SIZE`); values longer than MTU−3 are handled by the OS stacks via long writes/reads. Use write-with-response and iOS `maximumWriteValueLength(for: .withResponse)` for chunk size sanity.
4. **Enable FromNum notifications before starting the handshake.** The firmware gates FromNum notifications behind `STATE_SEND_PACKETS`, so during the config download you must poll (Android `KableMeshtasticRadioProfile.kt` comment).
5. Handshake: write `ToRadio{ want_config_id: <random non-zero nonce> }`. Then read `FromRadio` repeatedly; each read returns one message, an empty (0-byte) read means the queue is drained. Continue until `FromRadio.config_complete_id == nonce`.
6. Steady state: on each FromNum notification, read FromRadio until empty. Also drain after every ToRadio write (responses to your own admin/get requests otherwise sit until the next unrelated notification).
7. Send `ToRadio{ heartbeat{} }` every ~5 min on TCP; harmless on BLE. Send `ToRadio{ disconnect: true }` before an intentional disconnect so the node resets its PhoneAPI state immediately (important for on-demand admin sessions).
8. If you receive `FromRadio{ rebooted: true }`, redo the handshake (step 5).

**Config download order** (`PhoneAPI.h/.cpp`, "the client apps ASSUME THIS SEQUENCE"):
`MY_INFO → (UIDATA) → OWN_NODEINFO → METADATA → CHANNELS (8×) → CONFIG (each Config variant) → MODULE_CONFIG → OTHER_NODEINFOS → FILEMANIFEST → (2.8: REGION_PRESETS) → COMPLETE_ID → PACKETS`. Do not depend on exact ordering beyond "my_info first, complete_id last"; store each variant as it arrives.

**Pairing (nRF52840, `NRF52Bluetooth.cpp`):** `Config.BluetoothConfig.mode` = `RANDOM_PIN` (6-digit random shown on the device screen; T-Echo), `FIXED_PIN` (`fixed_pin`, default `123456`; headless devices such as the WisMesh Tag default to this on first boot), or `NO_PIN` (Just Works). PIN modes use MITM-protected bonding (`SECMODE_ENC_WITH_MITM`); the OS shows the pairing dialog — **the app cannot enter the PIN programmatically**; guide the user. Pairing is remembered (bond). Advise changing the fixed PIN for headless nodes (docs call the default a significant risk); changing it is a `set_config(bluetooth)` → reboot.

**Single client per node:** one PhoneAPI session at a time (a second BLE central or a serial session conflicts). Unrelated nodes are unrelated links; a phone may hold several (design §4).

**Platform notes**
- Android: `BLUETOOTH_SCAN/CONNECT` runtime permissions (API 31+), a foreground service for the persistent Personal Node session, `CompanionDeviceManager` optional. Kable (used by the official app) or Nordic Android-BLE-Library both work. Bonding dialogs come from the OS.
- iOS: CoreBluetooth with `bluetooth-central` background mode and state restoration (`CBCentralManagerOptionRestoreIdentifierKey`). Subscribe to FromNum; wait for the `didUpdateNotificationStateFor` confirmation before the handshake (Apple `BLEConnection.swift` "pending notify confirmations").
- Both: reconnect with exponential backoff (1 s → 30 s), re-run the handshake on every reconnect, and treat a disconnect within ~10 s of a reboot-inducing admin write as expected (§6.7).
- Toolchain (decided, see `meshchat-v1-scope.md` §4): Wire / SwiftProtobuf generated from protobufs tag v2.8.0, platform cryptography only (no libsodium: invites are QR-only, §6.8), MapLibre + OpenFreeMap with offline regions, Room + SQLCipher on Android and GRDB with the same schema on iOS, minimum iOS 17 / Android 10, no analytics SDKs.

### 4.2 TCP, HTTP, serial (secondary transports)

- **TCP** port `4403` (firmware `StreamAPI`; UDP multicast shares the port). Framing: `0x94 0xC3 <len_hi> <len_lo> <protobuf>`; len > 512 → resync on `0x94`. Same ToRadio/FromRadio semantics; heartbeat required (connection timeout otherwise).
- **HTTP** (Wi-Fi nodes): `PUT /api/v1/toradio` and `GET /api/v1/fromradio?all=false`, `Content-Type: application/x-protobuf` (`src/mesh/http/ContentHandler.cpp`).
- **Serial/USB**: same framing as TCP over the CDC port.
Not required for v1 hardware (nRF52840 has no Wi-Fi), but keep the transport abstraction so the session layer is transport-agnostic (Apple's `Accessory/Protocols/Transport.swift` is a good model).

### 4.3 Multi-node connection manager

```text
NodeSession { nodeId, transport, state: Disconnected|Connecting|Pairing|Handshaking|Ready|Rebooting, kind: Personal|Base|Router, configSnapshot, phoneQueue }
Manager {
  personal: one persistent NodeSession (auto-reconnect, foreground/background service)
  adminSessions: on-demand NodeSession, opened for an admin task, closed with ToRadio.disconnect
  invariant: at most one active session per physical node
}
```

Rules: never let the on-demand session steal the Personal Node's slot; if a user admins their own Personal Node, reuse the persistent session. Expect a drop after `commit_edit_settings` (§6.7) and after reboots; re-handshake on reconnect and diff the received config against the local snapshot.

---

## 5. Session and data model

### 5.1 What to persist from the config download

| FromRadio variant | Store as |
|---|---|
| `my_info` (`MyNodeInfo`) | own nodenum, reboot_count, min_app_version, device_id |
| `node_info` (own first, then others) | node table: num, user (id, long/short name, hw_model, role, public_key, is_unmessagable), position, snr, last_heard, device_metrics, channel, hops_away, is_favorite, has_xeddsa_signed |
| `metadata` (`DeviceMetadata`) | firmware_version, hw_model, hasPKC, has_xeddsa, role |
| `channel` ×8 | channel slots (index, role, settings incl. module_settings) |
| `config` (device, position, power, network, display, lora, bluetooth, security) | config snapshot (security contains keys; keep encrypted-at-rest) |
| `moduleConfig` (telemetry at minimum) | module config snapshot |
| `region_presets` (2.8) | region → allowed presets map for the region picker |
| `config_complete_id` | handshake done |

Steady state: `packet` (MeshPacket), `queueStatus`, `clientNotification` (show as toast/log; includes firmware warnings), `log_record` (optional), `rebooted`.

### 5.2 Suggested local schema (both platforms)

```text
nodes(num PK, user_id, long_name, short_name, hw_model, role, public_key, is_unmessagable, last_heard, snr, rssi, hops_away, battery_level, voltage, ch_util, air_util_tx, uptime, has_xeddsa_signed, is_favorite, first_seen)
rooms(room_id PK = ChannelSettings.id, name, psk, precision, slot_index NULLABLE, generation, created_by, joined_via, is_active_location_room)
room_members(room_id, node_num, first_seen_on_room, last_seen_on_room, invited_by, invite_id)
messages(id PK = packet id, room_id NULLABLE, peer_num NULLABLE (DM), from_num, to_num, portnum, text, reply_id, emoji, sent_at, rx_time, status, ack_from, ack_error, rx_snr, hop_count, xeddsa_signed)
positions(node_num, room_id, lat_i, lon_i, alt, precision_bits, gps_time, rx_time, source)
waypoints(id PK, room_id, owner_num, lat_i, lon_i, name, description, icon, expire, locked_to, received_at)
invites(invite_id PK, room_id, kind qr|link, issued_at, expires_at, status pending|joined|expired|reused, joined_by)
node_sessions(node_num, kind personal|base|router, ble_address/peripheral_id, last_config_json)
```

### 5.3 Room membership derivation

Membership is **evidence-based**: a node is a member of room R when the app has received a channel-decoded packet on R's slot from it (any portnum) or when it joined via an invite handshake (§6.8). `MeshPacket.channel` holds the slot index for channel-decoded packets; for PKI DMs it is 0 and must not be used for room attribution. Nodes present in the NodeDB but never seen on a shared room (e.g. other MeshChat users on the shared primary, §9 D-1) are contacts, not members, and are not DM targets ("DMs only between people who share at least one room").

---

## 6. Feature implementation guides

Each feature: **Maps to** (stock Meshtastic mechanism) · **Wire facts** · **Firmware behaviour** · **Steps** · **Reference implementations** · **Tests** · **Pitfalls**.

### 6.1 Rooms

**Maps to:** a `SECONDARY` channel in slots 1–7; the room's PSK is the access control.

**Wire facts:** `Channel{ index, role: SECONDARY, settings: ChannelSettings{ name ≤ 11 bytes, psk: 32 random bytes, id: random fixed32 (room_id), uplink_enabled: false, downlink_enabled: false, module_settings{ position_precision: 0, is_muted: false } } }`, written with `AdminMessage{ set_channel }` to the local node.

**Firmware behaviour:** `set_channel` → `Channels::setChannel` + `saveChanges(SEGMENT_CHANNELS, false)` → **no reboot**. Channel hash and key tables refresh (`onConfigChanged`). Reading back: `get_channel_request = index + 1` (never send 0) → `get_channel_response`.

**Steps — create room**
1. Generate `psk` (32 bytes, CSPRNG), `room_id` (random fixed32 ≠ 0), name (validate ≤ 11 UTF-8 bytes, no leading/trailing whitespace). `position_precision` is always written as 0 (positions are sealed by the phone, §6.3.1); full precision (U-2) is what the phone seals. Room icon: one of eight fixed icons, stored app-side only (U-5).
2. Slot manager: pick the lowest free slot in 1..7 (`Channel.role == DISABLED`). If none → "7 rooms max" error.
3. Write the channel (§Appendix B, "set_channel"). Confirm by reading it back or by the ADMIN_APP response with `request_id`.
4. Persist the room; if it is the user's first room and location sharing is desired, offer to make it the active location room (§6.3).

**Steps — leave room / reindex (keep slots consecutive)**
1. For each slot `i` from the removed index to the last used slot, write the channel from slot `i+1` into slot `i` (same settings, `index = i`), and write `DISABLED` into the last used slot. Do it inside `begin_edit_settings` / `commit_edit_settings` **only if** you accept a BLE drop after commit (§6.7); otherwise write them one by one (no reboot, no drop) and verify afterwards by reading back all 8 channels.
2. Update `rooms.slot_index` by `room_id`, never by name. If the active location room moved, its precision moved with it (precision lives in the channel settings), so nothing else changes.
3. Position-sharing side effect: if the removed room was the active location room, live sharing stops automatically (no channel with precision ≠ 0), which matches the design.

**Steps — join via invite** → §6.8 (QR only). Decoding the invite writes nothing: it carries no key. The channel is written only when the `RoomGrant` arrives (§6.8.5), using the granted `room_id`, name, psk and precision. A grant for a `room_id` this phone already holds is only taken from a member of that room and only at a newer generation (catching up after a missed rotation); then the channel is overwritten in place.

**Slot 0 (primary) provisioning — D-1:** written once at node setup, never shown as a channel. `Channel{ index: 0, role: PRIMARY, settings{ name: <mode-dependent>, psk: <32-byte app-wide key>, id: 0x4D455348, module_settings{ position_precision: 0 } } }`. **Group only:** `name = "MeshChat"` → the firmware hashes the name into its own frequency slot. **Group + public relays:** `name = ""` → the firmware hashes the modem-preset display name instead (`LongFast`), landing on the public mesh's slot, so public nodes relay our encrypted packets while the private key keeps identity and telemetry unreadable. Never set `lora.channel_num` by hand (leave 0 so the derivation stays correct in every region). Switching modes = one `set_channel` on slot 0; verify on hardware whether the radio re-tunes without a reboot (`Channels::onConfigChanged` bumps `radioGeneration`) and fall back to `reboot_seconds` if not. All nodes in a group must share mode **and** modem preset or they cannot hear each other: the invite carries both (`LoRaProfile`), and the joiner aligns before writing the room. On 2.8, US public nodes may default to LongTurbo; public-relay mode should follow the local public preset (`FromRadio.region_presets` default), group-only mode keeps LongFast.

**LoRa alignment on join:** the invite carries the inviter's `modem_preset`, `use_preset`, `channel_num`, `override_frequency`, `region`, `hop_limit`. Apply preset/slot fields with `set_config(lora)` if they differ (reboot). Set `region` if `UNSET`; if set and different, warn — do not silently change a node's region. This mirrors python `setURL` (which applies `ChannelSet.lora_config` wholesale) but is safer.

**Reference implementations:** python `node.py` (`setURL`, `deleteChannel`, `getDisabledChannel`), Android `core/model/util/ChannelSet.kt`, Apple `Helpers/MeshtasticChannelURL.swift`.

**Tests:** encode/decode round-trip of 8-channel files; reindex property test (any removal keeps slots consecutive and preserves `room_id → settings`); name validation vectors (11-byte UTF-8 boundary); "empty PSK inherits primary" negative test (never emit an empty PSK).

**Pitfalls:** channel name is bytes, not characters; two rooms with identical name+key collide (same hash) — fine, but identical name with different keys is confusing for users; the firmware's own UI shows channel names, so avoid control characters.

### 6.2 Chat

#### 6.2.1 Group messages

**Maps to:** `TEXT_MESSAGE_APP` broadcast on the room's slot.

**Wire:**
```text
MeshPacket{ to: 0xFFFFFFFF, channel: slot, id: rnd32, hop_limit: lora.hop_limit||3, want_ack: true, priority: UNSET (→HIGH),
            decoded: Data{ portnum: TEXT_MESSAGE_APP, payload: utf8, reply_id: <optional packet id>, emoji: 0 } }
```

**Firmware behaviour:** with `want_ack` the node retransmits up to 3× (`NUM_RELIABLE_RETX`) until it overhears a rebroadcast; then the phone receives `Routing{NONE}` from **our own nodenum** = implicit ACK ("entered the mesh"). If nobody rebroadcasts (isolated node, hop_limit 0, or everyone muted), the phone receives `Routing{MAX_RETRANSMIT}`. On 2.8 the firmware also signs the broadcast if the signed Data fits (~165-byte text budget, §7). Incoming text may arrive as `TEXT_MESSAGE_APP` or `TEXT_MESSAGE_COMPRESSED_APP` (the firmware converts before delivering to the phone, but handle both).

**Status mapping (design: "must not imply delivery")**

| Event | Status | UI |
|---|---|---|
| Written to ToRadio | `queued` | clock |
| `QueueStatus{res: 0, mesh_packet_id: id}` | `sent_to_node` | single grey tick |
| `QueueStatus{res ≠ 0}` or `Routing{RATE_LIMIT_EXCEEDED / DUTY_CYCLE_LIMIT / TOO_LARGE / NO_CHANNEL}` | `error(reason)` | red, retry option |
| `Routing{NONE}` from own nodenum, broadcast | `reached_mesh` | single dark tick, tooltip "heard by at least one node" |
| `Routing{MAX_RETRANSMIT}` | `unheard` | grey "no one heard this" |
| No routing packet within 120 s | `unknown` | keep single grey tick |

Android reference: `MeshDataHandlerImpl.handleAckNak` maps ACK from `p.to` → `RECEIVED`, other ACK → `DELIVERED`(implicit), else `ERROR`; `PacketHandlerImpl` stamps `TIMEOUT` after a grace period. Apple: `MeshPackets.swift routingPacket` (`realACK` only when `packet.to != packet.from`).

#### 6.2.2 Direct messages

**Maps to:** `TEXT_MESSAGE_APP` unicast with PKI.

**Wire:** same as above with `to: peer`, `pki_encrypted: true`, `public_key: peer key (optional; firmware uses NodeDB)`, `channel: 0`, `want_ack: true`.

**Preconditions (both enforced by firmware):** our node's NodeDB holds the peer's 32-byte public key (else NAK `PKI_SEND_FAIL_PUBLIC_KEY`), and the peer's NodeDB holds ours (else it cannot decrypt; with `want_ack` it NAKs `PKI_UNKNOWN_PUBKEY` on the primary channel). Before every DM, send `AdminMessage{ add_contact: SharedContact{ node_num, user (with public_key) } }` to the local node from the app's own contact record — the official Android client does exactly this to survive NodeDB eviction (`NodeDB::addFromContact` comment). Cost: zero airtime.

**Policy:** offer DM only to room co-members (§5.3) whose `User.is_unmessagable` is false.

**Status:** `Routing{NONE}` from **the peer's nodenum** → `delivered` (double tick). `Routing{NONE}` from own nodenum on a unicast is an implicit relay ACK → `reached_mesh` only. `PKI_UNKNOWN_PUBKEY` → show "peer does not know your key yet; send a hello" and trigger the NodeInfo hello (§6.8 step A).

**2.8 note:** DMs are not XEdDSA-signed (PKI provides authenticity). `xeddsa_signed` stays false on DMs; do not show "unverified" for them.

#### 6.2.3 Quick replies / canned messages

App-side list only (no firmware `CannedMessageConfig`; that module is for the device's own buttons). Enforce the phone-API text rate limit: queue outgoing texts with ≥ 2 s spacing per node; `RATE_LIMIT_EXCEEDED` (38) arrives as a Routing NAK if you violate it. Keep canned strings ≤ 40 bytes.

#### 6.2.4 Alerts

**Maps to:** `ALERT_APP` (11) with `priority: ALERT` (110), as python `sendAlert` does (`portNum=ALERT_APP, priority=ALERT, wantAck=False`).

**Wire:** `MeshPacket{ to: broadcast|peer, channel: slot (or PKI for a peer), priority: ALERT, want_ack: true (MeshChat choice, gives the reached-mesh indicator), decoded: Data{ portnum: ALERT_APP, payload: utf8 } }`.

**Firmware behaviour:** the node treats ALERT_APP as a text payload (`MeshService::isTextPayload`) and rate-limits phone-originated alerts to one per 10 s (silent drop → watch `QueueStatus`). An ASCII BEL (`0x07`) in a text payload triggers the device's alert bell outputs if configured (`isAlertPayload`); optional. Receivers: show as high-priority notification even when the room is muted. 2.8's `PAGING_APP` (acknowledged paging) is out of scope.

#### 6.2.5 Message length

Composer counter: hard stop at **200 bytes of UTF-8** (Apple `TextMessageField.maxbytes = 200`). Show a secondary hint at 165 bytes on 2.8 nodes ("longer messages are sent unsigned") only if `has_xeddsa`. See §7 for the arithmetic. Do not attempt client-side fragmentation in v1.

**Reference implementations:** Apple `AccessoryManager+ToRadio.swift sendMessage` (sets `wantAck`, `channel`, `pkiEncrypted`, `publicKey`, hop limit from config); Android `CommandSenderImpl.kt`; python `sendText`, `sendData`.

**Tests:** ACK-from-self vs ACK-from-peer mapping; NAK mapping for each `Routing.Error`; dedupe on redelivered packets; 2 s text spacing; 200-byte limit at multi-byte boundaries; `TEXT_MESSAGE_COMPRESSED_APP` decode.

### 6.3 Location

#### 6.3.1 Live location sharing (one active room)

> **Superseded 2026-09-29 (build plan Stage 7.9).** Positions are now sealed by the
> phone: `MeshChatControl.position` (a `meshtastic.Position`) inside a `SealedMessage`
> under the room key, broadcast on the room's slot at the radio's beacon interval
> while the user shares (`LocationRepository`). `position_precision` is written as
> **0 on every channel**, rooms included, and re-asserted on every connection, so
> the firmware never broadcasts or answers for us: its broadcast travels under the
> channel key, which anyone holding a member's radio has, and it outlives the phone.
> Sharing therefore pauses while the phone is away from its radio. The firmware
> behaviour below is kept because it is what "precision 0 everywhere" defends against.

**Maps to:** firmware `PositionModule` periodic broadcast + per-channel `position_precision`.

**Firmware behaviour (verified, both versions):**
- Periodic send: `PositionModule::runOnce` every `position_broadcast_secs` (config 0 → default **1 h** for CLIENT roles, `default_broadcast_interval_secs`; the docs' "15 min" is stale) scaled up with online node count (`getConfiguredOrDefaultMsScaled`); smart broadcast (`position_broadcast_smart_enabled`, default true) sends earlier when moved ≥ `broadcast_smart_minimum_distance` (default 100 m) and ≥ `broadcast_smart_minimum_interval_secs` (default **5 min** on 2.8; check 2.7 `Default.h`). Both gated by ChUtil < 25 % and by having a fix since boot (`hasLocalPositionSinceBoot`) unless `fixed_position`.
- Channel choice: **first index 0..7 with non-zero precision** (`sendOurPosition()`/`findPositionChannel`). Precision applied per channel (`applyPositionPrecision`, coordinates truncated to the bucket centre). Priority BACKGROUND (RELIABLE for TRACKER roles).
- **Any position packet originated by our node, including ones built by the phone, gets the channel's precision applied in `Router::send`** (`applyPositionPrecisionForChannel`, 2.7 `Router.cpp:357`, master `:568`). Precision 0 strips coordinates to time-only. So "sharing off" is enforced by the firmware for phone-built packets too.
- `set_config(position)` **reboots** the node (`AdminModule::handleSetConfig`, `requiresReboot` stays true for the position case). `set_channel` does not.
- **Interval floors on default channels (both versions, `NodeDB.cpp` after `resetRadioConfig()`):** at boot the firmware raises `position_broadcast_secs` to ≥ 1 h (`min_default_broadcast_interval_secs`) and `broadcast_smart_minimum_interval_secs` to ≥ 5 min **only if the first channel with non-zero precision uses a well-known key** (`channels.isDefaultChannel(i)`). Rooms use random 32-byte PSKs, so the tier table below is honoured. Never enable precision on a default-key channel or the tiers silently collapse to hourly.

**MeshChat model:** `active_location_room` = the single room whose channel has `position_precision > 0`. Slot 0 stays at 0. Start/stop/switch = channel writes (no reboot). Duration tiers = position config profile (reboot only when the tier changes).

| Tier (design) | `position_broadcast_secs` | `broadcast_smart_minimum_interval_secs` | `broadcast_smart_minimum_distance` | Auto-stop |
|---|---|---|---|---|
| minutes (≤ 60 min) | 120 | 30 | 25 m | at end |
| hours (≤ 12 h) | 600 | 120 | 50 m | at end |
| days | 1800 | 300 | 100 m | at end |
| off (provisioning default) | 3600 | 300 | 100 m | — |

Values are starting points; they must be re-tuned on hardware (design §10) and against ChUtil. Smart broadcast is what makes the minutes tier useful (moving users update quickly, stationary ones do not spend airtime).

**Steps — start sharing (room R, tier T, duration D)**
1. If the current position config differs from tier T's profile → `set_config(position{…})`. Warn the user: "node restarts for ~10 s". Wait for reconnect + handshake.
2. Ensure every other channel has `position_precision = 0` (read back channels; fix any drift) and write R's channel with `position_precision = room.precision` (`set_channel`, no reboot).
3. Schedule local auto-stop at `now + D`; persist it so an app restart still stops sharing.
4. Backoff: if own `air_util_tx` or `channel_utilization` (from the node's phone-only telemetry) exceeds 20 %/40 %, temporarily bump the tier one step slower (one config write, reboot) or, cheaper, do nothing — the firmware already skips periodic sends above 25 % ChUtil. Prefer "do nothing" in v1 and surface the utilization in the UI.

**Steps — stop / expire:** write R's channel with `position_precision = 0`. Optionally return position config to the "off" profile later (reboot) — batch it with the next admin visit rather than doing it immediately.

**Steps — switch active room:** one `set_channel` to zero the old room, one to set the new room. Order: zero first (never two channels > 0 at once, or the lower index wins silently).

**Phone GPS fallback ("smart GPS source"):** when the node reports no fix (own `Position` missing/`gps_mode ≠ ENABLED`) and the phone has one, send the phone position **to the node itself**:
```text
MeshPacket{ to: my_node_num, channel: 0, hop_limit: 0, priority: BACKGROUND,
            decoded: Data{ portnum: POSITION_APP, payload: Position{ latitude_i, longitude_i, altitude, time, location_source: LOC_EXTERNAL, ... } } }
```
Apple does exactly this (`AccessoryManager+Position.swift`: `sendPosition(channel: 0, destNum: fromNodeNum)`). The packet never goes on air (`isToUs`), the firmware stores it as the local position (`PositionModule::handleReceivedProtobuf` → `setLocalPosition`, unless `fixed_position`), and the normal periodic/smart broadcast then applies channel and precision rules. Cadence: at the tier's smart-minimum interval; stop when the node reports its own fix. Respect the 10 s phone-API position rate limit. Throttle further when the node battery (`battery_level`) is < 20 % or the phone is in low-power mode. Do not change `gps_update_interval`/`gps_mode` on the fly (reboot); those are provisioning decisions.

#### 6.3.2 Location requests (any room)

- **Current location** *(superseded 2026-09-29: now a sealed `PositionQuery` to the peer on a shared room's slot, answered by the peer's phone with a sealed position, and only while it shares with that room)*: `MeshPacket{ to: peer, channel: slot of a shared room, want_ack: false, decoded: Data{ portnum: POSITION_APP, payload: <our current Position or empty Position>, want_response: true } }`. POSITION_APP is never PKI-encrypted, so it rides the room key. The peer's firmware answers automatically (`PositionModule::allocReply`) but **at most once per 3 minutes per node** and only if it has a valid position; the reply arrives as a POSITION_APP packet with `request_id = your id`. Show "requested…" and time out after ~3 min. Note that the reply is subject to the peer's precision for that channel.
- **Live sharing request:** app-level. Send a PKI DM on the MeshChat control port carrying `LiveLocationRequest{ room_id, suggested_secs }` (§6.8.5); the peer\'s app shows the duration picker; starting live sharing is always their own decision. Reply is implicit (they start sharing or not).

#### 6.3.3 Pin drops (waypoints)

> **Superseded 2026-09-29.** Pins travel as `MeshChatControl.pin` (a `meshtastic.Waypoint`)
> inside a `SealedMessage` under the room key, so their place and their lock answer to the
> room key rather than the channel key. `WAYPOINT_APP` is neither sent nor accepted for rooms;
> stock Meshtastic apps no longer see Firepit pins. Name ≤ 30 and description ≤ 100 UTF-8 bytes,
> so a sealed pin at its limits still fits one packet (`ProtocolContractTest`). The delete
> convention below (resend with `expire = 1`) is unchanged.

**Maps to:** `WAYPOINT_APP` broadcast on the room's slot.

```text
Waypoint{ id: rnd32 ≠ 0, latitude_i, longitude_i, expire: unix_secs (always set; design says auto-expire), locked_to: my_node_num | 0, name ≤ 29 bytes, description ≤ 99 bytes, icon: unicode codepoint }
MeshPacket{ to: broadcast, channel: slot, want_ack: true, decoded: Data{ portnum: WAYPOINT_APP, payload: Waypoint } }
```

Firmware convention: **delete = resend the same id with `expire = 1`** (`WaypointModule::broadcastDelete`: "Already-expired = the mesh convention for 'delete this waypoint'"). Only the owner (`locked_to`) may delete mesh-wide; others remove locally. Devices with screens display received waypoints; the firmware also validates the nested protobuf before handing it to the phone (`phonePayloadIsDecodable`). Rate limit: one waypoint per 10 s from the phone. 2.8 adds optional geofence fields; ignore.

#### 6.3.4 Position precision per room

*(Superseded 2026-09-29: every channel is written with precision 0 and positions are sealed at full precision by the phone; the invite's and grant's `position_precision` fields are reserved.)* `ModuleSettings.position_precision` on the room's channel. **v1: always 32 (full precision, U-2)** — no per-room choice in the UI; the invite still carries the value so a future reduced-precision option needs no format change. The firmware supports 0–32 (Appendix C radii); every member's node applies the value from its own channel settings. On 2.8, well-known keys are capped at 15 bits (not applicable to rooms).

#### 6.3.5 Live map

- Position source per node: latest POSITION_APP packet on any shared room (channel-decoded) or NodeDB `NodeInfo.position` from the handshake. Keep `precision_bits` to draw an uncertainty circle when < 32.
- Liveness: `live` if a position was received within 2× the active tier's interval **and** `last_heard` is recent; else `stale` (grey, with age). 2.8 relays may suppress identical repeated positions for up to 5 h (traffic management), so a stationary node can look stale by position while `last_heard` proves it is alive — show "here since …" in that case.
- Colours: hash of nodenum → palette index for personal nodes; one reserved colour + icons for Base/Router (role from `User.role`). Marker label = `User.short_name` (the user's tag), full `long_name` in the caption; fall back to the last 4 hex digits of the nodenum until NodeInfo arrives.
- Time: `Position.time` is GPS time (may be 0); `rx_time` is the node's RTC; prefer `rx_time` for age, but handle nodes without RTC (0).

**Reference implementations:** Apple `AccessoryManager+Position.swift`, python `sendPosition`, `sendWaypoint`; Android map features under `feature/map*`.

**Tests:** precision truncation must equal the firmware's `truncateCoordinate` (mask + bucket-centre offset) for random inputs; channel-choice invariant (exactly one non-zero precision among slots 1–7, slot 0 always 0); auto-stop persistence across app restart; phone-GPS injection produces no on-air packet (verify with a second node); 3-minute reply throttle handling.

### 6.4 Status (battery, signal)

**Maps to:** `TELEMETRY_APP` `DeviceMetrics` broadcasts and per-packet radio metadata.

- Broadcast conditions (`DeviceTelemetry::runOnce`): `device_telemetry_enabled == true`, interval `device_update_interval` (0 → 1 h default, scaled by mesh size), ChUtil < 25 %, air-util gate, not `CLIENT_HIDDEN`. Sent on **channel 0 only**, BACKGROUND priority, `want_response: false`. Consequence: members see each other's battery only if they share the primary key (§9 D-1) and the flag is on.
- Provisioning: `set_module_config(telemetry{ device_telemetry_enabled: true, device_update_interval: 1800 })` (30 min; 1 packet per node per 30 min). Module config writes may reboot; batch with other provisioning. If **any** channel on the node uses a well-known key (`channels.hasDefaultChannel()`, e.g. a stock LongFast primary under D-1 option (a)), the firmware raises all telemetry intervals to ≥ 30 min at boot (`min_default_telemetry_interval_secs`); 1800 s satisfies that floor either way.
- Own node: `sendTelemetry(phoneOnly=true)` about every minute → keep `channel_utilization`/`air_util_tx` for backoff and the composer hint. `battery_level == 101` means USB-powered (`MAGIC_USB_BATTERY_LEVEL`).
- On-demand refresh (not in v1 per "no app-invented polling"): `Data{ portnum: TELEMETRY_APP, payload: Telemetry{device_metrics{}}, want_response: true }` unicast would be answered (`DeviceTelemetry::allocReply`, multi-hop broadcast requests are ignored).
- Signal per node: `rx_snr`, `rx_rssi` (last hop only), hops = `hop_start − hop_limit` when `hop_start > 0` (unknown otherwise), `relay_node` = last byte of the last relay's nodenum. Persist a rolling window per node; render as bars, no notifications (design).

### 6.5 Member list

Rows from `nodes ⨝ room_members`: long/short name, `hw_model` (device type), battery (`DeviceMetrics`), signal (§6.4), role icon, `invited_by` (from invite handshake or join announcement, §6.8), `has_xeddsa_signed` badge on 2.8. Mark all members as **favorites on the Personal Node** (`set_favorite_node`, no reboot) so they survive NodeDB eviction; unfavorite when they leave. Unknown nodes heard only on the primary (other MeshChat users) live in a separate "nearby" list, not in rooms.

### 6.6 Node types and roles

**Maps to:** `Config.DeviceConfig.role`: `CLIENT = 0`, `ROUTER = 2`, `REPEATER = 4 [deprecated]`, `ROUTER_LATE = 11`, `CLIENT_BASE = 12` (also `CLIENT_MUTE = 1`, `CLIENT_HIDDEN = 8`, `TRACKER = 5`, `SENSOR = 6`, …).

**Verified semantics**
- `CLIENT`: rebroadcasts once unless it hears another rebroadcast first.
- `CLIENT_BASE`: "always rebroadcasts packets from or to its favorited nodes; handles all other packets like CLIENT" (`FloodingRouter.cpp:126`, `NodeDB::isFromOrToFavoritedNode`). Hop limit is **not decremented** when a favorite router-class node relays to another router-class node (`Router.cpp` ~256–290: ROUTER/ROUTER_LATE/CLIENT_BASE). Firmware will not auto-favorite the admin phone's node on a CLIENT_BASE ("is_favorite has special meaning").
- `ROUTER_LATE`: always rebroadcasts once, after all other roles (`clampToLateRebroadcastWindow`); visible in node lists.
- `ROUTER`: always rebroadcasts with priority; preempts others; reserved for well-sited deployments (warn before offering).
- `REPEATER`: `[deprecated = true]` in `config.proto`; do not offer.
- Role change → `set_config(device)` → **reboot** (only role/rebroadcast_mode/button/buzzer changes trigger it).

**Provisioning per type**

| Type | role | Extra |
|---|---|---|
| Personal | `CLIENT` | rooms; `set_owner` with the user's name |
| Base | `CLIENT_BASE` | rooms (so members see it); `set_favorite_node` for every member nodenum and for other infra nodes; `set_fixed_position` if static (no reboot; sets `fixed_position = true`); `set_owner{ is_unmessagable: true }` so it is not a chat target; optional precision on one room |
| Router | `ROUTER_LATE` | rooms optional; favorites for infra peers; `is_unmessagable: true`; location optional |

`hop_limit`: leave `lora.hop_limit = 3` (0 = default 3). Do not raise.

### 6.7 Node admin (BLE only)

**Maps to:** `ADMIN_APP` packets addressed to the local node.

```text
MeshPacket{ to: my_node_num, channel: 0, id: rnd32, hop_limit: 0, want_ack: false,
            decoded: Data{ portnum: ADMIN_APP, want_response: <true for get_*>, payload: AdminMessage{ session_passkey: <8 bytes>, <variant> } } }
Response: MeshPacket{ from: my_node_num, decoded: Data{ portnum: ADMIN_APP, request_id: <your id>, payload: AdminMessage{ *_response } } }
```

**Session passkey:** `AdminMessage{ get_config_request: SESSIONKEY_CONFIG (8) }` → response contains `session_passkey` (8 bytes; regenerated after 150 s, accepted for 300 s). Include it in every set/action message. Firmware only enforces it for remote senders (`AdminModule.cpp`: `if (mp.from != 0 && !messageIsRequest && !messageIsResponse) checkPassKey`), but include it regardless (python `ensureSessionKey`, Android `SessionManager`).

**Authorization for local admin:** `from == 0` (the node forces this for phone packets) is trusted unless `Config.SecurityConfig.is_managed` is true (then local admin is ignored). MeshChat never enables `is_managed`, `admin_channel_enabled`, or remote `admin_key` (design: BLE only).

**Reboot matrix (`AdminModule::handleSetConfig`, `handleSetChannel`, verified on master; 2.7 equivalent)**

| Write | Reboots? | Notes |
|---|---|---|
| `set_channel` | no | `saveChanges(SEGMENT_CHANNELS, false)` |
| `set_owner` | no | validates non-blank long_name; triggers NodeInfo refresh |
| `set_favorite_node` / `remove_favorite_node` | no | |
| `set_fixed_position` / `remove_fixed_position` | no | sets/clears `position.fixed_position` |
| `add_contact` | no | |
| `set_config(device)` | only if role / rebroadcast_mode / button_gpio / buzzer_gpio changed | |
| `set_config(position)` | **yes** | no `requiresReboot = false` branch |
| `set_config(lora)` | yes if any radio parameter (region, preset, bw/sf/cr, tx_power, freq offset, channel_num, …) changed | |
| `set_config(bluetooth)`, `set_config(security)`, `set_config(network)`, `set_config(display)` | yes | |
| `set_config(power)` | only for some fields | |
| `set_module_config(*)` | mostly yes | verify per module |
| `commit_edit_settings` | reboots if any batched write required it; **always calls `disableBluetooth()`** first (master and 2.7) → expect the BLE link to drop | |
| `reboot_seconds`, `shutdown_seconds`, `factory_reset_*`, `nodedb_reset` | yes / destructive | confirm with the user |

**Transactions:** `begin_edit_settings` delays saves; `commit_edit_settings` saves everything once and reboots if needed. Use a transaction for the initial provisioning batch (region + role + owner + telemetry module + channels), expect one disconnect, reconnect, re-handshake, and verify by diffing the downloaded config. For single no-reboot edits (channels, favorites, fixed position), write directly without a transaction.

**Adding a node (design §4, "one at a time")**
1. Scan → user picks a node → connect → OS pairing (PIN) → handshake.
2. Check `Config.lora.region`: if `UNSET`, force the region picker (node cannot transmit). On 2.8 use `region_presets` to filter presets; keep `modem_preset` consistent with the group.
3. Choose type → `set_config(device{ role })`.
4. `set_owner(User{ long_name ≤ 23 bytes, short_name ≤ 4 bytes, is_unmessagable: <infra> })`. `short_name` is the user-chosen **tag** shown on avatars and map markers by MeshChat and by every other Meshtastic client. The protobuf allows 4 UTF-8 bytes (nanopb `max_size:5`); **MeshChat enforces exactly 2 characters** (two Latin/Arabic/Cyrillic characters fit; a CJK or emoji character is 3–4 bytes, so allow one). Validate by grapheme count and by encoded byte length. Propose first + last initials when the account name has two words ("Sarmad Jari" → "SJ"), else the first two letters, but always let the user type their own; before applying, compare against `short_name` values of known room members and hint alternatives on collision (cannot be enforced — tags live on each node). Nodes provisioned by other clients may carry 3–4 character tags: display them at a smaller font, never truncate. Changing it later is another `set_owner` (no reboot); the firmware then re-broadcasts NodeInfo on its own schedule (≥ 1 h between periodic broadcasts, `min_node_info_broadcast_secs`), so peers may see the old tag for a while — the app can send a NodeInfo hello on each room (§6.8.5 step A) to speed it up.
5. Provision rooms (§6.1) with explicit precision (0 on slot 0). Note the primary channel policy in §9 D-1.
6. `set_module_config(telemetry{ device_telemetry_enabled: true, device_update_interval: 1800 })`.
7. Commit (transaction) → disconnect → reconnect → verify.

**Sanity checks after every reconnect:** `lora.tx_enabled == true`, region set, channel 0 role PRIMARY, exactly one room with precision > 0 (or none), owner names as expected. Report drift instead of silently rewriting (another client may have changed the node).

**Reference implementations:** python `node.py` (`_sendAdmin`, `writeChannel`, `beginSettingsTransaction/commitSettingsTransaction`), Android `CommandSenderImpl.kt`, `AdminPacketHandlerImpl.kt`; Apple `AccessoryManager+ToRadio.swift` admin helpers.

### 6.8 Security, identity, and invites

#### 6.8.1 What is native (do not reimplement)

- Channel PSK (AES-256-CTR) for rooms; PKI for DMs; per-node X25519 keys generated by the firmware (`Config.SecurityConfig.public_key/private_key`; never export the private key).
- 2.8 XEdDSA signatures on broadcasts; display `xeddsa_signed` only when true.
- Optional manual key verification (`KEY_VERIFICATION_APP`, `AdminMessage.key_verification`, `ClientNotification.key_verification_*`): a 2-party handshake with a 6-digit code exchanged out of band; sets `NodeInfo.is_key_manually_verified`. Out of scope for v1 but the cleanest future upgrade for "verified member".
- `SharedContact` (`admin.proto`) and the `https://meshtastic.org/v/#<base64url(SharedContact)>` contact-URL format (python `node.py`, Apple `ContactURLHandler.swift`, Android `SharedContact.kt`) — reuse the message type inside MeshChat invites.

#### 6.8.2 Invite payload (app-level, never on air)

Define a MeshChat-private protobuf; it is encoded into QR codes and links only.

```protobuf
syntax = "proto3";
package meshchat;
import "meshtastic/mesh.proto";      // User
import "meshtastic/config.proto";    // LoRaConfig enums

enum MeshMode { GROUP_ONLY = 0; PUBLIC_RELAY = 1; }   // D-1 range mode, must match across the group
message LoRaProfile {
  bool use_preset = 1;
  meshtastic.Config.LoRaConfig.ModemPreset modem_preset = 2;
  uint32 channel_num = 3;            // 0 = derived from primary name
  float override_frequency = 4;
  meshtastic.Config.LoRaConfig.RegionCode region = 5;  // hint; never overrides a set region
  uint32 hop_limit = 6;
  MeshMode mesh_mode = 7;            // joiner aligns slot 0 name to this mode before joining
}
message Inviter { uint32 node_num = 1; meshtastic.User user = 2; }   // user.public_key must be 32 bytes
message Invite {
  reserved 4, 13;                    // held open: a key here is in every photograph of the code
  uint32 version = 1;                // 1
  fixed32 room_id = 2;               // == ChannelSettings.id
  string room_name = 3;              // ≤ 11 UTF-8 bytes
  uint32 position_precision = 5;
  uint32 generation = 6;             // increments on PSK rotation
  LoRaProfile lora = 7;
  Inviter inviter = 8;               // carries the key the hello is encrypted to
  fixed32 invite_id = 9;             // random, non-zero
  uint32 issued_at = 10;             // unix seconds (inviter clock)
  uint32 window = 11;                // rotation window index
  bytes token = 12;                  // 8 bytes
}
```

**The invite carries no key material.** A photograph of the code yields a room
name, a room id, the inviter's public key and a token that stops being accepted
in about thirty seconds. The keys arrive in a `RoomGrant` (§6.8.5), encrypted to
the joiner, after a person approves.

#### 6.8.3 QR invites (in-person, rotating)

- `invite_key = HMAC-SHA256(key = room_psk, msg = "meshchat-invite-v1" || room_id || generation)` — derivable by every current key-holder, so any member can invite (design).
- Every `ROTATION_SECS = 8` s: `window = floor(now / ROTATION_SECS)`, `token = HMAC-SHA256(invite_key, inviter.node_num || window)[0:8]`, re-encode, re-render. The QR payload is `firepit://join?v=1&d=<base64url(Invite)>` (register the scheme on both platforms).
- **The scanner cannot verify the token.** It is an HMAC under the room key, which the scanner does not hold and is not yet trusted with. It checks only that `window` is within ±2 of its own clock, which fails an obviously old photograph immediately; the inviter proves the token before granting anything.
- The invite screen sets `FLAG_SECURE`, so the code cannot be screenshotted or screen-recorded into a photo backup.
- Size: Invite ≈ 32 (inviter key) + names + ~40 → ~120 bytes → ~160-char base64url; QR version 7–9 at ECC M, fine for phone screens.

#### 6.8.4 There are no link invites

Designed, specified, and dropped. A link can be forwarded, screenshotted, left in a chat history or read by whoever else has that phone, and an expiry window narrows that rather than fixing it. A PIN only turns the problem into a second message travelling the same way as the first — and, once the ciphertext is in the attacker's hands, one they can grind offline at their own pace.

A QR code has to be pointed at a camera, which means the two people are in the same place. That is the property everything else rests on.

Consequence, accepted deliberately: somebody who is not with you cannot be added. No Argon2, no libsodium, no domain to register.

#### 6.8.5 Join handshake (MeshChat control port)

No NodeInfo step and no propagation wait: the inviter's public key is in the
code, so the joiner can speak privately from the first packet.

**Step A — joiner confirms, then seeds its own radio.** Nothing is sent until the reader has seen the inviter's node id and key fingerprint from the code and checked them against the inviter's screen: a code is anyone's to print. The radio encrypts from its own NodeDB, which is bounded and evicts; the app knowing a key is not the same as the radio knowing it. Hand it over from the invite rather than broadcasting and hoping — but only when the radio knows no key for that node. A code naming a known node under a different key is refused (`TrustRules.contactFor`), since overwriting it would redirect everything sent to that node to whoever printed the code:
```text
AdminMessage{ add_contact: SharedContact{ node_num: inviter.node_num, user: inviter.user } }
```
A code for a room this phone already holds is only answered when the inviter is a member of it (catching up after a missed rotation); otherwise it is an attempt to replace that room.

**Step B — join hello (PKI DM on the MeshChat control port):**
```text
MeshPacket{ to: inviter.node_num, pki_encrypted: true, want_ack: true,
            decoded: Data{ portnum: PRIVATE_APP,
                           payload: MeshChatControl{ join_hello: JoinHello{ invite_id, token, generation, app_version, joiner_key, phone_key } } } }
```
`joiner_key` is the joiner's own 32-byte radio key. The firmware reports the key it decrypted a PKI packet with in `MeshPacket.public_key`, and the two must be equal (`TrustRules.helloIsBound`), so a spoofed hello cannot name somebody else's. `phone_key` is the joiner's 33-byte phone key (`KeyEnvelope`), which the room's own key is sealed to in the grant.

Nothing is written to the radio at this point. The code carries no keys, so there is no room to write until the inviter answers.

**Step C — inviter decides.** On a `JoinHello`, in order:

| Check | Why |
|---|---|
| ≤ 5 attempts per node per minute | A stranger who cannot pass the token check has no reason to keep trying |
| PKI, addressed to us, `joiner_key == packet.public_key` | The grant goes to that key; a claimed one would let a spoofed hello point the room's keys at somebody else |
| `invite_id` not already spent | A code stops being worth presenting once the person it was shown to is in; the next code drawn gets a new id |
| `hop_start == hop_limit` | A code is shown to somebody in front of you; anything relayed was read somewhere you cannot see. Fails closed: neither field is authenticated, so a sender three hops out can leave `hop_start` at 0 to look unmeasurable while setting `hop_limit` high enough to be carried — it then arrives claiming a negative distance. Only an exact match counts as adjacent (`PacketOrigin.arrivedDirectly`) |
| `matchesRecentToken` within 4 windows (~32 s) | Proves the code is genuine |
| A later hello never swaps the keys of one already waiting | The first one is what the person in front of you is checking |
| **A person taps "Let in", having compared fingerprints** | Proves the bearer is who it was shown to — which no token can. Both screens show the joiner's radio key fingerprint |

A token proves the *invite* is genuine, not that the *bearer* was authorised. Without the last row, a photographed code used inside the window still yields the keys.

**Step D — grant.** On approval the inviter sends, to the key its firmware decrypted the hello with (which its NodeDB therefore already holds):
```text
MeshPacket{ to: joiner, pki_encrypted: true, public_key: joiner_key, want_ack: true,
            decoded: Data{ portnum: PRIVATE_APP,
                           payload: MeshChatControl{ room_grant: RoomGrant{ answer: GRANTED, invite_id, room_id, room_name, room_psk, generation, sealed_key, key_hour } } } }
```
`generation` is the room key's current generation. `sealed_key` is that generation's key for the current hour, sealed to the joiner's `phone_key` and bound to `room_id`, `generation`, the joiner's node number and `key_hour`, the hour it belongs to (UTC hours since 1970). Room keys move on every hour, one way (security.md §3), so the joiner reads from the hour they were let in and nothing recorded before it. The radios at both ends decrypt the PKI layer, and anyone holding one can read its private key, but neither can open this. Declining sends the same message with `answer: DECLINED` and no keys, so the joiner is told rather than left waiting.

The joiner accepts a grant only from the node it asked, only for the invite it asked about, and only while still waiting. The sender check is not redundant with the id checks: `room_id` and `invite_id` both travel in the QR code, so anyone who photographed it can name them and race the real inviter with their own keys, landing the scanner in a room they control. Answering *as the scanned node* needs that node's private key, and the joiner seeded its public key from the code in step A — so `packet.from == awaited.inviter` on an already-`pki_encrypted` packet is what makes the substitution fail. A grant for a room already held is taken only from a member of it, moving it forward (`TrustRules.mayTakeGrant`). It then writes the channel, stores both keys, and announces itself. The inviter broadcasts a sealed `RosterEvent{ JOINED, phone_key }` to the room and sends the roster privately to the joiner.

**Consequence, by design:** a join cannot cross a relay. Both people must be in RF range of each other, which is the same requirement as pointing a camera at a screen. A joiner who is out of range sees the request time out rather than silently joining.

**MeshChat control protobuf (v1, `PortNum.PRIVATE_APP`) — decided in D-2:**
```protobuf
message MeshChatControl {
  uint32 version = 1;                                 // 1
  oneof payload {
    JoinHello join_hello = 2;                         // joiner → inviter, PKI DM
    RosterEvent roster_event = 3;                     // inviter → room broadcast: JOINED / KEY_ROTATED
    LiveLocationRequest live_location_request = 4;    // member → member, PKI DM
    RosterSync roster_sync = 5;                       // inviter → joiner, the room's members
    Receipt receipt = 6;                              // room broadcast (sealed) or PKI DM
    SealedMessage sealed_message = 7;                 // any of the above, encrypted under the room key
    RoomText room_text = 8;                           // a message, inside a SealedMessage
    KeyRotation key_rotation = 9;                     // re-key after a removal
    PersonCard person_card = 10;                      // name, tag and colour, shared on join
    RoomGrant room_grant = 11;                        // inviter → joiner, PKI DM: the keys
    SealedDirect sealed_direct = 12;                  // one person's words or receipts, phone to phone, inside PKI
    meshtastic.Position position = 13;                // a member's fix, inside a SealedMessage
    meshtastic.Waypoint pin = 14;                     // a pin, inside a SealedMessage
    PositionQuery position_query = 15;                // "where are you?", inside a SealedMessage
  }
}
message SealedDirect { bytes ciphertext = 1; }        // DirectSeal: P-256 ECDH of both phones' keys + HKDF + AES-GCM
message PositionQuery {}
message JoinHello { fixed32 invite_id = 1; bytes token = 2; uint32 generation = 3; uint32 app_version = 4; bytes joiner_key = 5; bytes phone_key = 6; }
message RoomGrant { enum Answer { GRANTED = 0; DECLINED = 1; } reserved 6, 8; Answer answer = 1; fixed32 invite_id = 2; fixed32 room_id = 3; string room_name = 4; bytes room_psk = 5; uint32 generation = 7; bytes sealed_key = 9; uint32 key_hour = 10; }
message RosterEvent { enum Kind { JOINED = 0; KEY_ROTATED = 1; } Kind kind = 1; uint32 node_num = 2; uint32 invited_by = 3; uint32 generation = 4; bytes phone_key = 5; }
message KeyRotation { reserved 4; fixed32 room_id = 1; uint32 generation = 2; bytes room_psk = 3; string room_name = 5; repeated fixed32 removed = 6; bytes sealed_key = 7; uint32 key_hour = 8; }
message PersonCard { string name = 1; string tag = 2; uint32 colour_slot_plus_one = 3; bytes phone_key = 4; }
message LiveLocationRequest { fixed32 room_id = 1; uint32 suggested_secs = 2; }
message Receipt { fixed32 room_id = 1; repeated fixed32 delivered = 2; repeated fixed32 read = 3; }
message SealedMessage { fixed32 room_id = 1; bytes ciphertext = 2; uint32 generation = 3; }  // ciphertext: SealedText v2, the nonce opens with the hour
```
Rules: one packet per event, except the sealed position, which is sent at the beacon interval only while the user shares; unknown fields and kinds are ignored; a control packet is never rendered as chat. Every sealed room packet asks for an acknowledgement, so the header's `want_ack` bit does not tell words from receipts, cards or positions. Most payloads are ≤ ~40 bytes; a full receipt of 40 ids is ~206 bytes sealed and still inside the 233-byte budget, which `SealedReceiptTest` asserts rather than assumes.

`SealedMessage` wraps an encoded `MeshChatControl`, so opening it yields another control message handled as if it had arrived in the clear — and opening it is itself proof the sender holds the room key. It is only believed on the slot of the room it names, or privately to us (`TrustRules.sealedPlacementOk`), and only a message sealed under the room's **current** generation counts as membership: an older key is what a removed member still holds. A `Receipt` is only believed sealed — under a room key, or phone to phone inside a `SealedDirect` — because whoever holds a radio can put bytes on a channel, or encrypt to us, under any name; one that is sealed is only recorded from the recipient of a direct message or a member of the message's room. `RosterEvent`, `PersonCard`, `RoomText`, positions and pins are only taken sealed under the room's current key; a `RosterSync` only sealed, privately, from the inviter who let us in. A `KEY_ROTATED` event is sent sealed under the key being replaced, before the sender's radio moves on, so a member who misses their own copy of the new key stops sending in the room. Relays set to `rebroadcast_mode = CORE_PORTNUMS_ONLY` drop private ports — irrelevant for group-owned infrastructure and for Group-only mode (D-1); in public-relay mode a strict public router may drop a hello on one path, flooding tries the others, and membership stays evidence-based (§5.3) so a lost hello only delays the "invited by" attribution.

#### 6.8.6 Removing a member / leaving (key rotation)

1. The initiator generates a new `room_psk` and firepit key at `generation + 1`, same `room_id`, and writes its own channel in place (same slot; no reboot). The new key is the key for the current hour, and moves on hourly like any other (security.md §3).
2. Each remaining member gets a `KeyRotation` as a PKI DM. Its `sealed_key` is the new generation's key for the hour it is sent in, sealed to that member's phone key with `key_hour` saying which hour; a member handed it later, after missing it, gets that later hour's key; the whole `MeshChatControl{ key_rotation }` travels inside a `SealedMessage` under the **old** generation's key, so only somebody who holds the room can move it on. It is accepted only from a member, privately, sealed under the current generation, and moving forward (`TrustRules.rotationAcceptable`). About 200 of the 221 bytes a PKI DM leaves, which `ProtocolContractTest` pins.
3. A member whose phone key was never learned, or who is out of range, cannot be handed the new key and is reported back as missed, to be invited again.
4. The member's radio acknowledging the handover only reports them as reached. The handover stays owed (`pending_handovers`) until their app seals something under the new key — it shares its person card as soon as it takes one — and is handed again when they are heard: up to three radio-acknowledged times, then only on proof they still seal under the old key (security.md §6).
5. Phone keys come from the join hello, the sealed `JOINED` event that introduces a newcomer, and person cards, which every phone sends even with no name chosen. They are learned on first sight and kept; only an approved join — the approver's own, or the sealed `JOINED` announcing it — replaces one.
6. The removed member keeps the old key, past messages, and hears the mesh at the radio layer; state this plainly (design §6).

#### 6.8.7 Trust display

- Room chat: "encrypted with the room key; anyone holding the key can read and could impersonate members" (2.7), plus "signed by sender" badge when `xeddsa_signed` (2.8).
- DM: "end-to-end encrypted to this node's key". Show `is_key_manually_verified` if it ever becomes true.
- Location requests are answered automatically by firmware with no prompt on the target (design accepts this; say so in the room info screen).

**Tests:** invite encode/decode vectors; HMAC/window acceptance ±2; Argon2/XChaCha round-trip with wrong-PIN failure; handshake ordering (A before B) in a two-node integration test; `invite_id` reuse detection; key-rotation overwrite by `room_id`; control-text parser fuzzing.

---

## 7. Bandwidth budget and priorities

**Byte budget (from `RadioInterface.h`, `mesh.options`, `CryptoEngine.h`):**

| Packet kind | Available for `Data.payload` | Notes |
|---|---|---|
| Channel broadcast, unsigned | 239 − ~6 (portnum, len, bitfield) = **~233** | equals `DATA_PAYLOAD_LEN` |
| Channel broadcast, signed (2.8) | ~233 − 66 = **~167** | if larger, firmware sends it unsigned (`signedDataFits`); Balanced receivers accept because it "would not have fit" |
| PKI DM | ~233 − 12 = **~221** | DMs are never signed |
| With `reply_id` / `emoji` set | subtract 5 bytes each | |

UI rule: 200-byte cap (Apple parity), soft hint at 165 on 2.8.

**Airtime (order of magnitude, LongFast SF11/BW250/CR4-5 ≈ 1.1 kbps):** a full 255-byte frame ≈ 2 s including preamble; a 50-byte text ≈ 0.6 s. Each hop repeats the frame. EU_868 duty cycle 10 % per node; periodic modules stop at 5 % own TX (§3.5). Measure on hardware with `air_util_tx`.

**Priorities used by MeshChat**

| Traffic | priority | want_ack | Source |
|---|---|---|---|
| Room text, DM text | UNSET → HIGH by firmware (or set RELIABLE if you want chat below responses) | true | `fixPriority` |
| Alert | ALERT (110) | true | python `sendAlert` |
| Waypoint | UNSET → RELIABLE (want_ack) | true | |
| Position request | UNSET → RELIABLE (want_response) | false | |
| Phone-injected own position | BACKGROUND (10) | false | never on air |
| NodeInfo hello | BACKGROUND | false | |
| Admin (local) | UNSET → HIGH | false | never on air |

**What MeshChat never does:** custom periodic traffic beyond the sealed position that replaces the firmware's own (D-2), polling for telemetry/positions, hop_limit > 3, MQTT uplink/downlink (`uplink_enabled/downlink_enabled` always false), remote admin.

---

## 8. Testing and hardware verification plan

### 8.1 Unit / contract tests (no hardware)
- Generated protobuf round-trips against the pinned tags; a test that fails if `Constants.DATA_PAYLOAD_LEN`, `MeshPacket` field numbers, or `PortNum` values change.
- Channel URL and contact URL codecs against python outputs (`getURL`, `getContactURL`), including `?add=true` in query vs fragment (Android comment: older Apple/web put it in the fragment).
- Precision truncation vs firmware `truncateCoordinate`.
- Message-status state machine with recorded Routing/QueueStatus sequences (implicit vs explicit ACK, NAK codes, timeout).
- Slot manager property tests; invite crypto vectors; control-text parser fuzzing.

### 8.2 Integration without radios
- `meshtasticd` (Linux native/Portduino) exposes the same PhoneAPI over TCP 4403; several instances with the simulated radio (Meshtasticator / `SIMULATOR_APP`) let you test handshake, admin, channels, and message flows end-to-end in CI.

### 8.3 On hardware (T-Echo, WisMesh Tag) — from design §10, plus what this guide found
| Item | How |
|---|---|
| NodeDB capacity | 2.7: `MAX_NUM_NODES = 80`; 2.8: 120 + warm store. Confirm by flooding with simulated nodes and watching `MyNodeInfo.nodedb_count` (2.8) / eviction of non-favorites |
| Battery life per live-location tier | log `battery_level` over 8 h at each tier |
| Dense-crowd range/hops | `hop_start − hop_limit` histograms per room |
| 2.8 signing stability | `xeddsa_signed` ratio on room texts; mixed 2.7/2.8 room with BALANCED policy |
| BLE: two simultaneous node sessions | Personal + Base admin; verify MTU (~247 on nRF52), long writes, reconnect after `commit_edit_settings` and after role change |
| Reboot matrix | confirm which `set_config` variants reboot on 2.7.26 and on the 2.8 release you ship against |
| Phone-GPS injection | second node must not see any packet during injection; periodic broadcast then carries the injected coordinates with the room's precision |
| Smart-broadcast defaults on 2.7 | read `Default.h` at the 2.7 tag; tune tier table |
| Rate limits | 2 s text spacing, 10 s position/waypoint/alert spacing |
| Traffic-management dedup (2.8) | stationary node's repeated identical position is not re-relayed for 5 h; liveness UI still correct |
| Range mode switch | change the slot-0 channel name via `set_channel`; verify the radio re-tunes to the new frequency slot without a reboot on 2.7.26 and on 2.8 (else use `reboot_seconds`) |

---

## 9. Open decisions (defaults chosen; owner to confirm)

| ID | Decision | Default in this guide | Alternatives / trade-off |
|---|---|---|---|
| D-1 | **Primary channel (slot 0) policy — locked 2026-09-09 as a two-mode "Range" setting.** Slot 0 carries NodeInfo/telemetry and sets the frequency slot; NodeDB admits only decodable packets | Both modes: app-wide private key (extractable → "semi-public among MeshChat users"), `position_precision = 0`, uplink/downlink off. **Group only (default):** primary name `MeshChat` → own frequency slot; only MeshChat nodes hear and relay; quietest. **Group + public relays:** primary name empty (displays as the preset name) → firmware derives the same frequency slot as the public LongFast mesh, so public nodes rebroadcast our encrypted packets; identity/battery stay private (private key), NodeDB stays clean (undecodable packets are not admitted); cost = shared airtime and our nodes relaying public traffic. Per node, carried in invites (`LoRaProfile.mesh_mode`), joiners align automatically with a notice | Stock public LongFast primary rejected: public names/battery and NodeDB pollution |
| D-2 | Join-hello transport — **locked 2026-09-09**, port corrected 2026-09-11, amended 2026-09-29 | `PortNum.PRIVATE_APP` carrying `MeshChatControl` (§6.8.5): PKI DM for join hello and live-location requests, room broadcast for roster events; one packet per event. Amended: positions and pins are sealed on this port too, and a sealed position is the one periodic message — it replaces the firmware's position broadcast, which is kept off on every channel. An earlier note said port 300; nothing ever sent on it, and the generated `PortNum` enum cannot express a value the vendored protos do not declare | Text DM with a control prefix rejected: the node treats it as a real message (T-Echo screen, buzzer, other apps show garbage) |
| D-3 | Roster trust chain propagation — **locked (default accepted 2026-09-09)** | one `JOINED` control broadcast per join | none (only the inviter knows who invited whom) |
| D-4 (withdrawn) | Link+PIN strength — **locked 2026-09-09** | 8-digit numeric PIN shown as `4821 9306`, Argon2id (64 MiB, t=3, p=1) key derivation, XChaCha20-Poly1305 payload encryption, 15-min soft expiry, single use | 6 digits rejected: a captured link can be brute-forced offline in hours because the payload holds a permanent room key |
| D-5 | `want_ack` on room messages — **locked (default accepted 2026-09-09)** | true (needed for the "reached mesh" tick; up to 3 retransmits when isolated) | false: no delivery signal at all |
| D-6 | Live-location tiers imply position-config writes that reboot the node — **locked (default accepted 2026-09-09)** | accept one reboot per tier change; start/stop via channel precision only | phone-driven cadence (more app airtime, still bounded by 10 s rate limit) |
| D-7 | Device telemetry — **locked (default accepted 2026-09-09)** | enable, 30 min interval | leave firmware default (off on 2.8) → no battery for others |
| D-8 | Room member favorites on the Personal Node — **locked (default accepted 2026-09-09)** | favorite all members (NodeDB protection) | none; risk of eviction in busy meshes |

---

## Appendix A — Enum tables (verified against protobufs master / v2.7.26)

**PortNum (subset):** `TEXT_MESSAGE_APP=1`, `POSITION_APP=3`, `NODEINFO_APP=4`, `ROUTING_APP=5`, `ADMIN_APP=6`, `TEXT_MESSAGE_COMPRESSED_APP=7`, `WAYPOINT_APP=8`, `ALERT_APP=11`, `KEY_VERIFICATION_APP=12`, `TELEMETRY_APP=67`, `TRACEROUTE_APP=70`, `NEIGHBORINFO_APP=71`, `PRIVATE_APP=256`, `MAX=511`. 2.8 additions: `MESH_BEACON_APP=37`, `PAGING_APP=38`, `LORA_OTA_APP=79`.

**MeshPacket.Priority:** `UNSET=0, MIN=1, BACKGROUND=10, DEFAULT=64, RELIABLE=70, RESPONSE=80, HIGH=100, ALERT=110, ACK=120, MAX=127`.

**Routing.Error:** `NONE=0, NO_ROUTE=1, GOT_NAK=2, TIMEOUT=3, NO_INTERFACE=4, MAX_RETRANSMIT=5, NO_CHANNEL=6, TOO_LARGE=7, NO_RESPONSE=8, DUTY_CYCLE_LIMIT=9, BAD_REQUEST=32, NOT_AUTHORIZED=33, PKI_FAILED=34, PKI_UNKNOWN_PUBKEY=35, ADMIN_BAD_SESSION_KEY=36, ADMIN_PUBLIC_KEY_UNAUTHORIZED=37, RATE_LIMIT_EXCEEDED=38, PKI_SEND_FAIL_PUBLIC_KEY=39`.

**Config.DeviceConfig.Role:** `CLIENT=0, CLIENT_MUTE=1, ROUTER=2, ROUTER_CLIENT=3 (deprecated), REPEATER=4 (deprecated), TRACKER=5, SENSOR=6, TAK=7, CLIENT_HIDDEN=8, LOST_AND_FOUND=9, TAK_TRACKER=10, ROUTER_LATE=11, CLIENT_BASE=12`.

**Channel.Role:** `DISABLED=0, PRIMARY=1, SECONDARY=2`.

**Config.BluetoothConfig.PairingMode:** `RANDOM_PIN=0, FIXED_PIN=1, NO_PIN=2`. **PositionConfig.GpsMode:** `DISABLED=0, ENABLED=1, NOT_PRESENT=2`.

**AdminMessage.ConfigType:** `DEVICE=0, POSITION=1, POWER=2, NETWORK=3, DISPLAY=4, LORA=5, BLUETOOTH=6, SECURITY=7, SESSIONKEY=8, DEVICEUI=9`. **ModuleConfigType:** `TELEMETRY_CONFIG=5` (others irrelevant).

**Position.LocSource:** `LOC_UNSET=0, LOC_MANUAL=1, LOC_INTERNAL=2, LOC_EXTERNAL=3`.

**RegionCode (subset):** `UNSET=0, US=1, EU_433=2, EU_868=3, ANZ=6, IN=10, LORA_24=13` … (2.8 adds `EU_866=29, EU_874=30, EU_917=31, EU_N_868=32, ITU*`). **ModemPreset:** `LONG_FAST=0, MEDIUM_SLOW=3, MEDIUM_FAST=4, SHORT_SLOW=5, SHORT_FAST=6, LONG_MODERATE=7, SHORT_TURBO=8, LONG_TURBO=9, …`.

**Constants:** `DATA_PAYLOAD_LEN=233`, `MAX_LORA_PAYLOAD_LEN=255`, `MESHTASTIC_HEADER_LENGTH=16`, `MESHTASTIC_PKC_OVERHEAD=12`, `XEDDSA_SIGNATURE_SIZE=64` (+2 encoded), `MAX_TO_FROM_RADIO_SIZE=512`, `NODENUM_BROADCAST=0xFFFFFFFF`, `DEFAULT_REBOOT_SECONDS=7`, `NUM_RELIABLE_RETX=3`, `NUM_RELIABLE_UNICAST_ATTEMPTS=5`, `MAX_RX_TOPHONE=8|32` (phone queue; oldest **text** is dropped when full, other packets are dropped).

## Appendix B — Packet cookbook (pseudo-protobuf; `rnd32()` = random non-zero uint32)

```text
# Handshake
ToRadio{ want_config_id: rnd32() }

# Session passkey (before admin writes)
ToRadio{ packet: MeshPacket{ to: ME, id: rnd32(), decoded: Data{ portnum: ADMIN_APP, want_response: true,
         payload: AdminMessage{ get_config_request: SESSIONKEY_CONFIG } } } }

# Create/overwrite a room in slot i
AdminMessage{ session_passkey: PK, set_channel: Channel{ index: i, role: SECONDARY,
   settings: ChannelSettings{ name: "camp", psk: <32B>, id: room_id, module_settings: ModuleSettings{ position_precision: 32 } } } }

# Disable a slot
AdminMessage{ session_passkey: PK, set_channel: Channel{ index: i, role: DISABLED, settings: ChannelSettings{} } }

# Owner
AdminMessage{ session_passkey: PK, set_owner: User{ long_name: "Sam", short_name: "SAM", is_unmessagable: false } }

# Role (reboots)
AdminMessage{ session_passkey: PK, set_config: Config{ device: DeviceConfig{ role: CLIENT_BASE, <copy other fields from snapshot> } } }
# NOTE: set_config replaces the whole sub-message; always start from the downloaded snapshot and change one field.

# Favorite / contact
AdminMessage{ session_passkey: PK, set_favorite_node: 0x12345678 }
AdminMessage{ session_passkey: PK, add_contact: SharedContact{ node_num: N, user: User{ id:"!…", long_name, short_name, public_key:<32B>, hw_model, role } } }

# Fixed position for a Base node
AdminMessage{ session_passkey: PK, set_fixed_position: Position{ latitude_i, longitude_i, altitude, time } }

# Room text
MeshPacket{ to: 0xFFFFFFFF, channel: slot, id: rnd32(), hop_limit: 3, want_ack: true, decoded: Data{ portnum: TEXT_MESSAGE_APP, payload: "hi" } }

# DM
MeshPacket{ to: PEER, channel: 0, id: rnd32(), hop_limit: 3, want_ack: true, pki_encrypted: true, public_key: <peer key>, decoded: Data{ portnum: TEXT_MESSAGE_APP, payload: "hi" } }

# Alert
MeshPacket{ to: 0xFFFFFFFF, channel: slot, id: rnd32(), hop_limit: 3, want_ack: true, priority: ALERT, decoded: Data{ portnum: ALERT_APP, payload: "Meet at gate B now" } }

# Position request
MeshPacket{ to: PEER, channel: slot, id: rnd32(), hop_limit: 3, decoded: Data{ portnum: POSITION_APP, payload: Position{}, want_response: true } }

# Waypoint / delete
MeshPacket{ to: 0xFFFFFFFF, channel: slot, id: rnd32(), hop_limit: 3, want_ack: true, decoded: Data{ portnum: WAYPOINT_APP,
   payload: Waypoint{ id: W, latitude_i, longitude_i, expire: T, locked_to: ME, name: "Tent", description: "Blue tent by the oak", icon: 0x26FA } } }
# delete: same Waypoint with expire: 1

# NodeInfo hello on a room
MeshPacket{ to: 0xFFFFFFFF, channel: slot, id: rnd32(), hop_limit: 3, priority: BACKGROUND, decoded: Data{ portnum: NODEINFO_APP, payload: <own User> } }

# Phone GPS → node (never on air)
MeshPacket{ to: ME, channel: 0, id: rnd32(), hop_limit: 0, priority: BACKGROUND, decoded: Data{ portnum: POSITION_APP, payload: Position{ latitude_i, longitude_i, altitude, time, location_source: LOC_EXTERNAL } } }

# Graceful disconnect
ToRadio{ disconnect: true }
```

`latitude_i/longitude_i` are degrees × 1e7 (`sfixed32`). `time` fields are unix seconds.

## Appendix C — Position precision bits → radius (Android `PositionPrecision.kt`)

| bits | radius (m) | bits | radius (m) |
|---|---|---|---|
| 10 | 23 345 | 15 | 730 |
| 11 | 11 673 | 16 | 365 |
| 12 | 5 836 | 17 | 182 |
| 13 | 2 918 | 18 | 91 |
| 14 | 1 459 | 19 | 46 |
| 32 | exact | 0 | not sent |

Firmware truncation (`PositionPrecision.cpp`): `truncated = (coord & (0xFFFFFFFF << (32 − bits))) + (1 << (31 − bits))` (bucket centre); precision ≥ 32 leaves coordinates untouched; 0 clears them.

## Appendix D — Glossary

- **ChUtil / AirUtilTX**: channel utilization (all traffic heard) and own transmit utilization, percent, reported in `DeviceMetrics`.
- **Implicit ACK**: hearing one's own broadcast rebroadcast; surfaces as `Routing{NONE}` from own nodenum.
- **NodeDB**: the node's in-memory/flash table of known nodes; bounded (80/120); favorites are never evicted.
- **PhoneAPI**: the ToRadio/FromRadio stream and its state machine (`PhoneAPI.cpp`).
- **PSK**: pre-shared channel key (16/32 bytes or 1-byte well-known index).
- **Session passkey**: 8-byte replay-protection token for admin messages, valid 300 s.
- **XEdDSA**: Ed25519-style signature made with the node's X25519 key (2.8 broadcasts).
