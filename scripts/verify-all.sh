#!/usr/bin/env bash
# verify-all.sh: build and test both apps, and check that they still agree on the wire.
# Author: Sarmad Jari
#
# Usage: scripts/verify-all.sh            run everything (takes a while the first time)
#        FIREPIT_SIMULATOR="iPhone 17" scripts/verify-all.sh   pick another simulator
#
# Needs the Mac toolchain: Xcode, JDK 25 (Homebrew openjdk@25) and the Android SDK.
set -euo pipefail
cd "$(dirname "$0")/.."
step() { printf '\n==> %s\n' "$*"; }
FIXTURE=ios/Packages/FirepitKit/Tests/FirepitCryptoTests/Fixtures/android-vectors.json
fixture_clean=false; git diff --quiet -- "$FIXTURE" && fixture_clean=true

step "protos/ and ios/Packages/FirepitKit/Protos are identical"
diff -r -x .DS_Store protos ios/Packages/FirepitKit/Protos

step "Android: build, JVM tests and lint"
(cd android && ./gradlew build)

step "iOS: FirepitKit tests on the Mac"
swift test --package-path ios/Packages/FirepitKit

step "iOS: app build and every test bundle"
xcodebuild test -project ios/Firepit.xcodeproj -scheme Firepit \
  -destination "platform=iOS Simulator,name=${FIREPIT_SIMULATOR:-iPhone 17 Pro}" -quiet

step "iOS and Android crypto agree, both directions"
scripts/check-android-interop.sh
# The interop run writes fresh random vectors into the committed fixture; put the committed one back.
$fixture_clean && git checkout -- "$FIXTURE"

step "iOS data layer runs Android's SQL"
scripts/check-dao-parity.py

printf '\nAll checks passed.\n'
