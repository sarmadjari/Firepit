import Foundation
import GRDB

/// Firepit's Room schema on iOS.
///
/// Android encrypts the database with SQLCipher under a random key the Keystore wraps, and keeps that key usable while
/// the phone is locked, "because messages arrive in a pocket" (docs/security.md). iOS keeps the same promise with Data
/// Protection: the database directory and SQLite files are `.completeUntilFirstUserAuthentication`, encrypted by the
/// Secure Enclave until the phone is first unlocked after a restart and readable afterwards, so the app can still store
/// a message when iOS relaunches it in the background for Bluetooth while the phone is locked — a stricter class could
/// not reopen the file then, and the message would be lost. The keys in the Keychain use the matching
/// `AfterFirstUnlockThisDeviceOnly`. Android's extra SQLCipher layer, which also protects a copy of the files taken
/// after first unlock, has no equivalent here yet. The directory is excluded from iCloud and device backups, matching
/// Android's `allowBackup=false`.
///
/// iOS starts at Android schema v12. Historical Android migrations before it are deliberately not ported: the first
/// migration below creates the exported v12 schema exactly enough for Room-compatible SQL and DAO semantics, and each
/// later one is Android's own (v13 adds what each member last sealed under).
public enum FirepitDatabase {
    public static let identityHash = "affe4bac2480fa9d507233b5d82c4aee"
    public static let v12IdentityHash = "ffeccfa0136713b833875de07074781d"
    public static let fileName = "firepit.db"

    public static func open(at directory: URL) throws -> DatabasePool {
        try protectDatabaseDirectory(directory)
        let url = directory.appendingPathComponent(fileName, isDirectory: false)
        var configuration = Configuration()
        configuration.prepareDatabase { db in
            try db.execute(sql: "PRAGMA foreign_keys = ON")
            try db.execute(sql: "PRAGMA journal_mode = WAL")
            try protectSQLiteFiles(for: url)
        }
        let pool = try DatabasePool(path: url.path, configuration: configuration)
        try migrator.migrate(pool)
        try protectSQLiteFiles(for: url)
        return pool
    }

    public static func inMemory() throws -> DatabaseQueue {
        var configuration = Configuration()
        configuration.prepareDatabase { db in
            try db.execute(sql: "PRAGMA foreign_keys = ON")
        }
        let queue = try DatabaseQueue(configuration: configuration)
        try migrator.migrate(queue)
        return queue
    }

    public static var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()
        migrator.registerMigration("create_v12") { db in
            for sql in createStatements { try db.execute(sql: sql) }
            try db.execute(
                sql: "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            try db.execute(
                sql: "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, ?)",
                arguments: [v12IdentityHash])
        }
        migrator.registerMigration("v13_member_evidence") { db in
            try db.execute(sql: "ALTER TABLE room_members ADD COLUMN lastOpenedGeneration INTEGER")
            try db.execute(
                sql: "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, ?)",
                arguments: [identityHash])
        }
        return migrator
    }

    public static let createStatements: [String] = [
        """
        CREATE TABLE IF NOT EXISTS messages (`id` INTEGER NOT NULL, `channel` INTEGER NOT NULL, `fromNodeNum` INTEGER
        NOT NULL, `toNodeNum` INTEGER NOT NULL, `peerNodeNum` INTEGER NOT NULL, `text` TEXT NOT NULL, `sentAt` INTEGER
        NOT NULL, `rxTime` INTEGER, `status` TEXT NOT NULL, `failureReason` TEXT, `isOutgoing` INTEGER NOT NULL, `rxSnr`
        REAL, `rxRssi` INTEGER, `hopsAway` INTEGER, `replyId` INTEGER, `emoji` INTEGER, `signed` INTEGER NOT NULL
        DEFAULT 0, `roomId` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))
        """,
        "CREATE INDEX IF NOT EXISTS `index_messages_channel_sentAt` ON `messages` (`channel`, `sentAt`)",
        "CREATE INDEX IF NOT EXISTS `index_messages_peerNodeNum_sentAt` ON `messages` (`peerNodeNum`, `sentAt`)",
        "CREATE INDEX IF NOT EXISTS `index_messages_roomId` ON `messages` (`roomId`)",
        """
        CREATE TABLE IF NOT EXISTS nodes (`nodeNum` INTEGER NOT NULL, `userId` TEXT, `longName` TEXT, `shortName` TEXT,
        `hwModel` TEXT, `role` TEXT, `publicKey` TEXT, `isUnmessagable` INTEGER NOT NULL, `lastHeard` INTEGER, `snr`
        REAL, `rssi` INTEGER, `hopsAway` INTEGER, `batteryLevel` INTEGER, `voltage` REAL, `channelUtilization` REAL,
        `airUtilTx` REAL, `isFavorite` INTEGER NOT NULL, `firstSeen` INTEGER NOT NULL, `latitudeI` INTEGER, `longitudeI`
        INTEGER, `altitude` INTEGER, `positionTime` INTEGER, `positionPrecision` INTEGER, `groundSpeed` INTEGER,
        `groundTrack` INTEGER, PRIMARY KEY(`nodeNum`))
        """,
        """
        CREATE TABLE IF NOT EXISTS room_members (`roomId` INTEGER NOT NULL, `nodeNum` INTEGER NOT NULL, `invitedBy`
        INTEGER, `firstSeen` INTEGER NOT NULL, `lastHeard` INTEGER, PRIMARY KEY(`roomId`, `nodeNum`))
        """,
        """
        CREATE TABLE IF NOT EXISTS channel_state (`channel` INTEGER NOT NULL, `lastReadAt` INTEGER NOT NULL, `muted`
        INTEGER NOT NULL, `roomId` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`channel`))
        """,
        """
        CREATE TABLE IF NOT EXISTS map_pins (`id` INTEGER NOT NULL, `channel` INTEGER NOT NULL, `latitudeI` INTEGER NOT
        NULL, `longitudeI` INTEGER NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `expire` INTEGER NOT
        NULL, `lockedTo` INTEGER NOT NULL, `icon` TEXT, `createdBy` INTEGER NOT NULL, `receivedAt` INTEGER NOT NULL,
        `roomId` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))
        """,
        """
        CREATE TABLE IF NOT EXISTS deleted_pins (`id` INTEGER NOT NULL, `channel` INTEGER NOT NULL, `deletedAt` INTEGER
        NOT NULL, PRIMARY KEY(`id`))
        """,
        """
        CREATE TABLE IF NOT EXISTS receipts (`messageId` INTEGER NOT NULL, `nodeNum` INTEGER NOT NULL, `state` TEXT NOT
        NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`messageId`, `nodeNum`), FOREIGN KEY(`messageId`) REFERENCES
        `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )
        """,
        "CREATE INDEX IF NOT EXISTS `index_receipts_messageId` ON `receipts` (`messageId`)",
        """
        CREATE TABLE IF NOT EXISTS person_cards (`nodeNum` INTEGER NOT NULL, `name` TEXT NOT NULL, `tag` TEXT NOT NULL,
        `colourSlot` INTEGER, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`nodeNum`))
        """,
        """
        CREATE TABLE IF NOT EXISTS peer_keys (`nodeNum` INTEGER NOT NULL, `phoneKey` TEXT NOT NULL, `learnedAt` INTEGER
        NOT NULL, PRIMARY KEY(`nodeNum`))
        """,
        """
        CREATE TABLE IF NOT EXISTS room_activity (`roomId` INTEGER NOT NULL, `joinedAt` INTEGER NOT NULL,
        `lastActivityAt` INTEGER NOT NULL, `muted` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`roomId`))
        """,
        """
        CREATE TABLE IF NOT EXISTS pending_handovers (`roomId` INTEGER NOT NULL, `nodeNum` INTEGER NOT NULL,
        `generation` INTEGER NOT NULL, `heldGeneration` INTEGER NOT NULL, `removed` TEXT NOT NULL, `createdAt` INTEGER
        NOT NULL, `lastTriedAt` INTEGER NOT NULL, PRIMARY KEY(`roomId`, `nodeNum`))
        """,
    ]
}

private func protectDatabaseDirectory(_ directory: URL) throws {
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    var resourceValues = URLResourceValues()
    resourceValues.isExcludedFromBackup = true
    var mutable = directory
    try mutable.setResourceValues(resourceValues)
    try FileManager.default.setAttributes(
        [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: directory.path)
}

private func protectSQLiteFiles(for url: URL) throws {
    let paths = [url.path, url.path + "-wal", url.path + "-shm"]
    for path in paths where FileManager.default.fileExists(atPath: path) {
        try FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: path)
    }
}

/// Every DAO over one database, for the app's composition root — so the app never has to name a GRDB type.
public struct FirepitDaos: Sendable {
    public let messageDao: MessageDao
    public let nodeDao: NodeDao
    public let mapPinDao: MapPinDao
    public let channelStateDao: ChannelStateDao
    public let roomMemberDao: RoomMemberDao
    public let receiptDao: ReceiptDao
    public let personCardDao: PersonCardDao
    public let peerKeyDao: PeerKeyDao
    public let roomActivityDao: RoomActivityDao
    public let pendingHandoverDao: PendingHandoverDao

    public init(writer: any DatabaseWriter) {
        messageDao = MessageDao(writer)
        nodeDao = NodeDao(writer)
        mapPinDao = MapPinDao(writer)
        channelStateDao = ChannelStateDao(writer)
        roomMemberDao = RoomMemberDao(writer)
        receiptDao = ReceiptDao(writer)
        personCardDao = PersonCardDao(writer)
        peerKeyDao = PeerKeyDao(writer)
        roomActivityDao = RoomActivityDao(writer)
        pendingHandoverDao = PendingHandoverDao(writer)
    }

    /// The app's own database, protected and excluded from backup (see `FirepitDatabase.open`).
    public static func open(at directory: URL) throws -> FirepitDaos {
        FirepitDaos(writer: try FirepitDatabase.open(at: directory))
    }

    /// A database that exists only while the process runs.
    public static func inMemory() throws -> FirepitDaos {
        FirepitDaos(writer: try FirepitDatabase.inMemory())
    }
}
