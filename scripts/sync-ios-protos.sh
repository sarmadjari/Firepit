#!/usr/bin/env bash
# Copy protos/ (the wire contract both apps build from) into ios/Packages/FirepitKit/Protos, then regenerate the
# Swift code. SwiftPM only compiles files inside a package, so the iOS side keeps a vendored copy; CI checks that the
# two trees stay identical. Run after any change under protos/.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f protos/meshchat/meshchat.proto ] || { echo "protos/meshchat/meshchat.proto not found: run from the Firepit repository" >&2; exit 1; }
rsync -a --delete --exclude .DS_Store protos/ ios/Packages/FirepitKit/Protos/
echo "synced protos/ into ios/Packages/FirepitKit/Protos ($(git rev-parse --short HEAD 2>/dev/null || echo 'no git'))"
exec scripts/gen-swift-protos.sh
