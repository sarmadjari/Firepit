# Android design-gallery prototype (2026-09-09 → 2026-09-10)

The first Android code: a Compose **mock** of the eight v1 screens with fake data, built only so the screens could be
screenshotted in light and dark. It never talked to a radio. The screenshots and the PDF made from it are the Android
reference renders in `design/android/`. The real Android app is `android/` at the repository root. This one is not
maintained.

It came from `~/Projects/Firepit/android` and had never been committed anywhere before. It is kept buildable as a
record:

| Path | Was |
|---|---|
| `android/` | `~/Projects/Firepit/android` (source only: build output, `.gradle`, `.kotlin`, `.idea` and `local.properties` left out) |
| `protos/meshchat.proto` | `~/Projects/Firepit/protos/meshchat.proto`. The prototype's `meshchat-proto` module compiles `../../protos/meshchat.proto`. **This is the old, pre-contract version** (control on port 300, different package); the real one is `protos/meshchat/meshchat.proto` at the repository root |
| `scripts/capture-design.sh` | `~/Projects/Firepit/scripts/capture-design.sh`, now writing into `design/android/screens/` |

Toolchain at the time: Gradle 9.7.1 wrapper, AGP 9.4.0, Kotlin 2.4.20, package `com.getfirepit.app`.
Build: `cd archive/android-design-prototype/android && ./gradlew :app:assembleDebug`, then
`scripts/capture-design.sh` from this folder with an emulator running.

The prototype's primary channel key (`~/Projects/Firepit/protos/meshchat-primary-key.txt`) was deliberately **not**
kept. It differed from the real key in `protos/meshchat-primary-key.txt`, and nothing in the prototype used it.
