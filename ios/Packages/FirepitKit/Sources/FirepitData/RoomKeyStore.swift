import FirepitCrypto
import FirepitModel
import Foundation
import os

private let roomKeyLog = Logger(subsystem: "com.getfirepit.app", category: "FirepitRoomKeys")

public enum RoomKeyStoreError: Error, Sendable, Equatable {
    case wrongKeySize(Int)
}

/// The keys that open rooms, held where the radio cannot reach.
///
/// A room key has to leave the phone — sealed to another phone's key in a grant or a rotation — so it cannot live
/// inside the Keychain key material itself. Android wraps each value with a hardware key; iOS stores the room-key bytes
/// in this store's Keychain service. Swift Data cannot be reliably zeroed after copying, so cached keys are dropped on
/// forget rather than wiped with Android's fill(0).
public final class RoomKeyStore: Sendable {
    public static let first = 1

    private let store: any SecretStore
    private let unwrapped = Mutex<[String: Data]>([:])

    public init(store: any SecretStore = KeychainStore(service: "com.getfirepit.app.room-keys")) {
        self.store = store
    }

    /// The key a room is sealing with now, or nil when it is not sealed.
    public func keyFor(roomId: Int32) -> Data? {
        keyFor(roomId: roomId, generation: generationOf(roomId: roomId))
    }

    /// A specific generation, because old keys are kept for late packets from the mesh.
    public func keyFor(roomId: Int32, generation: Int) -> Data? {
        let account = slot(roomId, generation)
        if let cached = unwrapped.withLock({ $0[account] }) {
            return cached
        }
        do {
            guard let stored = try store.data(for: account) else {
                return nil
            }
            unwrapped.withLock { state in
                state[account] = stored
            }
            return stored
        } catch {
            roomKeyLog.error("could not read a room key")
            return nil
        }
    }

    /// Which generation this room is sealing with.
    public func generationOf(roomId: Int32) -> Int {
        integer(for: current(roomId)) ?? Self.first
    }

    /// The key to seal with now, or nil when there is none — or when the room moved to a key that never reached us.
    public func sealingKey(roomId: Int32) -> Data? {
        isSuperseded(roomId: roomId) ? nil : keyFor(roomId: roomId)
    }

    /// True when a member told us the room moved on to a later key than the one we hold.
    public func isSuperseded(roomId: Int32) -> Bool {
        (integer(for: superseded(roomId)) ?? 0) > generationOf(roomId: roomId)
    }

    /// Records that roomId has moved on to generation; cleared once we hold that key.
    public func markSuperseded(roomId: Int32, generation: Int) throws {
        if generation <= generationOf(roomId: roomId) {
            return
        }
        let existing = integer(for: superseded(roomId)) ?? 0
        try store.set(Data(String(max(generation, existing)).utf8), for: superseded(roomId))
    }

    public func remember(roomId: Int32, key: Data, generation: Int = first) throws {
        guard key.count == RoomCipher.keySize else {
            throw RoomKeyStoreError.wrongKeySize(key.count)
        }
        try store.set(key, for: slot(roomId, generation))
        if generation >= generationOf(roomId: roomId) {
            try store.set(Data(String(generation).utf8), for: current(roomId))
        }
        if generation >= (integer(for: superseded(roomId)) ?? 0) {
            try store.remove(superseded(roomId))
        }
        unwrapped.withLock { state in
            state[slot(roomId, generation)] = key
        }
    }

    public func generate(roomId: Int32, generation: Int = first) throws -> Data {
        let key = RoomCipher.generateKey()
        try remember(roomId: roomId, key: key, generation: generation)
        return key
    }

    /// Leaving a room takes every key it ever had, or leaving would not mean much.
    public func forget(roomId: Int32) throws {
        try store.remove(current(roomId))
        try store.remove(superseded(roomId))
        try store.removeAll(prefix: "\(roomId)/")
        unwrapped.withLock { state in
            state.keys
                .filter { $0.hasPrefix("\(roomId)/") }
                .forEach { state.removeValue(forKey: $0) }
        }
    }

    private func integer(for account: String) -> Int? {
        do {
            guard let data = try store.data(for: account),
                let text = String(data: data, encoding: .utf8)
            else {
                return nil
            }
            return Int(text)
        } catch {
            roomKeyLog.error("could not read room-key metadata")
            return nil
        }
    }

    private func slot(_ roomId: Int32, _ generation: Int) -> String {
        "\(roomId)/\(generation)"
    }

    private func current(_ roomId: Int32) -> String {
        "\(roomId).generation"
    }

    private func superseded(_ roomId: Int32) -> String {
        "\(roomId).superseded"
    }
}
