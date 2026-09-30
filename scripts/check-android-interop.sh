#!/usr/bin/env bash
# Proves the iOS and Android apps agree on every sealed byte, in both directions:
#   1. the Android app's own crypto writes seals, key envelopes, direct messages, tokens and invite codes, which the
#      iOS tests open and reproduce (committed as the FirepitCryptoTests fixture android-vectors.json);
#   2. what iOS seals and encodes is opened and re-encoded by the Android app's own code.
# Runs on a scratch copy of android/ and protos/ in refs/android-interop (git-ignored); android/ itself is never touched.
# It rewrites the committed android-vectors.json fixture with fresh random vectors; commit it or restore it afterwards.
set -euo pipefail
cd "$(dirname "$0")/.."
ANDROID_REPO="${FIREPIT_ANDROID_REPO:-$PWD}"
JAVA_HOME="${FIREPIT_JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
WORK="$PWD/refs/android-interop"
export JAVA_HOME FIREPIT_VECTORS_DIR="$WORK"

rm -rf "$WORK" && mkdir -p "$WORK"
rsync -a --exclude build --exclude .gradle --exclude .kotlin --exclude .idea "$ANDROID_REPO/android" "$ANDROID_REPO/protos" "$WORK/"
cp scripts/interop/CrossPlatformVectorGen.kt scripts/interop/CrossPlatformIosVerify.kt \
  "$WORK/android/core/crypto/src/test/kotlin/com/getfirepit/core/crypto/"
gradle() { (cd "$WORK/android" && ./gradlew "$@" -Dorg.gradle.java.home="$JAVA_HOME" -q); }

echo "1/3 Android writes vectors"
gradle :core:crypto:test --tests '*CrossPlatformVectorGen*' --rerun
cp "$WORK/android-vectors.json" ios/Packages/FirepitKit/Tests/FirepitCryptoTests/Fixtures/android-vectors.json

echo "2/3 iOS opens Android's vectors and writes its own"
(cd ios/Packages/FirepitKit && FIREPIT_IOS_VECTORS_OUT="$WORK/ios-vectors.json" swift test --filter FirepitCryptoTests 2>&1 \
  | grep -E "Test run with|✘|error" )
[ -s "$WORK/ios-vectors.json" ] || { echo "iOS wrote no vectors" >&2; exit 1; }

echo "3/3 Android opens what iOS sealed"
gradle :core:crypto:test --tests '*CrossPlatformIosVerify*' --rerun
echo "iOS and Android agree, both directions ($(git -C "$ANDROID_REPO" rev-parse --short HEAD 2>/dev/null || echo 'android/'))"
