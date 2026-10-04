# Firepit (MeshChat) — UI/UX Design & Flow Verification

**Companion to:** `meshchat-app-design.md` (product design, rev 2) and `meshchat-implementation-guide.md` (protocol/firmware facts, rev 1)
**Product name:** Firepit (store, in-app title). "MeshChat" below is the internal protocol name.
**Platforms:** iOS (SwiftUI) and Android (Jetpack Compose), native, one shared design language
**Status:** UX design rev 3 — flows verified against firmware 2.7/2.8; decisions locked 2026-09-09 (§11.4, guide §9, `meshchat-v1-scope.md`). Rev 3 (2026-10-04) adds large screens, foldables and split screen (§6.11), planned as build-plan Stage 12

---

## 1. Purpose and inputs

This document defines what the user sees and does. It takes the feature set from the design doc, the constraints from the implementation guide, and the brief for this revision:

- Familiar: it should feel like WhatsApp — chats list, bubbles, group info, invite by QR/link.
- Simple: automate every setting that can be derived; expose only decisions the user must make.
- Nodes: connect one or more nodes in a simple way; a node is **Personal**, **Base**, or **Router**.
- Core loop: connect node → create or join a room → chat.
- Three tabs: **Chats** (rooms and direct), **Map**, **Settings**.
- Beautiful, minimal, elegant.
- Uses the whole screen when there is one: on a foldable, a tablet or a wide window the conversation and the map sit side by side (§6.11).

Section 11 verifies the proposed flow against firmware reality and mobile best practice, and lists the adjustments made.

---

## 2. Design principles

1. **Familiar surface, honest semantics.** Borrow WhatsApp's layout, not its promises. A LoRa mesh cannot show "online", "read", or guaranteed delivery. Every indicator in MeshChat means exactly what the radio can prove (see §7.2 and §10).
2. **Zero-config by default.** Region, names, radio profile, telemetry, primary channel, hop limit, precision, favorites: derived or fixed. The user answers at most three questions to get a working node (§8).
3. **One primary action per screen.** Chats → write; Map → share/see; Settings → manage. The rest lives behind the room info screen and long-press menus.
4. **Local-first, latency-honest.** Everything the user does appears instantly; the mesh catches up. Delays and failures are shown as states, never as spinners that block.
5. **Quiet.** No alert popups for battery, signal, or staleness. Visual indicators only. The only interrupting notification is a message, an alert, or a location request.
6. **Explain the trust model once, where it matters.** Room info explains keys and invites in two sentences; ticks explain themselves on long-press. No security lectures in the main flow.
7. **Accessible and calm.** 44 pt targets, Dynamic Type, 4.5:1 contrast, colour never the only signal, reduced motion respected, RTL-ready.

---

## 3. People and top tasks

| Who | Top tasks | Frequency |
|---|---|---|
| Group member (most users) | read/write in a room, see who is where, share my location for a while, DM someone, drop a pin | constant |
| Organiser (one or two per group) | create room, invite people (QR at the meeting point, link for late arrivals), place a Base node at camp, occasionally remove someone | setup + rare |
| Everyone, once | pair their node, set name and region | once per node |

Design consequence: the first-run flow and the room invite flow get the most polish; node administration is thorough but tucked under Settings.

---

## 4. Information architecture

### 4.1 Tabs

| Tab | Purpose | Primary action |
|---|---|---|
| **Chats** | unified list of rooms and direct chats (filter: All · Rooms · Direct) | New chat (FAB / ＋): New room · Join room · Message a member |
| **Map** | everyone by name and colour, pins, live-location control | Share my location (floating pill) |
| **Settings** | profile, nodes, rooms, notifications, map, privacy, diagnostics | — |

Three tabs sit inside both platform guidelines (HIG tab bar: 3–5; Material 3 navigation bar: 3–5). Node connection status is not a tab: it is a persistent subtitle in the Chats header and a card at the top of Settings.

On a window wide enough for two panes, Chats and Map stop taking turns and sit side by side, and the tabs give way: Settings opens from ⚙ on the chat side (§6.11).

### 4.2 Navigation map

```mermaid
flowchart LR
  subgraph Tabs
    C[Chats]
    M[Map]
    S[Settings]
  end
  C --> RC[Room chat] --> RI[Room info]
  C --> DC[Direct chat] --> MD[Member details]
  C -->|＋| NR[New room] --> INV[Invite: QR / Link]
  C -->|＋| JR[Join room: scan a QR]
  C -->|＋| NM[Message a member]
  RI --> INV
  RI --> MEM[Members] --> MD
  RI --> ROT[Remove someone / rotate key]
  M --> SL[Share location sheet]
  M --> PIN[Drop a pin]
  M --> MD
  S --> P[Profile]
  S --> N[Nodes] --> NS[Node setup wizard]
  N --> ND[Node details / admin]
  S --> R[Rooms] --> RI
  S --> NO[Notifications]
  S --> MAPS[Map & location]
  S --> PRIV[Privacy & security]
  S --> DIAG[Diagnostics]
  FR[First run] --> NS --> C
```

### 4.3 Screen inventory

| # | Screen | Type | Notes |
|---|---|---|---|
| 1 | First run / Welcome | full screen | one screen, one button |
| 2 | Permissions priming | sheet | Bluetooth, notifications, location (Android ≤ 11 needs it for BLE scan; iOS for map) |
| 3 | Node setup wizard | full screen, stepped | scan → pair → role → region + name + tag (with avatar/marker preview and duplicate hint) → applying → done |
| 4 | Chats list | tab root | filter chips, search, FAB |
| 5 | Room chat | push | bubbles, composer, header with member count |
| 6 | Direct chat | push | same as room chat, header shows node status |
| 7 | Room info | push | members, invite, location precision, mute, leave, remove |
| 8 | Invite (QR) | sheet or push | rotating QR; pending invites |
| 9 | Join room | sheet | camera scan or pasted link, PIN entry |
| 10 | Member details | sheet | identity, device, battery, signal, location, actions |
| 11 | Map | tab root | markers, pins, room filter, share pill, people sheet |
| 12 | Share location sheet | bottom sheet | room, duration, precision note, countdown |
| 13 | Pin sheet | bottom sheet | name, note, expiry, lock |
| 14 | Settings | tab root | grouped list |
| 15 | Nodes | push | personal node card, infrastructure list, add |
| 16 | Node details / admin | push | role, region, name, rooms, range mode (D-1), position, secure PIN, restart |
| 17 | Rooms (manage) | push | slots used, per-room settings |
| 18 | Notifications | push | per-room mute, alerts always on |
| 19 | Map & location | push | offline maps, units, GPS source |
| 20 | Privacy & security | push | plain-language trust model, key info |
| 21 | Diagnostics | push | mesh utilization, firmware, logs, nearby nodes |
| 22 | Wide screens (arrangement) | layout, not a screen | chat side ∥ map side, three panes from 1200, stacked when half-folded; layout menu ◫ and Settings › Appearance › Wide screens (§6.11) |

---

## 5. Core flows (with what happens under the hood)

Each flow lists the user-visible steps, the system actions (referencing the implementation guide, "IG §"), and the states the UI must handle. Verification notes call out where the flow had to bend to firmware reality.

### 5.1 First run → working Personal node

```mermaid
flowchart TD
  A["Welcome: Chat and share location without signal"] --> B["Allow Bluetooth / notifications"]
  B --> C[Scanning… list of nearby nodes]
  C --> D[Tap node → OS pairing dialog]
  D --> E{Node has a screen?}
  E -->|yes| F["Enter the 6-digit code shown on the node"]
  E -->|no| G["Enter 123456 (factory code)"]
  F --> H[Connecting… reading node]
  G --> H
  H --> I["Your name + 2-char tag (prefilled, editable) · Region (auto-detected, confirm)"]
  I --> J["Setting up your node… → node restarts (~10 s) → reconnected"]
  J --> K["Chats: empty state → Create a room · Join a room"]
```

**Steps and system actions**
1. Welcome: one sentence, one button ("Connect your node"). No carousel.
2. Permissions: explain before asking ("MeshChat talks to your node over Bluetooth"). Android 12+: `BLUETOOTH_SCAN/CONNECT`; Android ≤ 11: location permission is required for BLE scanning — explain that it is not used for tracking.
3. Scan: list advertising nodes with name, model icon (`hw_model` known after connect; before connect show generic), signal strength. Filter by the Meshtastic service UUID (IG §4.1).
4. Pair: the OS shows the PIN dialog; the app cannot enter it. Show contextual help: nodes with a screen (T-Echo) display a random 6-digit code; headless nodes (WisMesh Tag) use the factory code `123456` (IG §4.1). Offer "Secure this node" later (§5.9).
5. Handshake and config download (IG §4.1 steps 4–6). Show "Reading your node…" with a determinate progress from the config sequence.
6. Name and **tag**: the full name is prefilled from the device account/contact card. The tag is the **two-character** label everyone sees on your avatar and map marker (stored in Meshtastic `short_name`; MeshChat fixes it at exactly two characters so every marker reads the same — letters, digits, or two characters of any script that fit the field's 4 bytes; CJK and emoji allow one). The proposal is first-name + last-name initials when a last name is known ("Sarmad Jari" → SJ), otherwise the first two letters; it is shown as its own editable field with a live avatar + marker preview, so "Sarmad" and "Sara" do not both end up as "SA". If the tag matches a known member's tag the field hints alternatives ("SA is Sara's tag in Camp — try SJ or SD"); the user can always type their own. Editable later in Settings › Profile (a `set_owner` write, no restart). Region: auto-detected from the phone's country (SIM/locale, GPS if granted) and shown as "Europe · 868 MHz" with **Confirm**; a picker is one tap away. The node cannot transmit until region is set (IG §6.7 step 2), so this step cannot be skipped — but it is one tap.
7. Apply in one transaction (IG §6.7): region, role `CLIENT`, owner, telemetry on, primary channel policy, position "off" profile. Show a checklist: "Writing settings ✓ · Node restarting (about 10 s) ⟳ · Reconnecting ⟳ · Done". The BLE link **will** drop after commit (IG §6.7 reboot matrix); this is expected, not an error.
8. Land on Chats with the empty state.

**States:** no nodes found (tips: power on, hold closer, already connected to another phone?); pairing cancelled; pairing refused (node is connected to another phone — "only one phone at a time"); region unknown (picker); reconnect timeout after reboot (retry; keep settings queued).

**Verification:** flow matches "connect node → define type → room → chat". First run deliberately assumes **Personal**; Base/Router are added from Settings because they need a different connection model (on demand) and extra questions (fixed position). See §11.2.

### 5.2 Create a room and invite

1. Chats ＋ → **New room** → name (counter shows bytes left; limit 11 UTF-8 bytes, IG §3.2) → icon (one of eight fixed room icons: tent, trail, car, music, flag, house, star, heart — default tent; U-5) → Create. No precision choice: every room shares precise location, because finding each other in a crowd is the point (U-2).
2. System: generate room id + 32-byte key, pick the lowest free slot (1–7), write the channel (no reboot), mark room in DB (IG §6.1). If 7 rooms exist the button is disabled with "You're in 7 rooms — leave one to create another".
3. Land directly on **Invite**: rotating QR (8 s ring, IG §6.8.3) with "Show this to people next to you". There is no share link (IG §6.8.4). The room chat sits behind a "Done" button.
4. Room chat opens with a system chip: "You created Camp · Invite people from room info".

**States:** camera-less devices (link only); QR expired → auto-refreshes (never an error on the inviter's side); pending link invites listed in room info with pending / joined / expired / reused.

### 5.3 Join a room

1. Entry points: Chats ＋ → **Join room** (scan) · opening a `firepit://` or universal link · pasting a link.
2. Scan QR → "Join Camp? Invited by Sam" → Join. Link: PIN field (8 digits, numeric keypad) → Join.
3. System: validate window/token or decrypt with PIN; check expiry (soft); align radio profile and Range mode (Group only / Group + public relays) if they differ (may restart node — shown); write the channel; run the handshake: NodeInfo hello on the room, then the join DM to the inviter (IG §6.8.5). Add inviter as contact; favorite members.
4. Room chat opens with "You joined Camp · invited by Sam". Members appear as their NodeInfo arrives (avatar placeholder "?" until then).

**States:** no node connected yet → keep the invite, run the node wizard first, then auto-continue ("Setting up your node first, then joining Camp"); QR older than the tolerance window → "This code expired — ask Sam to show it again"; wrong PIN → "PIN doesn't match" (no lockout, but Argon2 makes each try ~0.5 s); link expired → "Ask for a new invite"; room already present (key rotation) → "Camp's key was updated — joining with the new key".

**Verification:** join needs the joiner's node connected and set up; the flow enforces it without dropping the invite. The join handshake ordering (hello before DM) is a firmware requirement (IG §3.3), invisible to the user.

### 5.4 Chat in a room

- Composer: `[＋] [Message] [⚡] [➤]`. ＋ opens: Drop a pin · Request someone's location · Share my location · Send alert. ⚡ opens quick replies (chips; editable in Settings). Send is a filled circle button, enabled when text is non-empty.
- Byte counter appears at 150 bytes ("50 left"); hard stop at 200 (IG §6.2.5). On 2.8 nodes, a subtle "long messages are sent unsigned" note appears past 165 — only when the node reports signing support.
- Outgoing messages appear instantly with the clock glyph. Status glyph progression per §7.2. A sending queue spaces texts ≥ 2 s apart (IG §3.5) — the user can type freely; the app paces sends.
- Long-press message: Reply (uses native `reply_id`), Copy, Message info, Delete for me. Reply renders a quoted block like WhatsApp.
- Reactions (native `emoji` + `reply_id`): long-press a message → six fixed emoji 👍 ❤️ 😂 😮 😢 🙏, one tap, no picker. One packet each, and a reaction replaces an "ok" text, so airtime-neutral (U-1, locked).
- Date separators, day headers, system chips (joined, key rotated, live location started).
- Header: room name · "7 members" · node status line (§7.4). Tap → Room info.

**States:** no node connected (composer stays usable; banner "Not connected to your node — messages will send when reconnected"); mesh busy (banner when ChUtil > 40 % or after `DUTY_CYCLE_LIMIT`); message failed (red glyph, tap → retry / info); rate-limited (never shown — the queue handles it).

### 5.5 Direct chat

- Start from a person: tap them in a room's info (Members), or open them in Settings → Nodes and choose **Message**. A tapped notification for a direct message opens that conversation. One thread per person regardless of rooms. The empty Direct list says where to start: "No direct messages yet. To start one, tap someone in a room's info, or open them in Settings → Nodes." (Shipped 2026-10-03 on both apps; until then neither offered a way to start one.)
- Prerequisite handled silently: the peer's public key must be known to our node (IG §6.2.2). If not yet: "Waiting for Sam's node to say hello…" with the Message button disabled and an automatic NodeInfo request (once). Usually resolved within seconds after a join.
- Delivery: ✓✓ appears only when the peer's node ACKs (real delivery to the device, not to the person — the tooltip says "Delivered to Sam's node").
- Header subtitle: "T-Echo · 78 % · good signal · heard 3 min ago" (no "online").

### 5.6 Alerts

- Composer ＋ → **Send alert** → short text, preset chips ("Meet at camp now", "Need help", "Leaving now") → confirmation sheet ("Alerts interrupt everyone in Camp, even if muted") → Send.
- Rendered as an amber-bordered bubble with a bell; receivers get a high-priority notification that bypasses room mute (IG §6.2.4). Sender status like any message.

### 5.7 Share my location (live)

```mermaid
flowchart TD
  A["Map tab → Share my location pill"] --> B["Sheet: Room (active room preselected) · Duration: 15 min / 1 h / 8 h / custom"]
  B --> C{Tier profile already on node?}
  C -->|yes| D[Enable precision on the room → sharing]
  C -->|no| E["Node restarts to change update rate (~10 s)"] --> D
  D --> F["Pill: Sharing in Camp · 43 min left · Stop"]
  F -->|expires| G["Precision off → chip: Live location ended"]
```

- One room at a time, by firmware design (IG §6.3.1). Switching room in the sheet moves sharing: the sheet says "This stops sharing in Trail".
- Duration maps to a tier (minutes / hours / days). Changing tier writes position config and **restarts the node** — the sheet says so before the tap; start/stop within the same tier is instant (channel precision only).
- The pill shows the countdown; a small Map-tab badge shows sharing is on. Auto-stop persists across app restarts.
- Phone GPS fallback is automatic: when the node has no fix, the app feeds phone location to the node (never on air) and shows "Using phone GPS" in the sheet (IG §6.3.1).
- Precision follows the room's setting (Precise / Approximate) and is shown in the sheet.

### 5.8 Pins and location requests

- **Drop a pin:** long-press map or ＋ → Drop a pin → sheet: name (≤ 29 bytes), note (≤ 99 bytes), expires (1 h · 1 day · 1 week · custom), "Only I can remove it" (lock) → Send. Appears for everyone in the room; expiry shown on the pin; delete = "Remove pin" (owner only for others' devices, IG §6.3.3).
- **Request current location:** from a member sheet or ＋ → pick member. The target's node answers automatically (no prompt on their side — disclosed in Room info's privacy note). Result: a location card in chat and a marker refresh. Timeout after 3 min with "No answer yet — Sam's node may be out of range" (the firmware also throttles replies to one per 3 min, IG §6.3.2).
- **Ask to share live location:** sends a request the peer's app shows as a notification with a duration picker; starting is their decision.

### 5.9 Add a Base or Router node

1. Settings → Nodes → **Add node** → scan → pair (same as first run).
2. "What is this node?" — cards: **Personal** (this phone's radio), **Base** (stays at camp, helps everyone's messages get through), **Router** (extends range, placed high). Each card has one line of guidance and a placement tip ("central and elevated: tent pole, a held-up backpack"). Plain `ROUTER` is not offered; "Router" maps to `ROUTER_LATE`; REPEATER is deprecated (IG §6.6).
3. Base only: "Fixed position — use this phone's current location?" (sets `set_fixed_position`, no reboot). Name auto: "Camp Base", "Ridge Router".
4. Rooms: "Add to rooms" — multi-select of the user's rooms (default: all). Members of those rooms are favorited on the Base node automatically (that is what makes it *your* base, IG §6.6). Range mode is copied from the Personal node (the group\'s mode) so the new node lands on the same frequency slot.
5. Apply → node restarts → verify → **Disconnect** ("This node keeps working on its own; connect again from here to change it"). The node card shows "Last checked 2 min ago".

**States:** node reachable only near it (BLE) — the card explains physical access is needed; role change restart; a Base at camp that is out of Bluetooth range shows "Walk closer to manage".

### 5.10 Remove someone / rotate the key

Room info → Members → long-press → **Remove from room** →
1. Explanation sheet: "There's no way to remove one person from a shared key. MeshChat creates a new key for Camp and re-invites everyone else (5 people). Ali keeps old messages but won't see new ones." Buttons: Continue · Cancel.
2. New key written locally; a "key rotated" chip is broadcast once on the old key so others see "Ask Sam for a new invite" (IG §6.8.6).
3. Invite screen with a re-join checklist: each remaining member with pending / rejoined status; QR and links as usual.

### 5.11 Leave a room

Room info → Leave room → confirm ("You'll need a new invite to come back") → the channel slot is freed and later rooms shift down invisibly (IG §6.1). If it was the active location room, sharing stops (chip).

### 5.12 Reconnect and background

- Personal node: persistent connection; iOS background BLE, Android foreground service with a minimal notification ("Connected to Sam's T-Echo"). Auto-reconnect with backoff; header shows "Reconnecting…".
- On reconnect the app re-reads the node config and shows a one-line toast only if something changed outside the app ("Settings on your node changed — review in Settings › Nodes").

---

## 6. Screen specifications (wireframes)

Wireframes are low-fidelity; spacing and type follow §9.

### 6.1 Chats list

```
┌──────────────────────────────────────────┐
│ MeshChat                       🔍   ⋮    │  ← large title on iOS, top app bar on Android
│ ● Sam's T-Echo · 78%                     │  ← node status line (green/amber/grey dot)
│ [All] [Rooms] [Direct]                   │  ← filter chips
├──────────────────────────────────────────┤
│ (⛺) Camp                        12:41   │
│      Ali: On my way                 (2)  │  ← unread badge, primary colour
│ (◍) Sam                          12:20   │  ← DM: initials avatar in Sam's identity colour
│      ✓ See you at the gate               │  ← own last message with status glyph
│ (🥾) Trail                    Yesterday  │
│      Live location ended            🔕   │  ← muted glyph
├──────────────────────────────────────────┤
│                                   (＋)   │  ← FAB: New room · Join room · Message a member
│ [Chats]        [Map]        [Settings]   │
└──────────────────────────────────────────┘
```

Empty state (no rooms): illustration-free, two large buttons: **Create a room** · **Join a room**, and a one-line hint "Rooms are private groups. Invite friends with a QR code or a link."

Row anatomy: 48 dp avatar; title 16/semibold; preview 14/regular secondary; time 12; badge 20 dp. Swipe: mute / pin. No archive in v1.

### 6.2 Room chat

```
┌──────────────────────────────────────────┐
│ ‹  (⛺) Camp                         ⋮   │
│       7 members · ● connected            │
├──────────────────────────────────────────┤
│              ── Today ──                 │
│ ┌ Ali ────────────────┐                  │
│ │ On my way            │ 12:40           │
│ └──────────────────────┘                 │
│           ┌──────────────────────────┐   │
│           │ See you at the gate  12:41 ✓│ │ ← outgoing tint, status glyph
│           └──────────────────────────┘   │
│      ⟨ Sam joined · invited by you ⟩      │ ← system chip
│ ┌ Sam ─ 🔔 ALERT ──────┐                  │
│ │ Meet at camp now     │ 12:45           │ ← amber border
│ └──────────────────────┘                 │
├──────────────────────────────────────────┤
│ (＋) [ Message                ] (⚡) (➤) │
│                                  48 left │ ← byte counter, appears ≥150 bytes
└──────────────────────────────────────────┘
```

Bubble anatomy: max width 78 %; radius 16; incoming neutral surface with sender name in identity colour (rooms only); outgoing primary tint; time and status inline at the bottom right; reply quote block above text; location card = title + distance/bearing + "Open map".

### 6.3 Room info

```
┌──────────────────────────────────────────┐
│ ‹                                        │
│               (⛺)                       │
│               Camp                        │
│      7 members · precise location         │
│ [ Invite ]  [ Show QR ]  [ Mute ]        │
├──────────────────────────────────────────┤
│ Members                                  │
│ (◍) You                       Personal   │
│ (◍) Sam · invited you      T-Echo · 78%  │
│ (◍) Ali · invited by Sam   Tag · 54%     │
│ (⌂) Camp Base                   Base     │
├──────────────────────────────────────────┤
│ Pending invites                          │
│ Link · 12:31 · pending · expires in 9 min│
├──────────────────────────────────────────┤
│ Location sharing: Precise (all rooms)     │ ← info only, no setting (U-2)
│ Notifications: On ▸                      │
├──────────────────────────────────────────┤
│ Privacy                                  │
│ Anyone with Camp's key can read messages │
│ and see shared locations. Location       │
│ requests are answered automatically.     │
├──────────────────────────────────────────┤
│ Remove someone…                          │
│ Leave room                         (red) │
└──────────────────────────────────────────┘
```

Room name is immutable per key generation (the channel name is part of the encryption hash on every node); a local nickname is allowed via ⋮ → "Rename for me".

### 6.4 Invite

```
┌──────────────────────────────────────────┐
│ ‹  Invite to Camp                        │
│                                          │
│         ┌──────────────────┐             │
│         │                  │  ◔ 5 s      │ ← ring shows rotation countdown
│         │      QR CODE     │             │
│         │                  │             │
│         └──────────────────┘             │
│   Show this to people next to you.       │
│   It refreshes every few seconds.        │
│                                          │
│  ──────────── or ────────────            │
│  [ Share link ]                          │
│  PIN  4 8 2 1 · 9 3 0 6   (copy)         │
│  Send the PIN separately — a link alone  │
│  can't open the room. Expires in 15 min. │
└──────────────────────────────────────────┘
```

### 6.5 Member details (sheet)

```
┌──────────────────────────────────────────┐
│               (◍)                        │
│               Sam                        │
│         !a1b2c3d4 · T-Echo               │
│  🔋 78%   📶 ▂▄▆ good   ⇄ 1 hop   ⏱ 3 min │
│  📍 120 m NE · 2 min ago      [Open map] │
│  Invited you · in Camp, Trail            │
│  🛡 Verified signatures        (2.8 only)│
│ [ Message ] [ Request location ] [ Ask to share live ] │
└──────────────────────────────────────────┘
```

### 6.6 Map

```
┌──────────────────────────────────────────┐
│ [All rooms ▾]           (◎ me)  (⬇ tiles)│
│                                          │
│        (SA)          ⌂ Camp Base         │ ← initials markers in identity colours
│                                          │
│   (AL)·····                              │ ← stale: 50 % opacity, grey ring, "2 h"
│              📍 Tent                     │ ← pin with label
│                                          │
│ ┌──────────────────────────────────────┐ │
│ │ ● Share my location                  │ │ ← pill; while sharing: "Sharing in Camp · 43 min · Stop"
│ └──────────────────────────────────────┘ │
│ ═══ 7 people · 2 live · 1 pin ═══════════│ ← people sheet (collapsed peek)
│ [Chats]        [Map]        [Settings]   │
└──────────────────────────────────────────┘
```

People sheet rows: avatar, name, distance and bearing from me, age of last position, live/stale word. Tap → centre + member sheet. Pins listed below people.

Marker sizes, identical on both apps (dp on Android, points on iOS, so a marker is the same physical size on every phone): disc 34 across; ring 3 (4 on your own marker, in `#1B73E8`), drawn inside the disc's edge, `live` when live and `stale` otherwise; tag 14 bold, shrinking to fit 78 % of the disc; Base/Router glyph 62 % of the disc; approximate fix at 60 % opacity; heading arrow 10 × 8 just above the disc; name pill 3 below the disc, 12 semibold, padding 6 × 2, corner 6, `surface-2` with an `outline` hairline. Pins: an amber (`#F59E0B`) teardrop 28 high with a white hole, anchored at its tip, with the pin's name under it. Android draws these into bitmaps scaled by screen density (`MarkerSize` in `MapScreen.kt`); iOS draws the same numbers (`MarkerSize` in `MapAnnotations.swift`).

Marker language: personal = the person's tag (`short_name`) in identity colour with white ring, full name in the label chip below; live = solid + subtle pulse (off with reduce motion); stale = 50 % opacity, grey ring, age label; reduced precision = translucent accuracy circle; Base = house glyph, Router = antenna glyph, both in the reserved infrastructure colour; multiple bases share the glyph and differ by label. Own marker = blue dot like every map app.

### 6.7 Share location sheet

```
┌──────────────────────────────────────────┐
│ Share my location                        │
│ Room     [ Camp ▾ ]  (stops sharing in Trail) │
│ For      [15 min] [1 h] [8 h] [Custom]   │
│ Precision: Precise (room setting)        │
│ ⓘ Changing the update rate restarts your │
│   node for about 10 seconds.             │  ← shown only when a tier change is needed
│ [ Start sharing ]                        │
└──────────────────────────────────────────┘
```

### 6.8 Settings

As built (2026-10-03), Settings is one page on both apps, with the same sections in the same order — You, Radio, Notifications, Messages, Privacy, Map, Appearance, About — and the same wording. Each question is answered where it is asked: Android with fields, switches and chip rows; iOS with fields, switches and menus showing the current answer. Only Devices, Nodes, Offline areas and Dropped pins open screens of their own. The wireframe below is the original plan.

```
┌──────────────────────────────────────────┐
│ Settings                                 │
│ (SJ) Sarmad Jari              !a1b2c3d4  │ ← profile row → name + 2-char tag, avatar/marker preview
├──────────────────────────────────────────┤
│ Nodes                                    │
│ ● Sam's T-Echo   Personal · 78% · 2.7.26 │
│ ⌂ Camp Base      Base · checked 2 min ago│
│ ＋ Add node                              │
├──────────────────────────────────────────┤
│ Rooms                          3 of 7 ▸  │
│ Notifications                        ▸   │
│ Map & location                       ▸   │
│ Privacy & security                   ▸   │
│ Diagnostics                          ▸   │
│ Help & about                         ▸   │
└──────────────────────────────────────────┘
```

### 6.9 Node details / admin

```
┌──────────────────────────────────────────┐
│ ‹  Camp Base                    Connect  │ ← on-demand connect for Base/Router
│ Base · RAK WisMesh Tag · firmware 2.7.26 │
│ 🔋 54%  📶 —  last checked 2 min ago     │
├──────────────────────────────────────────┤
│ Type            Base                  ▸  │ ← changing restarts the node
│ Name            Camp Base             ▸  │
│ Region          Europe 868 MHz        ▸  │ ← restart
│ Rooms           Camp, Trail           ▸  │
│ Range           Group only            ▸  │ ← or "Group + public relays" (D-1); the group must match; may restart
│ Fixed position  Set from phone        ▸  │
│ Secure Bluetooth PIN   Factory (change)▸ │ ← restart; recommended for headless nodes
├──────────────────────────────────────────┤
│ Advanced                              ▸  │ ← hop limit (read-only 3), preset, restart node, forget node
└──────────────────────────────────────────┘
```

Every row that restarts the node says so in its detail screen and shows the checklist progress after applying.

### 6.10 Diagnostics

Mesh utilization (channel util / own TX util as two small gauges), firmware version and protocol capabilities (PKI, signing), reconnect count, nearby nodes not in any room (count only, expandable), export logs. This is the only place raw numbers live.

### 6.11 Large screens, foldables and split screen

Firepit is a conversation and a map. On a phone they take turns. On a screen wide enough for both, they sit side by side, so you can talk to the group and see where everyone is at the same time. On the cover screen of a foldable, or in half of a split screen, Firepit is the phone app you already know.

Every rule here follows the size and shape of the **window** Firepit is given, never the device model. The same rules serve an unfolded Galaxy Z Fold8 or Fold8 Ultra, a Pixel Fold, the inner screen of an iPhone Duo, a TriFold, a tablet, a desktop window, and Firepit in part of a split screen. Built in build-plan Stage 12.

#### 6.11.1 Principles

1. **The window decides, not the device.** Layout comes from the window's width and height and from the fold, if there is one. A device name or "is foldable" never appears in the code.
2. **The same app, more of it.** Two panes show two places the user already knows: Chats and Map. No new screens, and every action keeps its place and wording.
3. **Nothing is lost when the shape changes.** Folding, unfolding, rotating or resizing keeps the open conversation, the draft, the scroll position, the map's camera, open sheets and a QR scan in progress.
4. **The fold is a boundary.** When a fold separates the screen (half-folded), the split sits on it and nothing is drawn across it.
5. **The user chooses the arrangement, and Firepit remembers it.**

#### 6.11.2 Which layout, when

Sizes are in dp on Android and points on iOS, measured on the window, not the screen.

| Window | Layout | Navigation |
|---|---|---|
| Narrower than 600, or shorter than 480 | **One pane:** the phone app. Chats, Map and Settings take turns | Bottom bar (iOS tab bar). On Android, a phone in landscape (wide but short) keeps today's rail |
| 600 to 1199 wide and at least 480 tall | **Two panes:** the chat side and the map side (default), or one pane if the user chose so | No bar or rail; Settings opens from ⚙ on the chat side (§6.11.7) |
| 1200 or wider and at least 480 tall | **Three panes:** chat list, conversation, map | Same |
| Half-folded with the fold across the screen (tabletop) | **Two stacked panes:** map above, conversation below | Same |

- **Pane minimums:** the chat side needs at least 320 (the app's width floor) and the map side at least 280. If both cannot fit, for example with a large text size in a narrow window, Firepit shows one pane.
- **Below 320:** the full design holds down to 320. Between 220 (the smallest window Android allows) and 320, as in a third of a split screen, Firepit stays the one-pane phone app with less chrome. The composer's ＋ and ⚡ fold into one button, and the map's controls fold into one menu. Nothing is cut off.
- Typical windows, from the 2026 devices. These are estimates: Samsung does not publish its screen densities, Apple has not published the iPhone Duo's sizes in points (these are worked out from its screenshot sizes), and the user's display-size setting changes them all. The app always reads the real window size at run time:

| Device and window | About | Layout |
|---|---|---|
| Galaxy Z Fold8 Ultra, cover screen (6.5″, 21:9) | 410 × 960 dp | one pane |
| Galaxy Z Fold8 Ultra, inner screen (8.0″, about 10:9) | 750–860 × 835–950 dp | two panes, split on the fold |
| Galaxy Z Fold8 (the wide model), inner screen held landscape (7.6″, 4:3) | 815–930 × 615–705 dp | two panes |
| Galaxy Z Fold8, cover screen (5.5″) | 415–475 × 655–750 dp | one pane |
| Galaxy Z Flip8, half-folded | about 400 wide, two halves of about 420 | stacked: map above, chat below |
| Galaxy Z Flip8, cover screen (4.1″) | 315–360 × 350–400 dp | one pane, where the user lets apps run there |
| Galaxy Z TriFold, open (10″) | 820–1080 × 600–790 dp | two panes; three from 1200 wide |
| Pixel 11 Pro Fold, inner screen | 790–850 × 820–880 dp | two panes |
| iPhone Duo, outer screen (5.4″) | 466 × 678 pt | one pane |
| iPhone Duo, inner screen (7.6″, landscape when open) | 951 × 669 pt | two panes, split on the fold |
| iPad or tablet, full screen | 1000–1400 wide | two or three panes |
| Any of these in half of a split screen | 340–470 wide | one pane |

#### 6.11.3 The two panes

```
┌────────────────────────────┬─────────────────────────────┐
│ ‹  (⛺) Camp         ◫  ⋮  │ [Following Camp ▾] (◎) (◫) │
│       7 members · ● 78%    │                             │
├────────────────────────────┤     (SA)      ⌂ Camp Base   │
│ ┌ Ali ────────────┐        │                             │
│ │ On my way       │ 12:40  │   (AL)·····                 │
│ └─────────────────┘        │            📍 Tent          │
│        ┌───────────────┐   │                             │
│        │ See you 12:41✓│   │                             │
│        └───────────────┘   │ ┌─────────────────────────┐ │
│ 📍 Ali shared a location   │ │ ● Share my location     │ │
│    120 m NE · [Open map]   │ └─────────────────────────┘ │
├────────────────────────────┤ ═ 7 people · 2 live ═══════ │
│ (＋) [ Message      ] (➤)  │                             │
└────────────────────────────┴─────────────────────────────┘
           chat side        ┃ divider: on the fold       map side
```

- **The chat side** holds everything the Chats tab holds: the list, conversations, room info, invites and the member sheet. It navigates as on a phone: list, then conversation, then back. Its top bar adds ◫ (layout) and, on the list, ⚙ (Settings).
- **The map side** holds everything the Map tab holds: markers, pins, the room filter, ◎, offline tiles, the share pill and the people sheet, docked at the bottom of the pane. Its controls add ◫.

With three panes (1200 and wider), the list gets its own pane, 320 wide, and the conversation and the map share the rest:

```
┌───────────────┬──────────────────────────┬──────────────────────────┐
│ Chats  🔍 ◫ ⚙ │ ‹ (⛺) Camp           ⋮  │ [Following Camp ▾] (◎)  │
│ [All] [Rooms] │                          │                          │
│ (⛺) Camp   ● │   bubbles…               │   map…                   │
│ (◍) Sam       │                          │                          │
│ (🥾) Trail    │ (＋) [ Message   ] (➤)   │ ● Share my location      │
└───────────────┴──────────────────────────┴──────────────────────────┘
```

Half-folded with the fold across the screen (tabletop; a Flip8 standing on a table, or a Fold turned sideways), the top half is for looking and the bottom half for doing:

```
┌──────────────────────────────┐
│ [Following Camp ▾]      (◎)  │
│    (SA)     ⌂ Camp Base      │  ← map above the fold
│         📍 Tent              │
├──────────── fold ────────────┤
│ (⛺) Camp · 7 members     ◫  │
│ Ali: On my way        12:40  │  ← conversation and composer below
│ (＋) [ Message       ] (➤)   │
└──────────────────────────────┘
```

Typing in tabletop gives the conversation the whole screen, because a keyboard would fill the bottom half; the split returns when the keyboard closes.

#### 6.11.4 Choosing the arrangement

Three arrangements, available whenever the window is wide enough for two panes:

- **Chat and map** (default): the chat side and the map side.
- **Chat only:** the chat side across the whole window. At 600 and wider that is today's list beside the conversation.
- **Map only:** the map across the whole window, with the people sheet docked at the side.

Plus **Swap sides**, which puts the map on the other side.

Where the user chooses:

1. **The layout button ◫**, in the chat side's top bar and among the map side's controls. In one pane on a wide window, it stays in the top bar so two panes are one tap away. It opens a menu: Chat and map · Chat only · Map only, then Swap sides. The current arrangement is ticked.
2. **The divider:**
   - Drag it to share the width differently. Released, it settles on the nearest of ⅓, ½ or ⅔, or on the fold, with a light haptic as it lands.
   - Drag a pane below its minimum to close it, which chooses Chat only or Map only. The layout button brings the pane back.
   - Double-tap the divider to put it back on the fold, or in the middle when there is no fold.
3. **Settings → Appearance → Wide screens,** shown while the window is wide enough for two panes:
   - Layout: Chat and map · Chat only · Map only.
   - Map on the: Right · Left. Mirrored defaults in right-to-left languages, so the map starts on the left there.
   - Reset the divider.
   - Caption: "When the screen is wide enough for two: unfolded, a tablet, or a wide window."

What is remembered, on this device only and never sent anywhere:

- The arrangement and the map's side.
- The divider position, kept separately for upright windows and for wide ones, because a share that suits one rarely suits the other.
- While a fold separates the screen (half-folded, book posture), the divider sits on the fold and cannot be dragged; the remembered position returns when the screen lies flat.

#### 6.11.5 Folding, unfolding, rotating and resizing

| From → to | What the user sees |
|---|---|
| Folded, conversation open → unfold | That conversation on the chat side; the map beside it, following the same room. Draft and scroll position kept, and the keyboard stays up if it was |
| Folded, Map open → unfold | The map with the same camera on the map side; the chat side shows the last conversation, or the list if none was open |
| Two panes → fold | The side touched last becomes the one pane: the map if the map was touched last, otherwise the chat side. The other waits where it was left |
| Lying flat → half-folded like a book | The divider moves onto the fold |
| Two panes → tabletop | Map above, conversation below |
| Any → rotate | Same content and arrangement, with the divider position for the new shape |
| Window made narrower than two panes (split screen, desktop window) | Collapses to one pane, as when folding |
| App closed by the system in the background, then reopened | Same arrangement, conversation and map camera |

What is kept through all of these:

- The open conversation and its draft text.
- The scroll position.
- Any open sheet: member, share location or pin.
- The map's camera (centre, zoom and bearing) and its filter.
- The invite QR on screen, and a QR scan in progress: the camera restarts, the screen stays.
- The arrangement.

When only the arrangement changes, the map is moved, not rebuilt, so no tiles reload and nothing flashes. Moving to the other screen of a foldable can rebuild it on Android; it comes back on the same camera, from tiles already on the phone.

#### 6.11.6 How the two sides work together

**The map follows the open conversation.**
- With a room open, the map shows that room's members and pins. Its filter reads "Following Camp", and the other markers are left off.
- In a direct chat, the map shows that person and you.
- With only the list showing, the map uses the filter last chosen (All nodes or Our nodes).
- The filter menu can stop following, and following resumes when the user opens another conversation.

**From the chat to the map, without leaving the chat:**

| On the chat side | The map side |
|---|---|
| Location card → Open map | Flies to the point and marks it |
| A message that dropped a pin | Centres the pin and opens its sheet |
| Member sheet → Open map | Centres the person |
| Long-press a sender's name → Show on map | Centres the person (on a phone, this opens the Map tab) |

**From the map to the chat, without leaving the map:**

| On the map side | The chat side |
|---|---|
| Marker → member sheet → Message | Opens the direct chat |
| Pin sheet → Show in chat | Scrolls to the message that dropped it, when it is in a room the user is in |
| Long-press to drop a pin | The pin sheet's room defaults to the open conversation |
| Share my location | The room defaults to the open conversation |

**Each sheet opens on the side it was opened from:**
- A member sheet opened from a conversation or room info opens on the chat side; one opened from a marker opens on the map side.
- **Map side:** the pin, share-location and people sheets.
- **Chat side:** room info, invite and send alert.
- **The whole window:** step-by-step flows and anything that needs it: first run, node setup, joining by scanning a QR, and Settings (§6.11.7).

**Notifications:** tapping a message notification opens its conversation on the chat side, and the map follows.

**While sharing:** the chat side's header shows a small "Sharing · 43 min" chip in the room being shared to; the share pill stays on the map side.

#### 6.11.7 Navigation with two panes

- **No bottom bar, tab bar or rail** while two or three panes show. The places it switches between are already on screen, and a rail would take about 80 that a folding phone's inner screen cannot spare: on an 8″ inner screen split on the fold, it can push the chat side below the 320 floor.
- **Settings** opens from ⚙ in the chat list's top bar.
  - Android: across the whole window, with its own list beside the detail as today, and back returns to the two panes.
  - iOS: a sheet, at full height on the iPhone Duo's inner screen and as a form sheet on iPad.
- **Back** (Android back gesture, iOS edge swipe) acts on the side touched last. In a conversation it returns to the list; on the map it closes the open sheet. Android's predictive back animates within that pane.
- **In Chat only or Map only on a wide window,** the rail (Android) or tab bar (iOS) returns as it is today, with ◫ to go back to two panes.

#### 6.11.8 Keyboard, fold and edges

- **The keyboard belongs to the chat side.** It lifts the composer. The map behind it is covered, not squeezed, so it never jumps or redraws when the keyboard opens.
- **Nothing sits on a separating fold.** Android reads the fold's position from the window. On the iPhone Duo, iOS 27.1 reports the fold and camera areas, and controls keep clear of both.
- **Edge to edge.** The map runs under the system bars and the camera cut-out while its controls stay in the safe area. The chat side keeps its usual insets.
  - On the iPhone Duo, iOS may put toolbars along the side of the screen. Firepit uses standard toolbars so the system can place them.
  - In Split View, each app's controls sit on its outer edge, so the safe area differs on each side.
- **Half-folded,** controls belong in the bottom half and reading in the top, which is Apple's guidance and the tabletop arrangement above.

#### 6.11.9 Split screen, windows, mouse and keyboard

- **In part of a split screen** (Android, iPhone Duo, iPad), Firepit has a phone-sized window, so it shows one pane: exactly the phone app.
- **Freely resized windows** (Samsung DeX, Android desktop windowing, iPad windows):
  - The layout changes at the same thresholds while the window is resized.
  - The map is not redrawn on every frame of a live resize; it settles when the resize ends.
- **One Firepit window at a time** in this stage. A second window would need its own radio session, and a radio talks to only one phone app at a time.
- **Mouse, trackpad and keyboard:**
  - Rows and buttons show hover.
  - A right-click opens the long-press menu.
  - The scroll wheel zooms the map.
  - Enter sends; Shift+Enter starts a new line.
  - Esc closes the top sheet or menu.
  - Ctrl+F (⌘F on iPad) searches the chats.
  - Tab moves between the two sides.

#### 6.11.10 Accessibility and right-to-left

- **Regions:** each side is a labelled region, "Chat" and "Map". Focus moves through one side, then the other, in reading order.
- **The divider** is an adjustable control: "Divider. Drag to give the chat or the map more room." Its actions are "Make the chat wider", "Make the map wider", "Swap sides" and "Close the map".
- **Text size** never changes the layout thresholds. If the chat side would fall below its minimum at the user's text size, Firepit uses one pane.
- **Right-to-left:** the chat side sits at the start edge (the right) and the map at the end edge by default. "Map on the right/left" always names the physical side.
- **Reduce Motion:** pane changes cross-fade instead of sliding.

#### 6.11.11 Visual details

- **Divider:** a 1 hairline in `outline`, full height. Its handle is a 4 × 48 pill in `text-2` at 50 %, centred, with a 48-wide touch target that overlaps both panes. The handle is hidden while the divider is locked to a separating fold.
- **Panes:** no gutter between them, so the map runs right up to the divider. On a device with a physical gap between its two screens, the gap is `surface`.
- **Top bars:** each pane keeps its own.
  - Chat list: 🔍 · ◫ · ⚙.
  - Conversation: ◫ before ⋮.
  - Map side: filter chip · ◎ · ⬇ along the top, with ◫ in the top corner under the map's ⋮. Stacking the two keeps the top row free for notices and Show everyone on a map side only 280 wide.
- **The layout glyph** is a split-view symbol from each platform's chrome icons (§9.6): two panes outlined in Firepit's 24 dp outlined set on Android, SF Symbols' `rectangle.split.2x1` on iOS.
- **Motion:** changing arrangement moves the panes over 250 ms (emphasised deceleration). The map pane takes its new size once, at the end. While the divider is dragged, it follows the finger 1:1.
- **No new colours.** The divider is `outline` and the handle `text-2`.

#### 6.11.12 Not in this stage

- More than one Firepit window.
- Drag and drop between apps.
- A purpose-built Flip cover-screen layout: it gets the one-pane app where the user lets apps run there.
- Picture-in-picture.
- A separate layout for external monitors: they are desktop windows.

---

## 7. Components and states

### 7.1 Identity: avatar and colour

- Each person gets one identity colour derived from their node number (stable across devices and sessions), used for the avatar background, sender name in rooms, and the map marker. Palette of 12 accessible hues (§9.1).
- The label inside the avatar and marker is the person's **tag** (`User.short_name`, exactly two characters in MeshChat), chosen by the person at first run and editable in Profile — never silently derived. Colour comes from the node, the tag from the person; together they disambiguate "Sarmad Jari" (SJ, amber) from "Sara" (SA, teal). Tags from nodes set up with other Meshtastic clients may be 3–4 characters: render them at a smaller size, never truncate. The full name is always shown under map markers and in list rows, so even identical tags resolve. When a chosen tag collides with a tag already seen in the user's rooms, the app hints alternatives (§5.1 step 6); it cannot enforce global uniqueness because tags are set per node.
- Rooms use one of eight fixed icons (tent, trail, car, music, flag, house, star, heart; default tent) drawn in the primary colour on the outgoing-bubble tint — no emoji, which render inconsistently across platforms (U-5).
- Infrastructure nodes use the reserved slate colour with a house/antenna glyph.
- Unknown name → "?" avatar with the identity colour, and the `!id` as name until NodeInfo arrives.

### 7.2 Message status glyphs (the honest ticks)

```mermaid
stateDiagram-v2
  [*] --> Queued: user taps send
  Queued --> SentToNode: QueueStatus ok
  Queued --> Failed: QueueStatus error / rate limit / duty cycle
  SentToNode --> ReachedMesh: Routing NONE from own node (implicit ACK)
  SentToNode --> Unheard: Routing MAX_RETRANSMIT
  SentToNode --> Delivered: Routing NONE from peer (DM only)
  ReachedMesh --> Delivered: Routing NONE from peer (DM only)
  SentToNode --> Unknown: 120 s without a routing packet
```

| State | Glyph | VoiceOver / tooltip | Rooms | DMs |
|---|---|---|---|---|
| Queued | ◷ clock, grey | "Sending" | ✓ | ✓ |
| Sent to node | ✓ grey | "Sent to your node" | ✓ | ✓ |
| Reached mesh | ✓ green (primary) | "Heard by at least one node" | ✓ (final) | ✓ |
| Delivered | ✓✓ green | "Delivered to Sam's node" | — | ✓ (final) |
| Unheard | ✓ grey + "no one heard" text on info | "No node heard this" | ✓ | ✓ |
| Failed | ⚠ red | "Failed — tap to retry" | ✓ | ✓ |
| Unknown | ✓ grey | "Sent to your node" | ✓ | ✓ |

Glyphs are stroke icons; the state is carried by colour **and** by the tooltip/label, never by colour alone.

No blue ticks, no "read", no typing indicator. Message info (long-press) shows the timeline, hops, SNR, and on 2.8 "Signed · verified" when the packet carried a verified signature.

### 7.3 Presence

Replace "online/last seen" with **heard**: "heard 3 min ago" from the node's `last_heard`. Never say "offline". Battery and signal are shown as small glyphs, never as alerts.

### 7.4 Node status line (Chats header, Settings card)

| State | Dot | Text |
|---|---|---|
| Connected | green | "Sam's T-Echo · 78 %" |
| Reconnecting | amber | "Reconnecting to your node…" |
| Restarting | amber, spinner | "Node restarting (about 10 s)" |
| Not connected | grey | "Not connected — tap to connect" |
| Mesh busy | amber banner below header | "Mesh is busy — messages may take longer" |

### 7.5 Banners, toasts, chips

- Banners (persistent, dismiss on resolve): not connected, mesh busy, room key rotated.
- Toasts (3 s): copied, pin removed, settings applied.
- System chips (in-chat, centred): joined, key rotated, live location started/ended, "Ask Sam for a new invite".

### 7.6 Empty and error states (copy)

| Where | Copy |
|---|---|
| Chats empty | "No rooms yet. Create one or join with a QR code or link." |
| Map, no positions | "No locations yet. Share yours or ask someone for theirs." |
| Scan, nothing found | "No nodes found. Make sure the node is on and not connected to another phone." |
| Join, QR expired | "This code expired. Ask Sam to show it again." |
| Join, link expired | "This invite expired. Ask for a new one." |
| Join, wrong PIN | "PIN doesn't match." |
| DM, no key yet | "Waiting for Sam's node to say hello…" |
| Rooms full | "You're in 7 rooms. Leave one to create or join another." |
| Region unset (detected on reconnect) | "Your node needs a region before it can send. Set it now." |

---

## 8. Automation and defaults (what the user never configures)

| Setting | MeshChat default | How derived / applied | User-visible? |
|---|---|---|---|
| LoRa region | phone country → region code; confirm once | SIM/locale/GPS; `set_config(lora)` (restart) | one confirm tap; editable in Node details |
| Modem preset | LongFast (compatible across 2.7/2.8); joiners align to the group's profile from the invite | `set_config(lora)` only when different | Advanced only |
| Frequency slot, hop limit, tx power | firmware defaults; hop limit 3 | untouched | Advanced (read-only hop limit) |
| Primary channel (slot 0) / Range mode | app-wide private key, precision 0. **Group only** (default): primary named `MeshChat`, own frequency slot. **Group + public relays**: empty primary name → same frequency slot as the public LongFast mesh, public nodes relay our encrypted traffic; identity and battery stay private either way. Invites carry the mode; joiners align with a notice (IG §6.1, D-1) | written at setup / on switch | one row: Settings › Nodes › Range |
| Owner name / tag | device account name; tag = first + last initials proposed (SJ), user confirms or types their own (exactly 2 chars) with a duplicate hint against known members | `set_owner` (no restart; triggers a NodeInfo refresh) | prefilled; editable at first run and in Profile |
| Role | Personal on first run | `CLIENT`; Base → `CLIENT_BASE`; Router → `ROUTER_LATE` | card choice for added nodes |
| Device telemetry | on, every 30 min | `set_module_config(telemetry)` | never |
| Position profile | "off" tiers at setup; tier per live-sharing duration | `set_config(position)` on tier change | shown as "node restarts" note |
| Room position precision | always Precise (32) — U-2 | per-room channel setting; carried in invites | never (Room info shows "Precise" as information) |
| Favorites | all room members on Personal; members + infra on Base/Router | `set_favorite_node` | never |
| Infra nodes unmessagable | yes | `set_owner(is_unmessagable)` | never |
| Fixed position (Base) | from phone GPS at setup | `set_fixed_position` | one yes/no |
| Node time | synced from phone on connect when the node has no GPS time | `set_time_only` | never |
| Bluetooth PIN (headless nodes) | factory until "Secure this node" | `set_config(bluetooth{FIXED_PIN, random})`, PIN stored in app and shown on demand | suggested once, one tap |
| Offline map tiles | auto-download ~15 km around current location on room creation/join when on Wi-Fi/cellular; prompt on metered | MapLibre offline packs | one prompt |
| Notifications | rooms on, DMs on, alerts always | — | per-room mute |
| Quick replies | 5 defaults | app storage | editable list |

Advanced settings exist (preset, restart node, forget node, export logs) but are collapsed under "Advanced" and never required.

---

## 9. Visual design system

### 9.1 Colour

Tokens (light / dark). **"Ember" palette**: a terracotta primary with warm neutrals — campfire and canvas rather than messenger green, so MeshChat is recognisably its own thing next to WhatsApp (green), Telegram/Signal/Messenger (blue) and Viber/Discord (purple). Status colours stay conventional (green live, gold warn, crimson danger, slate infrastructure) so brand and status never compete.

| Token | Light | Dark | Use |
|---|---|---|---|
| `primary` | `#C2410C` | `#F0875A` | send button, FAB, badges, links, outgoing tint base |
| `on-primary` | `#FFFFFF` | `#3B1400` | text/icons on primary |
| `bubble-out` | `#FBE3D6` | `#3A2418` | outgoing bubbles |
| `bubble-in` | `#FFFFFF` | `#24211E` | incoming bubbles |
| `surface` | `#FAF7F3` | `#141210` | backgrounds |
| `surface-2` | `#FFFFFF` | `#1E1B18` | cards, sheets, bars |
| `text` | `#1A1614` | `#F1ECE7` | 4.5:1+ on surfaces |
| `text-2` | `#6B625C` | `#A39C95` | secondary |
| `outline` | `#E8E0D9` | `#2E2926` | dividers, strokes |
| `live` | `#2BB673` | `#4ED69A` | live markers, connected dot |
| `stale` | `#A39E98` | `#6F6963` | stale markers, unknown |
| `warn` | `#9A6B00` | `#F2C94C` | alerts, mesh busy, restarting (deep gold, distinct from the terracotta primary) |
| `danger` | `#C62B4A` | `#F27D8E` | failed, leave/remove (crimson, distinct from primary) |
| `infra` | `#4A5B8C` | `#93A6DF` | Base/Router markers and tags |
| `identity[0..11]` | 12 hues, S 50 %, L 42 % (light) / L 64 % (dark) | — | avatars, sender names, markers |
| map basemap (literal) | `#EEEAE4` ground, `#FFFFFF` roads, `#D5E5F0` water, `#D6E3CF` park | `#1C1A17`, `#2C2925`, `#1E2C38`, `#22301F` | Map tab only |

The QR card stays white with dark modules in both themes (scannability beats theming).

Component colour rules that the first Figma pass got wrong (recorded so code does not repeat them):
- **Callouts** (restart warning, mesh busy): fill = `warn` at 14 % (light) / 18 % (dark) over `surface-2`; text = `text`; icon = `warn`. Never a solid `warn` fill behind body text — contrast fails in both themes.
- **Anything on a `primary` surface** (buttons, FAB, send, badges, selected chips): text **and icons** use `on-primary` — white in light, `#3B1400` in dark. Icons must follow the same token as the label, never a hard-coded white.
- **Identity and infrastructure fills** (avatars, map markers): the tag and the Base/Router glyph use `on-primary` as well, as the dark Figma frames do — white on the light-theme hues, `#3B1400` on the lighter dark-theme hues (white there falls below 3:1).
- Icons elsewhere inherit the colour of the adjacent text token (`text`, `text-2`, `primary`), so a theme switch recolours them automatically.

Rules: colour is never the only signal (glyph + text everywhere); identity colours are tested for 3:1 against both surfaces; the map uses a desaturated basemap so markers dominate.

### 9.2 Typography

System fonts (SF Pro / Roboto), Dynamic Type and font scaling honoured.

| Role | Size/line | Weight |
|---|---|---|
| Large title | 28/34 | bold |
| Title | 20/26 | semibold |
| Body (messages) | 16/22 | regular |
| Secondary | 14/20 | regular |
| Caption (time, status) | 12/16 | regular |
| Mono (node ids, PIN) | 14/20 | medium, tabular digits |

### 9.3 Layout and components

- Spacing scale 4/8/12/16/24/32; screen margins 16; list rows 64–72 dp; bubbles padding 10×14; radius 16 (bubbles), 12 (cards), 24 (sheets), full (pills, FAB).
- Panes (§6.11): chat side at least 320 wide, map side at least 280, chat list pane 320 in three panes; no gutter on a flat screen; divider a 1 hairline in `outline`, handle 4 × 48 in `text-2` at 50 % with a 48-wide touch target; divider settles at ⅓ · ½ · ⅔ or on the fold.
- Platform-native controls: iOS tab bar, navigation stack, sheets with grabber; Android Material 3 navigation bar, top app bar, bottom sheets, FAB. Shared visual tokens; no cross-platform lookalikes.
- Icons: interface chrome uses each platform's symbols (SF Symbols on iOS; Firepit's outlined 24 dp set on Android), with the same metaphor on both — Settings is a gear, Chats a single bubble. Glyphs that people compare between phones are the same drawing on both: the eight room icons, the Personal/Base/Router role glyphs, and the map pin. Android's vector drawables are the source; `scripts/sync-ios-glyphs.py` writes them into the iOS asset catalogue. The delivery ticks are drawn the same way on both, by hand.

### 9.4 Motion and haptics

- Message send: bubble slides in from the composer (150 ms, ease-out); status glyph cross-fades.
- QR: ring depletes over 8 s; regenerate with a 120 ms cross-fade (no flash).
- Map markers: 1.6 s soft pulse for live positions; disabled with Reduce Motion.
- Haptics: light impact on send and on successful join; none for status changes.

### 9.5 Accessibility and localisation

- Minimum 44×44 pt targets; focus order matches visual order; all glyphs have labels (§7.2 tooltips are the labels).
- Contrast ≥ 4.5:1 text, ≥ 3:1 UI; identity colours never carry meaning alone (name and initials are always present).
- Dynamic Type up to accessibility sizes: bubbles reflow; the composer grows to 5 lines.
- RTL mirrored layouts; byte counters count UTF-8 bytes, so non-Latin names show the real remaining budget.
- Screen readers announce node status changes politely (no interruptions).

### 9.6 One design language, two native dialects (iOS ↔ Android)

The tokens (§9.1), type ramp (§9.2), icon set, copy (§10) and every flow are identical on both platforms. What differs is the *component vocabulary*: iOS follows the Human Interface Guidelines, Android follows Material 3. Never port one platform's controls to the other. Reference renders: `design/ios/firepit-ios-ui.pdf` (Figma) and `design/android/firepit-android-ui.pdf` (rendered from Compose screens on an emulator; page order tokens, chats, room chat, room info, invite, join, map, share location — light then dark).

| Element | iOS (HIG) | Android (Material 3) |
|---|---|---|
| Tabs | Tab bar, SF Symbols, active tint `primary` | Navigation bar with pill indicator (`primaryContainer` pill, `primary` icon/label) |
| Screen title | Large title collapsing into the nav bar | Top app bar; large title only on Chats (`headlineMedium` bold) |
| Back | Chevron + previous title, edge swipe | Arrow-left icon button, system back gesture; no "Back" label |
| Primary action on a list | "＋" bar button / bottom pill | Floating action button (bottom-end); extended FAB for "Share my location" on the map |
| Filters (All · Rooms · Direct) | Segmented-style pills | Material filter chips (`primary` when selected) |
| Lists | Grouped inset lists, chevron disclosure | Cards with `ListItem` rows, `HorizontalDivider`, chevron only for navigation rows |
| Toggles | UISwitch | Material `Switch` (`primary` track) |
| Modal sheets (Join, Share location) | Sheet with grabber, 24 pt corners | Modal bottom sheet with drag handle, 28 dp corners, scrim 45 % |
| Dialog-level confirmations | Alert | Material dialog |
| Buttons | Filled (14 pt radius) · bordered · plain | Filled · outlined · text (M3 full-radius) |
| Text field / composer | Rounded field, send button in tint | Same pill field; send = `FilledIconButton`; ＋ and ⚡ as icon buttons |
| Status bar / home indicator | System | System (edge-to-edge, gesture nav) |
| Fonts | SF Pro (Dynamic Type) | Roboto / system (sp scaling) |
| Haptics | UIFeedbackGenerator light on send | `HapticFeedbackType` light on send |
| Icons | SF Symbols for chrome; room, role and pin glyphs are Android's drawings (`Glyphs` asset set) | Firepit's 24 dp outlined set (vector drawables) |
| Ripple / highlight | Highlight on press | Material ripple (do not disable) |
| Map controls | Pill + round buttons over the map | Assist chip + small FABs; extended FAB for sharing; sheet peek with drag handle |
| Large screens / foldables | Size classes and the window's size: chat side ∥ map side on the iPhone Duo's inner screen and iPad, one pane on the outer screen; custom split with a draggable divider, sheets for Settings; iOS 27.1 fold and camera areas kept clear (§6.11) | Window size classes and posture: chat side ∥ map side from 600 wide, three panes from 1200, stacked in tabletop; pane expansion with a drag handle; split on the hinge (§6.11) |

Rules: platform-native navigation and gestures always win over visual parity; colour, spacing scale, radii for our own components (bubbles 18, cards 12), copy and behaviour never diverge. Bubble shapes: iOS 16 pt uniform; Android 18 dp with a 4 dp "tail" corner on the sender side — both acceptable expressions of the same message row. M3 gotcha: never map `surfaceVariant` to a colour also used as a container, or `contentColorFor` silently returns `onSurfaceVariant` (grey text); set `contentColor` explicitly on surface-2 containers.

---

## 10. Microcopy guide (honesty rules)

| Instead of | Say |
|---|---|
| Delivered (rooms) | Heard by the mesh |
| Read | (never) |
| Online / offline | Heard 3 min ago |
| Sending failed, retry | No node heard this · Tap to retry |
| Location shared | Location sent to Camp |
| Kick / ban | Remove — creates a new key and re-invites everyone |
| Encrypted end-to-end (rooms) | Encrypted with Camp's key — anyone with the key can read |
| Encrypted end-to-end (DMs) | Encrypted to Sam's node |
| Approve request | (there is none) Invite = access |
| Repeater | Router |
| Restart required | Your node restarts for about 10 seconds |

---

## 11. Verification

### 11.1 Proposed flow vs. firmware reality

| Proposed | Verdict | Why | Adjustment made |
|---|---|---|---|
| WhatsApp-familiar chat UI | PASS with semantics change | Mesh cannot prove read/online; group delivery is unprovable (IG §2.3) | Honest ticks (§7.2), "heard" presence (§7.3), no typing indicators |
| Automate settings | PASS | Region is the only mandatory human decision; everything else derivable | Automation table (§8); region confirm is one tap |
| Connect one or more nodes simply; choose personal / base / router | PASS with split | Personal is persistent BLE; Base/Router are on-demand admin sessions; role change reboots; region needed first; REPEATER deprecated (IG §4.3, §6.6, §6.7) | First run = Personal only; Base/Router via Settings › Add node with fixed-position and rooms questions; "Router" = ROUTER_LATE |
| Create or join room, then chat | PASS | Channel writes need no reboot; 7-room cap; joining needs a connected, provisioned node; join handshake order fixed by PKI (IG §6.1, §6.8.5) | Invite screen right after create; join keeps the invite through node setup; cap messaging |
| Tabs: Chats (group or direct) · Map · Settings | PASS | 3 tabs within HIG/M3 guidance; unified list with filters mirrors WhatsApp | Node status lives in Chats header + Settings card; invites live in room info and FAB |
| Live location sharing from the Map | PASS with disclosure | One room at a time; tier change writes position config → reboot (IG §6.3.1) | Sheet states the restart; start/stop in the same tier is instant |
| "Share location for X, auto-expire" | PASS | Auto-stop is app-side; firmware just follows precision | Persisted timer; chip on end |
| Location request in any room | PASS with disclosure | Target's firmware answers automatically; 3-min reply throttle (IG §6.3.2) | Room info privacy note; 3-min timeout copy |
| Pins with comment and expiry | PASS | Native waypoint fields; delete = expire=1 (IG §6.3.3) | Name/note byte limits in the sheet |
| Battery and signal for everyone | PASS with condition | Telemetry only on the primary channel and only if enabled (IG §6.4, D-1) | Automation enables telemetry; shared private primary policy |
| Remove someone | PASS with cost surfaced | No revocation on a shared key (design §6) | Rotate-key wizard with re-join checklist |
| QR rotation 5–10 s | PASS | App-level HMAC (IG §6.8.3) | Ring countdown; joiner tolerance ±2 windows |
| Link + PIN | **DROPPED** | A link is forwardable and a PIN travels the same way as the link; a QR has to be pointed at a camera | QR only |
| Room rename | FAIL → not offered | Channel name is part of the key hash on every member's node; no remote rename | Immutable name; local nickname only |
| Offline maps | GAP in brief → added | No internet at events; markers need tiles | Auto-download around current location; tile indicator on Map |
| Two phones sharing one node | FAIL by firmware | One PhoneAPI client per node | Scan error copy; Base/Router "Walk closer" card |

### 11.2 Feature coverage (design doc → screen)

| Design feature | Screen(s) |
|---|---|
| Create room, 7-room limit, consecutive slots | New room (§5.2), Rooms (§6.8), automatic reindex (§5.11) |
| Join via rotating QR | Invite (§6.4), Join (§5.3) |
| Group chat, DMs only among co-members | Chats, Room chat, Direct chat (§5.4–5.5) |
| Delivery indicators (two mechanisms) | Status glyphs (§7.2) |
| Canned/quick replies | ⚡ in composer, Settings › Quick replies |
| Alerts | ＋ → Send alert (§5.6) |
| Live location, one room, duration, interval scaling | Share sheet (§5.7, §6.7) |
| Location requests (current / live) | Member sheet, ＋ menu (§5.8) |
| Pin drops | Map long-press / ＋ (§5.8, §6.6) |
| Position precision per room | New room choice; Room info row |
| Live map, names, colours, live vs stale | Map (§6.6), identity system (§7.1) |
| Smart GPS source | automatic (§5.7, §8) |
| Node battery, signal quality, visual only | Member sheet, Room info rows, presence (§7.3) |
| Member list with inviter | Room info (§6.3) |
| Node types Personal/Base/Router, warnings | Add node (§5.9), Node details (§6.9) |
| Multi-node admin, BLE-only, physical access | Nodes (§6.8–6.9) |
| Security explanations in UI | Room info privacy note, Privacy & security screen, microcopy (§10) |
| Removing someone / leaving | §5.10, §5.11 |
| Bandwidth: counters, priorities, no polling | Byte counter, alert priority, mesh-busy banner; no refresh buttons anywhere |
| Talk and watch the map at once on a big screen | Chat side ∥ map side, linked: the map follows the open conversation (§6.11) |

### 11.3 Mobile best-practice checklist

| Practice | Status |
|---|---|
| 3–5 tab destinations, primary action reachable by thumb (FAB / pill at bottom) | ✓ |
| Permission priming before system prompts; graceful denial paths | ✓ (§5.1) |
| Optimistic UI, offline-first, no blocking spinners | ✓ (§2.4, §7.2) |
| Empty, loading, error, and partial states designed | ✓ (§7.6) |
| Destructive actions confirmed with consequences (leave, remove, forget node) | ✓ |
| Platform-native navigation and controls; shared tokens | ✓ (§9.3) |
| Dynamic Type, 44 pt targets, contrast, labels, reduce motion, RTL | ✓ (§9.5) |
| Dark mode from tokens, not hard-coded colours | ✓ (§9.1) |
| Background behaviour explained (Android foreground notification, iOS BLE background) | ✓ (§5.12) |
| Copy is short, specific, non-technical; technical detail behind long-press / Diagnostics | ✓ (§10, §6.10) |
| No dark patterns; no growth nags; no accounts | ✓ |
| Foldables, tablets and split screen: layout from the window, never the device; state survives fold, unfold and resize; nothing on a separating fold | Planned (§6.11, build-plan Stage 12). Android already has list ∥ conversation and hinge alignment (Stage 3) |

### 11.4 Open UX decisions

| ID | Question | Default in this doc |
|---|---|---|
| U-1 | Reactions in v1 — **locked** | Yes: six fixed emoji 👍 ❤️ 😂 😮 😢 🙏, no picker |
| U-2 | Precision choices — **locked** | None: every room is Precise (32 bits); crowd finding needs it. Approximate mode deferred |
| U-3 | Show other MeshChat users heard on the primary channel? — **locked (default)** | No (Diagnostics count only) |
| U-4 | Location cards render a static map snippet? — **locked (default)** | Text card + "Open map" (offline-safe) |
| U-5 | Room avatar — **locked** | Eight fixed icons (tent, trail, car, music, flag, house, star, heart), default tent; no emoji |
| U-6 | Duration presets — **locked** | 15 min · 1 h · 8 h · Custom (update rate follows automatically) |
| U-7 | "Secure this node" prompt timing — **locked (default)** | After first successful setup of a headless node, once |
| U-8 | Arrangement on a wide screen — **locked 2026-10-04** | Chat and map side by side; Chat only and Map only one tap away (§6.11.4) |
| U-9 | Which side the map takes — **locked 2026-10-04** | The end side: right in left-to-right languages, left in right-to-left; Swap sides changes it |
| U-10 | Navigation with two panes — **locked 2026-10-04** | No bar or rail; Settings from ⚙ on the chat side. A rail would push the chat side below 320 on an 8″ inner screen split on the fold |
| U-11 | Half-folded across the screen (tabletop) — **locked 2026-10-04** | Map above, conversation below; typing takes the whole screen until the keyboard closes |
| U-12 | Divider — **locked 2026-10-04** | On the fold if there is one, otherwise the middle; settles at ⅓ · ½ · ⅔; locked on a separating fold; remembered per upright and wide window |
| U-13 | Narrow windows — **locked 2026-10-04** | Full design down to 320; one pane with less chrome down to 220 (a third of a split screen) |
| U-14 | iPad — **locked 2026-10-04** | Yes, from the same code: the iPhone Duo's inner screen and an iPad window are the same regular-width layout |

---

## 12. Next steps

Reference renders: iOS — `design/ios/firepit-ios-ui.pdf` (Figma, https://www.figma.com/design/KhCa85jKBBx88JYrMs2wlX, one page with two rows). Android — `design/android/firepit-android-ui.pdf` (Compose renders, `design/android/screens/*.png`). Platform mapping in §9.6. Figma layout: one page with two rows. **Light** (y = 0): `00 Tokens`, `01 Chats`, `02 Room chat`, `03 Room info (full scroll)`, `04 Invite`, `05 Join room`, `06 Map`, `07 Share location`. **Dark** (y = 1300): the same eight frames suffixed `· Dark`. Light frames bind to the `MeshChat/Colors` variable collection, dark frames to `MeshChat/Colors · Dark` (two collections rather than two modes because the Starter plan allows one mode per collection; merge into modes on a Pro plan). Inter stands in for SF Pro/Roboto.

Local exports: `design/ios/firepit-ios-ui.pdf` (16 pages, exported from Figma: light 00–07 then dark 00–07) and per-frame PNGs at 2× in `design/ios/frames/` (rendered from that PDF). The pre-rename MeshChat exports and `.fig` backup are in `archive/meshchat-design-2026-09-09/`. Re-export after design changes with Figma → File → Export frames to PDF, then `pdftoppm -png -r 144` (steps in `design/README.md`). **File → Save local copy…** gives a `.fig` backup. The Starter plan's MCP call quota limits how many operations the agent can run per period; `design/figma-plugin/` is a local Figma development plugin (Plugins → Development → MeshChat fix) used to apply scripted fixes when the quota is exhausted — put new Plugin-API scripts in its `code.js` and run it. Lesson recorded there: variable-bound fills with opacity < 1 flatten to grey on PDF export; use solid literal tints for translucent surfaces in mockups.

1. Decisions are locked (2026-09-09): see §11.4, guide §9 and `meshchat-v1-scope.md`. Still open: platform order, Arabic in v1.
2. Dark polish pass: verify the warn tint (12 %) on the Share sheet callout in dark; convert repeated parts (row, bubble, chip, tab bar) into components; add the remaining screens (first run, node setup wizard, member sheet, settings, nodes, diagnostics).
3. Build a click-through prototype of first run → create room → invite → join on a second phone; test with three people who have never used Meshtastic.
4. Copy review against §10 with a non-technical reader.
5. Implement the design tokens as platform theme files before any screen code.
6. Wide screens (§6.11): add Figma frames, light and dark, for the Fold8 Ultra inner screen (two panes on the fold), the wide Fold8 inner screen held landscape, tabletop, the cover screen, half of a split screen, three panes on a tablet, and the iPhone Duo inner and outer screens. Android renders come from the same screens at those sizes.
