import FirepitModel
import Foundation

/// Sealed messages already opened, so a recording played back is refused.
///
/// A copy of a sealed packet opens as well as the original: the seal proves who wrote it, not when it was sent.
/// Somebody who recorded a room could otherwise play back a receipt, a position or a roster change and have it
/// believed again. Each sealed message carries a nonce that is never repeated, so a second arrival of one is a copy,
/// whatever packet it came wrapped in.
///
/// Only the hours a message can still be opened in need remembering (``RoomRatchet/opens(heldHour:nowHour:hour:)``);
/// anything older is refused before it gets here. So the list is a few hours of traffic at most, and is kept on disk
/// so that restarting the app is not a way in either. Nothing in it is secret: a nonce travels in the clear.
/// Android: `SeenSeals`.
public final class SeenSeals: Sendable {
    private struct State {
        /// Each message's identity, and the hour it was sealed in, for forgetting it later.
        var seen: [String: Int] = [:]
        var loaded = false
    }

    /// The hour just gone, this one and the next can open; one more for a clock that stepped back.
    private static let keepHours = 2

    /// More than a LoRa channel can carry in `keepHours` hours, so only a fault reaches it.
    private static let maxEntries = 20_000

    private let file: URL?
    private let state = Mutex(State())

    public init(file: URL?) {
        self.file = file
    }

    /// True the first time a message is offered, and false for every copy after. Only called for a message that
    /// opened, so nobody without the key can fill the list.
    public func firstSight(roomId: Int32, generation: Int, sender: Int32, hour: Int, nonce: Data, nowHour: Int) -> Bool
    {
        state.withLock { state in
            load(&state)
            let pruned = prune(&state, nowHour: nowHour)
            let id = Self.idOf(roomId: roomId, generation: generation, sender: sender, nonce: nonce)
            if state.seen[id] != nil {
                if pruned {
                    save(state)
                }
                return false
            }
            state.seen[id] = hour
            save(state)
            return true
        }
    }

    /// How many messages are remembered. For tests.
    public func size() -> Int {
        state.withLock { state in
            load(&state)
            return state.seen.count
        }
    }

    private func prune(_ state: inout State, nowHour: Int) -> Bool {
        let before = state.seen.count
        state.seen = state.seen.filter { $0.value >= nowHour - Self.keepHours }
        if state.seen.count > Self.maxEntries {
            let oldest = state.seen.sorted { $0.value < $1.value }.prefix(state.seen.count - Self.maxEntries)
            for entry in oldest {
                state.seen.removeValue(forKey: entry.key)
            }
        }
        return state.seen.count != before
    }

    private func load(_ state: inout State) {
        guard !state.loaded else {
            return
        }
        state.loaded = true
        guard let file, let data = try? Data(contentsOf: file), let text = String(data: data, encoding: .utf8) else {
            return
        }
        for line in text.split(separator: "\n") {
            let parts = line.split(separator: " ", omittingEmptySubsequences: false)
            guard parts.count == 5, let hour = Int(parts[4]) else {
                continue
            }
            state.seen[parts[0..<4].joined(separator: " ")] = hour
        }
    }

    private func save(_ state: State) {
        guard let file else {
            return
        }
        let text = state.seen.map { "\($0.key) \($0.value)\n" }.joined()
        try? Data(text.utf8).write(to: file, options: .atomic)
    }

    private static func idOf(roomId: Int32, generation: Int, sender: Int32, nonce: Data) -> String {
        "\(roomId) \(generation) \(sender) \(nonce.map { String(format: "%02x", $0) }.joined())"
    }
}
