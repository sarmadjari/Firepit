import Foundation
import GRDB
import Testing

@testable import FirepitData

/// The phone database is encrypted with SQLCipher (build plan, Stage 11 Phase 5).
@Suite("Database encryption")
struct DatabaseEncryptionTests {
    private func folder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func file(_ folder: URL) -> URL { folder.appendingPathComponent(FirepitDatabase.fileName) }

    @Test func theFileOnDiskIsNotPlainSQLite() throws {
        let folder = try folder()
        let pool = try FirepitDatabase.open(at: folder, secrets: InMemorySecretStore())
        try pool.write { db in try db.execute(sql: "INSERT INTO channel_state (channel, lastReadAt, muted) VALUES (1, 5, 0)") }
        try pool.close()
        #expect(!FirepitDatabase.isPlaintext(file(folder)))
        let bytes = try Data(contentsOf: file(folder))
        #expect(bytes.range(of: Data("CREATE TABLE".utf8)) == nil)
    }

    @Test func theSameKeyOpensItAgain() throws {
        let folder = try folder()
        let secrets = InMemorySecretStore()
        let first = try FirepitDatabase.open(at: folder, secrets: secrets)
        try first.write { db in try db.execute(sql: "INSERT INTO channel_state (channel, lastReadAt, muted) VALUES (2, 7, 1)") }
        try first.close()

        let again = try FirepitDatabase.open(at: folder, secrets: secrets)
        let count = try again.read { db in try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM channel_state") }
        #expect(count == 1)
    }

    @Test func anotherKeyCannotReadIt() throws {
        let folder = try folder()
        let pool = try FirepitDatabase.open(at: folder, secrets: InMemorySecretStore())
        try pool.close()
        #expect(throws: (any Error).self) {
            let stranger = try FirepitDatabase.open(at: folder, secrets: InMemorySecretStore())
            _ = try stranger.read { db in try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM sqlite_master") }
        }
    }

    @Test func aDatabaseFromBeforeEncryptionKeepsEveryRow() throws {
        let folder = try folder()
        // What a phone has before this build: the same schema, unencrypted.
        let plain = try DatabaseQueue(path: file(folder).path)
        try FirepitDatabase.migrator.migrate(plain)
        try plain.write { db in try db.execute(sql: "INSERT INTO channel_state (channel, lastReadAt, muted) VALUES (3, 9, 0)") }
        try plain.close()
        #expect(FirepitDatabase.isPlaintext(file(folder)))

        let secrets = InMemorySecretStore()
        let pool = try FirepitDatabase.open(at: folder, secrets: secrets)
        let lastRead = try pool.read { db in
            try Int64.fetchOne(db, sql: "SELECT lastReadAt FROM channel_state WHERE channel = 3")
        }
        #expect(lastRead == 9)
        try pool.close()
        #expect(!FirepitDatabase.isPlaintext(file(folder)))
    }

    @Test func theKeyIsRandomAndKeptInTheRawForm() throws {
        let secrets = InMemorySecretStore()
        let key = try FirepitDatabase.databaseKey(in: secrets)
        #expect(key.count == 67)
        #expect(key.prefix(2) == Data("x'".utf8))
        #expect(try FirepitDatabase.databaseKey(in: secrets) == key)
        #expect(try FirepitDatabase.databaseKey(in: InMemorySecretStore()) != key)
    }
}
