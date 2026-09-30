#!/usr/bin/env bash
# Capture the prototype's design gallery (all screens × light/dark) from a running emulator/device into the repository's
# design/android/screens/. Build the prototype first: (cd android && ./gradlew :app:assembleDebug).
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
OUT=../../design/android/screens; mkdir -p "$OUT"
SCREENS=(tokens chats room-chat room-info invite join map share-location)
$ADB install -r android/app/build/outputs/apk/debug/app-debug.apk >/dev/null
for theme in light dark; do
  if [ "$theme" = dark ]; then $ADB shell cmd uimode night yes >/dev/null; else $ADB shell cmd uimode night no >/dev/null; fi
  $ADB shell sleep 1
  i=0
  for s in "${SCREENS[@]}"; do
    i=$((i+1))
    $ADB shell am force-stop com.getfirepit.app
    $ADB shell am start -W -n com.getfirepit.app/.MainActivity --es design "$s" >/dev/null 2>&1
    $ADB shell sleep 2.5
    $ADB exec-out screencap -p > "$OUT/$(printf '%02d' $((i-1)))-$s-$theme.png"
    echo "captured $s $theme"
  done
done
$ADB shell cmd uimode night no >/dev/null
