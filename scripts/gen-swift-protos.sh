#!/usr/bin/env bash
# Regenerate ios/Packages/FirepitKit/Sources/FirepitProtos from the vendored protos in ios/Packages/FirepitKit/Protos
# (meshtastic @ v2.8.0 + meshchat; see Protos/UPSTREAM.md). Generated code is committed.
# protoc and protoc-gen-swift are built from the SwiftProtobuf version pinned in FirepitKit's Package.swift, so the
# generated code always matches the runtime. The checkout and build live in refs/ (git-ignored); the first run takes
# a few minutes.
set -euo pipefail
cd "$(dirname "$0")/.."
PKG=ios/Packages/FirepitKit
VERSION=$(sed -n 's/.*swift-protobuf\.git", exact: "\([0-9.]*\)".*/\1/p' "$PKG/Package.swift")
[ -n "$VERSION" ] || { echo "SwiftProtobuf version not found in $PKG/Package.swift" >&2; exit 1; }
TOOLS="refs/swift-protobuf-$VERSION"
[ -d "$TOOLS" ] || git clone -q --depth 1 --branch "$VERSION" https://github.com/apple/swift-protobuf.git "$TOOLS"
swift build --package-path "$TOOLS" -c release --product protoc-gen-swift
swift build --package-path "$TOOLS" -c release --product protoc
BIN=$(swift build --package-path "$TOOLS" -c release --show-bin-path)
PROTOS="$PKG/Protos"
OUT="$PKG/Sources/FirepitProtos"
rm -rf "$OUT" && mkdir -p "$OUT"
# nanopb.proto is imported (deviceonly.proto) but never generated: it only annotates firmware field sizes.
(cd "$PROTOS" && ls meshtastic/*.proto meshchat/*.proto) | xargs "$BIN/protoc" \
  --plugin="protoc-gen-swift=$BIN/protoc-gen-swift" \
  --proto_path="$PROTOS" --proto_path="$TOOLS/Sources/protobuf/protobuf/src" \
  --swift_opt=Visibility=Public --swift_out="$OUT"
echo "generated $(find "$OUT" -name '*.pb.swift' | wc -l | tr -d ' ') files into $OUT with SwiftProtobuf $VERSION"
