#!/usr/bin/env bash
# Fetch read-only reference sources into refs/ (git-ignored) for citation while coding.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p refs && cd refs
clone() { [ -d "$2" ] || git clone -q --depth 1 ${3:+--branch "$3"} "$1" "$2"; echo "$2 → $(git -C "$2" describe --tags --always)"; }
clone https://github.com/meshtastic/firmware.git firmware-2.7 v2.7.26.54e0d8d
clone https://github.com/meshtastic/firmware.git firmware-master
clone https://github.com/meshtastic/Meshtastic-Android.git Meshtastic-Android
clone https://github.com/meshtastic/Meshtastic-Apple.git Meshtastic-Apple
clone https://github.com/meshtastic/python.git python
