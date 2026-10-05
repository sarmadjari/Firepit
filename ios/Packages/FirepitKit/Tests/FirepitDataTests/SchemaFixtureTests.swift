import FirepitData
import Foundation
import GRDB
import Testing

private struct RoomSchema: Decodable {
    var database: DatabaseSchema
}

private struct DatabaseSchema: Decodable {
    var identityHash: String
    var entities: [Entity]
}

private struct Entity: Decodable {
    var tableName: String
    var fields: [Field]
}

private struct Field: Decodable {
    var columnName: String
    var affinity: String
    var notNull: Bool?
    var defaultValue: String?
}

private func fixtureSchema() throws -> RoomSchema {
    let url = Bundle.module.url(forResource: "room-schema-15", withExtension: "json", subdirectory: "Fixtures")!
    return try JSONDecoder().decode(RoomSchema.self, from: Data(contentsOf: url))
}

@Test func schemaFixtureContainsAllMigratedTables() throws {
    let schema = try fixtureSchema()
    let queue = try makeDatabase()
    let migrated = try queue.read { db in
        try String.fetchAll(db, sql: "SELECT name FROM sqlite_master WHERE type = 'table'")
    }
    for entity in schema.database.entities {
        #expect(migrated.contains(entity.tableName))
    }
}

@Test func schemaFixtureIdentityHashMatchesTheApp() throws {
    #expect(try fixtureSchema().database.identityHash == FirepitDatabase.identityHash)
}

@Test func schemaFixtureColumnNamesMatchMigratedTables() throws {
    let schema = try fixtureSchema()
    let queue = try makeDatabase()
    for entity in schema.database.entities {
        let names = try queue.read { db in
            try Row.fetchAll(db, sql: "PRAGMA table_info(\(entity.tableName))").map { $0["name"] as String }
        }
        #expect(names == entity.fields.map(\.columnName))
    }
}

@Test func schemaForeignKeysAreEnforced() throws {
    let queue = try makeDatabase()
    #expect(throws: Error.self) {
        try queue.write { db in
            try db.execute(
                sql: "INSERT INTO receipts (messageId, nodeNum, state, at) VALUES (404, 1, 'READ', 1)"
            )
        }
    }
}

@Test func v12DatabaseMigratesMemberEvidenceAndKeepsRosterRows() throws {
    var configuration = Configuration()
    configuration.prepareDatabase { db in try db.execute(sql: "PRAGMA foreign_keys = ON") }
    let queue = try DatabaseQueue(configuration: configuration)
    try queue.write { db in
        for sql in FirepitDatabase.createStatements { try db.execute(sql: sql) }
        try db.execute(sql: "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        try db.execute(
            sql: "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, ?)",
            arguments: [FirepitDatabase.v12IdentityHash])
        try db.execute(
            sql: """
                 INSERT INTO room_members (roomId, nodeNum, invitedBy, firstSeen, lastHeard)
                 VALUES (42, 7, NULL, 100, 200)
                 """)
    }

    try FirepitDatabase.migrator.migrate(queue)

    let columns = try queue.read { db in
        try Row.fetchAll(db, sql: "PRAGMA table_info(room_members)").map { $0["name"] as String }
    }
    #expect(columns.contains("lastOpenedGeneration"))
    let member = try queue.read { db in
        try RoomMemberEntity.fetchOne(db, sql: "SELECT * FROM room_members WHERE roomId = 42 AND nodeNum = 7")
    }
    #expect(member?.lastHeard == 200)
    try queue.write { db in
        try RoomMemberEntity(
            roomId: 42,
            nodeNum: 7,
            invitedBy: nil,
            firstSeen: 100,
            lastHeard: 300,
            lastOpenedGeneration: 3
        ).save(db)
    }
    let generation = try queue.read { db in
        try Int.fetchOne(
            db,
            sql: "SELECT lastOpenedGeneration FROM room_members WHERE roomId = 42 AND nodeNum = 7")
    }
    #expect(generation == 3)
}

@Test func v13DatabaseMigratesPhoneKeyProvenanceAndKeepsKeys() throws {
    var configuration = Configuration()
    configuration.prepareDatabase { db in try db.execute(sql: "PRAGMA foreign_keys = ON") }
    let queue = try DatabaseQueue(configuration: configuration)
    try queue.write { db in
        for sql in FirepitDatabase.createStatements { try db.execute(sql: sql) }
        try db.execute(sql: "ALTER TABLE room_members ADD COLUMN lastOpenedGeneration INTEGER")
        try db.execute(sql: "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        try db.execute(
            sql: "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, ?)",
            arguments: [FirepitDatabase.v13IdentityHash])
        try db.execute(sql: "CREATE TABLE IF NOT EXISTS grdb_migrations (identifier TEXT NOT NULL PRIMARY KEY)")
        try db.execute(sql: "INSERT INTO grdb_migrations (identifier) VALUES ('create_v12')")
        try db.execute(sql: "INSERT INTO grdb_migrations (identifier) VALUES ('v13_member_evidence')")
        try db.execute(sql: "INSERT INTO peer_keys (nodeNum, phoneKey, learnedAt) VALUES (7, 'abc', 100)")
    }

    try FirepitDatabase.migrator.migrate(queue)

    let key = try queue.read { db in
        try PeerKeyEntity.fetchOne(db, sql: "SELECT * FROM peer_keys WHERE nodeNum = 7")
    }
    #expect(key?.phoneKey == "abc")
    #expect(key?.inPerson == false)
    let hash = try queue.read { db in
        try String.fetchOne(db, sql: "SELECT identity_hash FROM room_master_table WHERE id = 42")
    }
    #expect(hash == FirepitDatabase.identityHash)
}

@Test func v14DatabaseMigratesRadioPositionFlagAndKeepsPositions() throws {
    var configuration = Configuration()
    configuration.prepareDatabase { db in try db.execute(sql: "PRAGMA foreign_keys = ON") }
    let queue = try DatabaseQueue(configuration: configuration)
    try queue.write { db in
        for sql in FirepitDatabase.createStatements { try db.execute(sql: sql) }
        try db.execute(sql: "ALTER TABLE room_members ADD COLUMN lastOpenedGeneration INTEGER")
        try db.execute(sql: "ALTER TABLE peer_keys ADD COLUMN inPerson INTEGER NOT NULL DEFAULT 0")
        try db.execute(sql: "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        try db.execute(
            sql: "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, ?)",
            arguments: [FirepitDatabase.v14IdentityHash])
        try db.execute(sql: "CREATE TABLE IF NOT EXISTS grdb_migrations (identifier TEXT NOT NULL PRIMARY KEY)")
        for id in ["create_v12", "v13_member_evidence", "v14_key_provenance"] {
            try db.execute(sql: "INSERT INTO grdb_migrations (identifier) VALUES (?)", arguments: [id])
        }
        try db.execute(
            sql: """
                 INSERT INTO nodes (nodeNum, isUnmessagable, isFavorite, firstSeen, latitudeI, longitudeI)
                 VALUES (7, 0, 0, 100, 1, 2)
                 """)
    }

    try FirepitDatabase.migrator.migrate(queue)

    let row = try queue.read { db in
        try Row.fetchOne(db, sql: "SELECT latitudeI, longitudeI, positionFromRadio FROM nodes WHERE nodeNum = 7")
    }
    #expect(row?["latitudeI"] as Int? == 1)
    #expect(row?["longitudeI"] as Int? == 2)
    #expect(row?["positionFromRadio"] as Bool? == false)
    let hash = try queue.read { db in
        try String.fetchOne(db, sql: "SELECT identity_hash FROM room_master_table WHERE id = 42")
    }
    #expect(hash == FirepitDatabase.identityHash)
}
