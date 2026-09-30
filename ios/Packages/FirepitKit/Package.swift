// swift-tools-version: 6.0
// Firepit's protocol stack for iOS: the iOS side of the working Android app's core modules
// (android/core/{model,protocol,crypto} in this repository). Same protos, same rules, same tests,
// so an iPhone and an Android phone agree on every byte they exchange.
//
// Protos/ is a vendored copy of the repository's protos/ (scripts/sync-ios-protos.sh);
// Sources/FirepitProtos is generated from it (scripts/gen-swift-protos.sh) and committed.
import PackageDescription

let package = Package(
    name: "FirepitKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(
            name: "FirepitKit",
            targets: ["FirepitProtos", "FirepitModel", "FirepitProtocol", "FirepitCrypto", "FirepitTransport", "FirepitData"]
        ),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", exact: "1.38.1"),
        .package(url: "https://github.com/groue/GRDB.swift.git", exact: "7.11.1"),
    ],
    targets: [
        // Generated from Protos/ — meshtastic @ v2.8.0 and meshchat. Never edit by hand.
        .target(
            name: "FirepitProtos",
            dependencies: [.product(name: "SwiftProtobuf", package: "swift-protobuf")]
        ),
        // Domain types (android/core/model).
        .target(name: "FirepitModel"),
        // Packet builders, ACK and trust rules, slot manager, PhoneAPI session (android/core/protocol).
        .target(
            name: "FirepitProtocol",
            dependencies: ["FirepitModel", "FirepitProtos"]
        ),
        // Room and phone-to-phone sealing, invites, QR tokens (android/core/crypto).
        .target(
            name: "FirepitCrypto",
            dependencies: ["FirepitProtocol"]
        ),
        // CoreBluetooth link to the radio and the PhoneAPI connection manager (android/core/transport).
        .target(
            name: "FirepitTransport",
            dependencies: ["FirepitProtocol"]
        ),
        // Storage, keys and repositories (android/core/{database,data}): SQLite through GRDB with the Room schema,
        // Keychain and Secure Enclave for secrets.
        .target(
            name: "FirepitData",
            dependencies: [
                "FirepitModel", "FirepitProtocol", "FirepitCrypto", "FirepitTransport",
                .product(name: "GRDB", package: "GRDB.swift"),
            ]
        ),
        .testTarget(
            name: "FirepitDataTests",
            dependencies: ["FirepitData"],
            resources: [.copy("Fixtures")]
        ),
        .testTarget(
            name: "FirepitProtocolTests",
            dependencies: ["FirepitProtocol"]
        ),
        .testTarget(
            name: "FirepitCryptoTests",
            dependencies: ["FirepitCrypto"],
            // android-vectors.json: seals, keys, tokens and links produced by the Android app's own crypto
            // (scripts/check-android-interop.sh regenerates it and checks the reverse direction).
            resources: [.copy("Fixtures")]
        ),
    ]
)
