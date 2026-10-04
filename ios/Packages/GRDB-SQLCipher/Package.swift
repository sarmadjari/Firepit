// swift-tools-version:6.1
// GRDB 7.11.1 (https://github.com/groue/GRDB.swift, MIT), built on SQLCipher instead of the system's SQLite,
// following the "GRDB+SQLCipher" comments in GRDB's own Package.swift. Firepit's phone database is encrypted with
// it, as Android's is (build plan, Stage 11 Phase 5). Only the library is kept: GRDB's tests stay upstream.

import PackageDescription

let swiftSettings: [SwiftSetting] = [
    .define("SQLITE_ENABLE_FTS5"),
    .define("SQLITE_ENABLE_SNAPSHOT"),
    .define("SQLITE_HAS_CODEC"),
    .define("SQLCipher"),
]
let cSettings: [CSetting] = [.define("SQLITE_HAS_CODEC")]

let package = Package(
    name: "GRDB",
    platforms: [
        .iOS(.v13),
        .macOS(.v10_15),
    ],
    products: [
        .library(name: "GRDB", targets: ["GRDB"]),
    ],
    dependencies: [
        .package(url: "https://github.com/sqlcipher/SQLCipher.swift.git", exact: "4.19.0"),
    ],
    targets: [
        .target(
            name: "GRDBSQLCipher",
            dependencies: [.product(name: "SQLCipher", package: "SQLCipher.swift")]
        ),
        .target(
            name: "GRDB",
            dependencies: [
                .product(name: "SQLCipher", package: "SQLCipher.swift"),
                .target(name: "GRDBSQLCipher"),
            ],
            path: "GRDB",
            resources: [.copy("PrivacyInfo.xcprivacy")],
            cSettings: cSettings,
            swiftSettings: swiftSettings + [
                .enableUpcomingFeature("MemberImportVisibility"),
            ]),
    ],
    swiftLanguageModes: [.v6]
)
