import FirepitData
import Foundation
import Testing

/// Ported from android/core/data SeenSealsTest.
@Suite("Seen seals")
struct SeenSealsTests {
    let room: Int32 = 0x0BAD_F00D
    let hour = 491_234
    let nonce = Data((0..<12).map { UInt8($0) })

    private func scratchFile() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("seen-seals-\(UUID().uuidString)")
    }

    @Test func aMessageOpensOnceAndEveryCopyAfterIsRefused() {
        let seen = SeenSeals(file: nil)
        #expect(seen.firstSight(roomId: room, generation: 1, sender: 42, hour: hour, nonce: nonce, nowHour: hour))
        #expect(!seen.firstSight(roomId: room, generation: 1, sender: 42, hour: hour, nonce: nonce, nowHour: hour))
    }

    @Test func differentMessagesAreAllLetThrough() {
        let seen = SeenSeals(file: nil)
        var other = nonce
        other[11] = 99
        #expect(seen.firstSight(roomId: room, generation: 1, sender: 42, hour: hour, nonce: nonce, nowHour: hour))
        #expect(seen.firstSight(roomId: room, generation: 1, sender: 42, hour: hour, nonce: other, nowHour: hour))
        #expect(seen.firstSight(roomId: room, generation: 1, sender: 43, hour: hour, nonce: nonce, nowHour: hour))
        #expect(seen.firstSight(roomId: room, generation: 2, sender: 42, hour: hour, nonce: nonce, nowHour: hour))
        #expect(seen.firstSight(roomId: room + 1, generation: 1, sender: 42, hour: hour, nonce: nonce, nowHour: hour))
    }

    @Test func restartingTheAppIsNotAWayToPlayOneBack() {
        let file = scratchFile()
        defer { try? FileManager.default.removeItem(at: file) }
        _ = SeenSeals(file: file).firstSight(
            roomId: room, generation: 1, sender: 42, hour: hour, nonce: nonce, nowHour: hour)

        #expect(
            !SeenSeals(file: file).firstSight(
                roomId: room, generation: 1, sender: 42, hour: hour, nonce: nonce, nowHour: hour))
    }

    @Test func hoursNoMessageCanOpenInAnyMoreAreForgotten() {
        let file = scratchFile()
        defer { try? FileManager.default.removeItem(at: file) }
        let seen = SeenSeals(file: file)
        var later = nonce
        later[0] = 1
        _ = seen.firstSight(roomId: room, generation: 1, sender: 42, hour: hour, nonce: nonce, nowHour: hour)
        _ = seen.firstSight(roomId: room, generation: 1, sender: 42, hour: hour + 3, nonce: later, nowHour: hour + 3)

        #expect(seen.size() == 1)
        #expect(SeenSeals(file: file).size() == 1)
    }

    @Test func aDamagedFileCostsTheMemoryOfItNotTheApp() throws {
        let file = scratchFile()
        defer { try? FileManager.default.removeItem(at: file) }
        try Data("not a record\n1 2 3\n\u{0}\u{1}".utf8).write(to: file)
        let seen = SeenSeals(file: file)

        #expect(seen.size() == 0)
        #expect(seen.firstSight(roomId: room, generation: 1, sender: 42, hour: hour, nonce: nonce, nowHour: hour))
    }
}
