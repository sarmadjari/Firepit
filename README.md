# Firepit

Private group chat and live map over Meshtastic LoRa radios, with no phone signal needed. Native Android
(Kotlin/Compose) and iOS (Swift/SwiftUI) apps in one repository, speaking one protocol. Inside the app and on
the radio, the protocol is called **MeshChat**.

| Path | What |
|---|---|
| `android/` | The Android app (reference implementation) |
| `ios/` | The iOS app, a one-to-one port of the Android app |
| `protos/` | The wire contract both apps build from: pinned Meshtastic protobufs, `meshchat/meshchat.proto`, the app-wide primary key |
| `docs/` | Scope, implementation guide, UX design, product design, build plan, security, iOS porting rules |
| `design/` | Brand, Figma exports and reference renders (see `design/README.md`) |
| `scripts/` | Cross-platform checks and iOS tooling |
| `archive/` | Not maintained: the Android design prototype, the MeshChat-era design, the 2026-09-30 reorganisation record |

Agents and contributors: read `CLAUDE.md` first.

## Check everything
```bash
scripts/verify-all.sh
```
This runs the protos-copy check, the Android build with tests and lint, the FirepitKit and Xcode tests, the
crypto interop in both directions, and DAO parity.

## Build (Android)
```bash
cd android && ./gradlew build              # compile, JVM tests, lint
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:installDebug  # on a connected phone
```
Toolchain: JDK 25 for the Gradle daemon (Homebrew `openjdk@25`; modules compile to Java 17), Gradle 9.8 wrapper,
AGP 9.4.1 with built-in Kotlin 2.4.20, compileSdk 37 / minSdk 29. The Android SDK comes from Android Studio
(`ANDROID_HOME`, `android/local.properties`). Details and version rules are in `CLAUDE.md`.

## Build (iOS)
The iOS app is a one-to-one port of the Android app in `android/`, which is the source of truth for behaviour and
wire format. Both phones talk to the same Meshtastic radios, so everything on the air is byte-identical: the
protobufs, the sealing and key exchange, and the invite format.

Open `ios/Firepit.xcodeproj` in Xcode and run the **Firepit** scheme, or from the command line:
```bash
xcodebuild build -project ios/Firepit.xcodeproj -scheme Firepit -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
xcodebuild test  -project ios/Firepit.xcodeproj -scheme Firepit -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
swift test --package-path ios/Packages/FirepitKit    # the non-UI layers, on the Mac
```
Layout: `ios/Packages/FirepitKit` holds the non-UI layers, one target per Android `core/` module:
- `FirepitProtos` (generated)
- `FirepitModel`
- `FirepitProtocol`
- `FirepitCrypto` (CryptoKit, Secure Enclave)
- `FirepitTransport` (CoreBluetooth)
- `FirepitData` (GRDB with Android's Room schema, Keychain)

The app target `ios/Firepit` holds `DesignSystem/` (port of `core/designsystem`) and `Features/` (port of `app/`),
with MapLibre for maps. Names, members and SQL match the Android code one to one, which the parity scripts below
check.

Toolchain: Xcode 27 / Swift 6.4, deployment target iOS 17, iPhone only. Dependencies are pinned exactly:
SwiftProtobuf 1.38.1 and GRDB.swift 7.11.1 in `ios/Packages/FirepitKit/Package.swift`, and MapLibre Native 6.31.0 in the
Xcode project. The project uses synchronized folders, so new files under `ios/Firepit/` join the app target without
editing the project. For a phone, pick your team under Signing & Capabilities.

Demo mode (debug builds): launch with `-demo` to run the app against a pretend radio and an in-memory world. It has
two private rooms, a few people with positions, a day and a half of messages, pins and a live share, so every screen
can be seen populated without hardware. Nothing is read from or written to real storage. Add
`-route <feature>.<screen>` to open one screen directly, for example `chat.room`, `map.main`, `radio.nodes` or
`settings.main`. Each feature lists its routes in `Features/<Feature>/<Feature>DebugRoutes.swift`.
```bash
xcrun simctl launch <simulator id> com.getfirepit.app -demo -route chat.room
```

Where iOS behaves differently from Android:
- iOS cannot block screenshots. With "Allow screenshots" off, Firepit instead hides in the app switcher and while
  the screen is recorded or mirrored.
- iOS does not show apps the phone's Bluetooth pairings, so Devices lists only the radios Firepit has found.
- iOS offers no switch to stop the keyboard learning what is typed.
- Deliberately, going back from a conversation to the list stops treating it as read. Android keeps the last room
  selected, which marks its new messages read and holds back their notifications while nobody is looking at it.

Install on a connected iPhone (USB, Developer Mode on; replace the team id with yours):
```bash
xcodebuild build -project ios/Firepit.xcodeproj -scheme Firepit -destination 'platform=iOS,name=<iPhone name>' \
  -derivedDataPath ios/build -allowProvisioningUpdates DEVELOPMENT_TEAM=<team id>
xcrun devicectl list devices    # the phone's identifier
xcrun devicectl device install app --device <identifier> ios/build/Build/Products/Debug-iphoneos/Firepit.app
xcrun devicectl device process launch --device <identifier> --terminate-existing com.getfirepit.app
```

## Scripts
- `scripts/verify-all.sh`: every check below, plus both builds and test suites, in one go.
- `scripts/check-android-interop.sh`: run the Android crypto code and the iOS crypto code against each other's test
  vectors, in both directions. It works on a scratch copy in `refs/` and rewrites the committed vectors fixture.
- `scripts/check-port-parity.py` / `scripts/check-dao-parity.py`: check that a Swift port declares every non-private
  Kotlin member, and that the iOS data layer runs Android's SQL.
- `scripts/sync-ios-protos.sh`: copy `protos/` into `ios/Packages/FirepitKit/Protos` and regenerate the Swift code.
  Run it after any change under `protos/`; CI fails if the two differ.
- `scripts/gen-swift-protos.sh`: regenerate the committed Swift code from the vendored protos with SwiftProtobuf tools
  built from the pinned version (the tool build lives in `refs/`).
- `scripts/render-ios-app-icon.swift`: render the iOS app icon from the Android launcher icon
  (`design/brand/firepit-icon.svg`). Light and dark use the same artwork, plus the one-colour mark iOS uses for tinted icons.
- `scripts/fetch-refs.sh`: reference clones (firmware, Meshtastic apps) into `refs/` (git-ignored) for citations.
