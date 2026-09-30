import FirepitData
import Foundation
import GRDB
import Testing

private struct RoomSchema: Decodable {
    var database: DatabaseSchema
}

private struct DatabaseSchema: Decodable {
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
    let url = Bundle.module.url(forResource: "room-schema-12", withExtension: "json", subdirectory: "Fixtures")!
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
