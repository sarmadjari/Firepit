#!/usr/bin/env bash
# capture-site-screens.sh: refresh the app screenshots on the website (website/assets/screens) from the iOS app's
# demo world, in light and dark, with a status bar that has no phone signal.
#
# Usage: scripts/capture-site-screens.sh            capture on the booted simulator (builds the app first)
#        FIREPIT_SIMULATOR_ID=<udid> scripts/capture-site-screens.sh
#        FIREPIT_SKIP_BUILD=1 scripts/capture-site-screens.sh   reuse the last build in $FIREPIT_DERIVED_DATA
#
# Needs Xcode and cwebp (brew install webp). Screens come from the debug-only `-demo -route <feature>.<screen>`
# launch arguments (README, Demo mode), so nothing real is shown.
set -euo pipefail
cd "$(dirname "$0")/.."
SIM="${FIREPIT_SIMULATOR_ID:-$(xcrun simctl list devices booted | grep -oE '[0-9A-F]{8}(-[0-9A-F]{4}){3}-[0-9A-F]{12}' | head -1)}"
[ -n "$SIM" ] || { echo "No booted simulator: boot one, or set FIREPIT_SIMULATOR_ID" >&2; exit 1; }
DERIVED="${FIREPIT_DERIVED_DATA:-/tmp/firepit-site-dd}"
OUT=website/assets/screens
WORK="$(mktemp -d)"
ROUTES=(chat.room map.everyone)
WIDTH=660   # 2x the largest size the site shows a phone at
command -v cwebp >/dev/null || { echo "cwebp not found: brew install webp" >&2; exit 1; }

xcrun simctl boot "$SIM" 2>/dev/null || true
if [ -z "${FIREPIT_SKIP_BUILD:-}" ]; then
  echo "==> Building the app"
  xcodebuild build -project ios/Firepit.xcodeproj -scheme Firepit -configuration Debug \
    -destination "platform=iOS Simulator,id=$SIM" -derivedDataPath "$DERIVED" -quiet
fi
xcrun simctl install "$SIM" "$DERIVED/Build/Products/Debug-iphonesimulator/Firepit.app"

# The point of the app: no bars, no Wi-Fi.
xcrun simctl status_bar "$SIM" override --time "9:41" --batteryState discharging --batteryLevel 82 \
  --cellularMode searching --cellularBars 0 --wifiMode failed --wifiBars 0 --dataNetwork hide --operatorName ""
trap 'xcrun simctl status_bar "$SIM" clear; rm -rf "$WORK"' EXIT

mkdir -p "$OUT"
for appearance in light dark; do
  xcrun simctl ui "$SIM" appearance "$appearance"
  for route in "${ROUTES[@]}"; do
    xcrun simctl terminate "$SIM" com.getfirepit.app 2>/dev/null || true
    xcrun simctl launch "$SIM" com.getfirepit.app -demo -route "$route" >/dev/null
    # The map fetches its tiles over the network; everything else is ready at once.
    case "$route" in map.*) sleep 9 ;; *) sleep 4 ;; esac
    name="${route//./-}-$appearance"
    xcrun simctl io "$SIM" screenshot "$WORK/$name.png" >/dev/null 2>&1
    sips --resampleWidth "$WIDTH" "$WORK/$name.png" --out "$WORK/$name-small.png" >/dev/null
    cwebp -quiet -q 82 -m 6 "$WORK/$name-small.png" -o "$OUT/$name.webp"
    echo "    $OUT/$name.webp"
  done
done
xcrun simctl terminate "$SIM" com.getfirepit.app 2>/dev/null || true
echo "Done."
