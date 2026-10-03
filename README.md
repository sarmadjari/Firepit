# Firepit

**Private group chat and a live map for when there is no phone signal.**

Firepit is for small groups (festivals, hikes, off-grid trips). Each person carries a small
[Meshtastic](https://meshtastic.org) LoRa radio paired with their phone over Bluetooth. The radios pass messages
to each other over kilometres, with no phone network, no internet, no servers and no accounts.

There are two native apps, **Android** (Kotlin) and **iPhone** (Swift). They speak exactly the same protocol, so
Android and iPhone users can share a room.

**Website:** [firepit.sarmad.no](https://firepit.sarmad.no)

---

## What it does

- **Rooms.** Up to seven private rooms on each radio. You join a room only by scanning its QR code in person,
  and the inviter approves you.
- **Chat.** Room messages, direct messages, replies and alerts. Delivery ticks never claim more than the mesh
  can prove.
- **Live map.** Share your location with one room for a set time, drop pins with a note, and save map areas
  for offline use.
- **Radios.** Set up your radio, give radios roles (Personal, Base or Router), and see battery and signal for
  everyone. Firepit checks the radio's security settings, such as a weak Bluetooth PIN, and offers fixes.
- **Privacy.** In Firepit rooms, everything people say or share is encrypted by the phones, so radios, even
  your own, only carry ciphertext. Each room is labelled with who can read it. Nothing goes to a server. The
  only internet use is downloading map tiles, and that can be switched off.

## How it works

```
 Phone ⇄ Bluetooth ⇄ Radio ⇄ LoRa, relayed by any radios in between ⇄ Radio ⇄ Bluetooth ⇄ Phone
 seals                         carry ciphertext they cannot read                         opens
```

- A Meshtastic radio has eight **channel slots**. Slot 0 is the primary channel, where radios announce
  themselves; slots 1–7 hold **rooms**.
- Each Firepit room has two keys. The **channel key** is stored on the radios. The **Firepit key** exists only
  on members' phones, and it seals every message, position and pin. A stolen or borrowed radio therefore
  cannot read the room.
- Firepit uses standard Meshtastic packets plus one message type of its own, `MeshChatControl`, which carries
  everything the phones seal. Inside the code and on the radio, the protocol is called **MeshChat**.

→ The full picture, in plain language: **[docs/architecture.md](docs/architecture.md)**.

## Status

| Area | State |
|---|---|
| Android app | ✅ Built: all v1 features except quick replies and emoji reactions |
| iPhone app | ✅ Built: a one-to-one port of the Android app, with the same features |
| Android ↔ iPhone compatibility | ✅ Proven by tests: each app opens what the other encrypts. ⏳ Not yet tested on real radios |
| Field test | ⏳ A full afternoon outdoors with several phones and radios is still to do |
| Release | ⏳ Not started: store listings, accessibility and right-to-left pass, Arabic, licence (see below) |

Known differences between the two apps, such as iOS not allowing apps to block screenshots, are listed in
[docs/architecture.md §10](docs/architecture.md#10-where-android-and-ios-differ).

## What's in this repository

| Path | What |
|---|---|
| [`android/`](android) | The Android app: the reference implementation |
| [`ios/`](ios) | The iPhone app: a one-to-one port of the Android app |
| [`protos/`](protos) | The wire contract both apps are built from: Meshtastic protobufs (pinned at v2.8.0), Firepit's own `meshchat.proto`, and the app-wide primary-channel key |
| [`docs/`](docs) | How it works, security, protocol, UX, product, build history. Start at [docs/README.md](docs/README.md) |
| [`design/`](design) | The app icon, Figma exports and reference screens |
| [`website/`](website) | The project website, [firepit.sarmad.no](https://firepit.sarmad.no): plain HTML and CSS, published by GitHub Pages |
| [`scripts/`](scripts) | Checks that keep the two apps identical, and iOS tooling |
| [`archive/`](archive) | Old prototypes and design files, kept for the record and not maintained |

## Getting started

### What you need

- **Android:** JDK 25 (on a Mac, `brew install openjdk@25`) and the Android SDK from Android Studio. The Gradle
  wrapper is included.
- **iPhone:** a Mac with Xcode 27 (Swift 6.4). The app runs on iOS 17 or newer.
- **To use it for real:** a Meshtastic radio on firmware 2.7 or newer for each phone. The project's test radios
  are the LILYGO T-Echo and the RAK WisMesh Tag ([Tag guide](docs/wismesh-tag-buttons.md)). The iPhone app's
  demo mode needs no radio.

### Android

```bash
cd android
./gradlew build                # compile, run every JVM test, lint
./gradlew :app:assembleDebug   # build the app
./gradlew :app:installDebug    # install on a phone connected over USB
```

Android 10 (API 29) or newer. Toolchain: AGP 9.4.1, Kotlin 2.4.20, Gradle 9.8 running on JDK 25, with modules
compiled to Java 17 and compileSdk 37. Versions live in `android/gradle/libs.versions.toml`; the rules for
changing them are in [CLAUDE.md](CLAUDE.md).

### iPhone

Open `ios/Firepit.xcodeproj` in Xcode and run the **Firepit** scheme, or use the command line:

```bash
# Build and test on a simulator
xcodebuild test -project ios/Firepit.xcodeproj -scheme Firepit \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro'

# Test the non-UI layers alone, on the Mac (fast)
swift test --package-path ios/Packages/FirepitKit
```

**Demo mode** (debug builds) runs the app against a pretend radio and an in-memory world: two rooms, a few people
with positions, a day and a half of messages, pins and a live share. Nothing touches real storage. Add
`-route <feature>.<screen>` to open one screen directly, for example `chat.room`, `map.everyone`, `radio.nodes`
or `settings.notifications`. Each feature lists its routes in `ios/Firepit/Features/<Feature>/<Feature>DebugRoutes.swift`.

```bash
xcrun simctl launch booted com.getfirepit.app -demo -route chat.room
```

**On your iPhone** (USB cable, Developer Mode on). Pick your team under Signing & Capabilities in Xcode and press
Run, or:

```bash
xcodebuild build -project ios/Firepit.xcodeproj -scheme Firepit -destination 'platform=iOS,name=<iPhone name>' \
  -derivedDataPath ios/build -allowProvisioningUpdates DEVELOPMENT_TEAM=<team id>
xcrun devicectl list devices    # find the phone's identifier
xcrun devicectl device install app --device <identifier> ios/build/Build/Products/Debug-iphoneos/Firepit.app
xcrun devicectl device process launch --device <identifier> --terminate-existing com.getfirepit.app
```

How the iOS code is organised:

| Part | What it holds |
|---|---|
| `ios/Packages/FirepitKit` | The non-UI layers, one target per Android `core/` module: `FirepitProtos` (generated), `FirepitModel`, `FirepitProtocol`, `FirepitCrypto` (CryptoKit, Secure Enclave), `FirepitTransport` (CoreBluetooth), `FirepitData` (GRDB running Android's database schema, Keychain) |
| `ios/Firepit/DesignSystem` | Colours, type and shared components: the port of Android's `core/designsystem` |
| `ios/Firepit/Features` | The screens: the port of Android's `app/` |

Dependencies are pinned exactly: SwiftProtobuf 1.38.1 and GRDB 7.11.1 in `ios/Packages/FirepitKit/Package.swift`,
and MapLibre Native 6.31.0 in the Xcode project. New files under `ios/Firepit/` join the app target
automatically.

## Checking your work

```bash
scripts/verify-all.sh
```

This is the full gate, run before every push. It:

1. checks that the two copies of the protobufs are identical;
2. builds the Android app and runs its tests and lint;
3. runs the iOS package tests and the app's test bundles;
4. checks that Android and iOS open each other's encrypted messages, keys and invites, in both directions;
5. checks that the iOS data layer runs Android's exact SQL.

GitHub CI runs the Android build, tests and lint, and the protobuf-copy check, on every push to `main` and every
pull request. The iOS steps need a Mac, so they run locally through `verify-all.sh`.

## Website

[firepit.sarmad.no](https://firepit.sarmad.no) is a single static page in `website/`: HTML, CSS and a few lines of
JavaScript, with no build step, no web fonts, no cookies and no third-party requests (its Content-Security-Policy
allows only its own files). It uses the apps' own colour tokens: light and dark follow the visitor's device unless
they pick one with the theme button (remembered in the browser, never in a cookie).

```bash
python3 -m http.server 8000 --directory website   # preview at http://localhost:8000
scripts/capture-site-screens.sh                    # refresh the app screenshots from the iOS demo mode
scripts/make-site-qr.py                            # remake the demo invite codes
```

The invite screen on the page is live, not a screenshot: like the app, its code refreshes every 8 seconds on the clock
and counts down to it. Each code only says hello, in one of eleven languages, so scanning it is harmless and fun.

Pushing a change under `website/` to `main` publishes it: the `Website` workflow
(`.github/workflows/pages.yml`) uploads that folder to GitHub Pages, which serves it on the custom domain. Keep its
claims in line with the documents; it describes the same features in fewer words.

## Scripts

| Script | What it does |
|---|---|
| `scripts/verify-all.sh` | The full gate above. `FIREPIT_SIMULATOR="iPhone 17"` picks another simulator |
| `scripts/check-android-interop.sh` | Runs Android's and iOS's encryption code against each other's output, both ways. Works on a scratch copy in `refs/`; rewrites the committed test-vector file |
| `scripts/check-dao-parity.py` | Checks that the iOS database code runs Android's SQL |
| `scripts/check-port-parity.py` | Checks that a Swift file declares every member of the Kotlin file it ports. Run it on each file you port |
| `scripts/sync-ios-protos.sh` | Copies `protos/` into the iOS package and regenerates the Swift code. Run it after any change under `protos/` |
| `scripts/gen-swift-protos.sh` | Regenerates the Swift protobuf code with tools built from the pinned SwiftProtobuf |
| `scripts/sync-ios-glyphs.py` | Copies the drawings both apps must show identically (room icons, radio roles, the map pin) from Android into iOS. `--check` reports drift |
| `scripts/render-ios-app-icon.swift` | Renders the iOS app icon from `design/artwork/icon-app.svg` |
| `scripts/capture-site-screens.sh` | Captures the website's app screenshots from the iOS demo mode, in light and dark, with no phone signal in the status bar |
| `scripts/make-site-qr.py` | Makes the website's demo invite codes: eleven QR codes that say hello, from English to Japanese, with Arabic marked right to left (needs `segno`) |
| `scripts/fetch-refs.sh` | Fetches the Meshtastic firmware and reference apps into `refs/` (git-ignored), to cite when firmware behaviour matters |

## Documentation

| Read | To learn |
|---|---|
| [docs/architecture.md](docs/architecture.md) | How the whole system works. **Start here** |
| [docs/security.md](docs/security.md) | What is protected, against whom, and the code and tests behind each claim |
| [docs/meshchat-ux-design.md](docs/meshchat-ux-design.md) | Screens, flows, colours, icons and wording |
| [docs/meshchat-implementation-guide.md](docs/meshchat-implementation-guide.md) | Meshtastic protocol and firmware facts, with sources |
| [docs/build-plan.md](docs/build-plan.md) | How it was built, stage by stage, and what is left |
| [docs/README.md](docs/README.md) | Every document, and which to read for what |

## Contributing

- Read [CLAUDE.md](CLAUDE.md) first. It holds the rules for everyone who changes code, people and coding agents
  alike ([AGENTS.md](AGENTS.md) points to it).
- **Android is the reference.** Change Android first, then port the change to iOS following
  [docs/ios-ui-porting-rules.md](docs/ios-ui-porting-rules.md), keeping names and behaviour identical.
- Update the relevant document in the same commit as a behaviour change.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org) with a scope, for example
  `feat(android): …`, `fix(ios): …`, `docs: …`.

## Licence

Not chosen yet. The vendored Meshtastic protobufs are GPL-3.0, which makes Firepit a derivative work, so GPL-3.0
is the likely choice. It must be settled before any public release
([build-plan.md, Open decisions](docs/build-plan.md#open-decisions)).
