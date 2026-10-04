import Foundation

/// When this phone made each room generation, outside the database schema.
public final class RoomKeyMadeStore: @unchecked Sendable {
    public struct Record: Sendable, Equatable {
        public var generation: Int
        public var madeAt: Int64
    }

    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func record(roomId: Int32) -> Record? {
        guard let encoded = defaults.string(forKey: key(roomId)) else { return nil }
        let parts = encoded.split(separator: ":")
        guard let generation = parts.first.flatMap({ Int($0) }),
            let madeAt = parts.dropFirst().first.flatMap({ Int64($0) })
        else { return nil }
        return Record(generation: generation, madeAt: madeAt)
    }

    public func record(roomId: Int32, generation: Int, at: Int64) {
        defaults.set("\(generation):\(at)", forKey: key(roomId))
    }

    public func markDue(roomId: Int32, generation: Int) {
        defaults.set(true, forKey: dueKey(roomId))
        if record(roomId: roomId) == nil {
            record(roomId: roomId, generation: generation, at: Int64(Date().timeIntervalSince1970 * 1_000))
        }
    }

    public func isDueNow(roomId: Int32) -> Bool {
        defaults.bool(forKey: dueKey(roomId))
    }

    public func clearDue(roomId: Int32) {
        defaults.removeObject(forKey: dueKey(roomId))
    }

    public func markMadeByMe(roomId: Int32) {
        defaults.set(true, forKey: makerKey(roomId))
    }

    public func isMadeByMe(roomId: Int32) -> Bool {
        defaults.bool(forKey: makerKey(roomId))
    }

    public func forget(roomId: Int32) {
        defaults.removeObject(forKey: key(roomId))
        defaults.removeObject(forKey: dueKey(roomId))
        defaults.removeObject(forKey: makerKey(roomId))
    }

    private func key(_ roomId: Int32) -> String { "room_\(roomId)" }
    private func dueKey(_ roomId: Int32) -> String { "due_\(roomId)" }
    private func makerKey(_ roomId: Int32) -> String { "maker_\(roomId)" }
}
