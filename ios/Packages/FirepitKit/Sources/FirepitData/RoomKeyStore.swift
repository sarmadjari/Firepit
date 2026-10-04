import FirepitCrypto
import FirepitModel
import FirepitProtos
import Foundation
import os

private let roomKeyLog = Logger(subsystem: "com.getfirepit.app", category: "FirepitRoomKeys")

public enum RoomKeyStoreError: Error, Sendable, Equatable {
    case wrongKeySize(Int)
}

/// The keys that open rooms, held where the radio cannot reach, and destroyed as the clock moves on.
///
/// A room key has to leave the phone — sealed to another phone's key in a grant or a rotation — so it cannot live
/// inside the Keychain key material itself. Android wraps each value with a hardware key; iOS stores the room-key bytes
/// in this store's Keychain service, on this device only. Swift Data cannot be reliably zeroed after copying, so cached
/// keys are dropped rather than wiped with Android's fill(0).
///
/// For each generation of a room's key, one hour's key is kept: the hour just gone, so a packet the mesh delivers late
/// still opens. Later hours are derived from it when needed and earlier ones are destroyed, busy room or quiet
/// (``RoomRatchet``), so whoever takes the phone cannot read what was said before.
public final class RoomKeyStore: Sendable {
    public static let first = 1

    /// How long a generation the room has moved on from stays, in hours: long enough for anything sealed under it just
    /// before the move, from a sender whose clock runs a little behind, to arrive and open.
    public static let retiredGraceHours = 2

    private static let hourSize = 4

    private let store: any SecretStore
    private let seen: SeenSeals

    /// The phone's clock for sealing and opening, and real time for erasing (see ``KeyClock``). Replaced only by
    /// tests.
    public let time: any KeyTime

    /// Keys already read, so the Keychain is asked once per room rather than once per message.
    private let cached = Mutex<[String: HourKey]>([:])

    /// Moving a key on is read, derive, write: two at once would each keep a different hour.
    private let gate = NSRecursiveLock()

    public init(
        store: any SecretStore = KeychainStore(service: "com.getfirepit.app.room-keys"),
        seen: SeenSeals = SeenSeals(file: nil),
        time: any KeyTime = KeyClock.system()
    ) {
        self.store = store
        self.seen = seen
        self.time = time
    }

    /// True when this phone holds the key `roomId` seals with now, which is what makes a slot one of ours. Under the
    /// gate, so a room being forgotten at that moment cannot have its key put back in memory.
    public func holds(roomId: Int32) -> Bool {
        gate.lock()
        defer { gate.unlock() }
        return held(roomId, generationOf(roomId: roomId)) != nil
    }

    /// True when something new may be sealed for `roomId`: we hold its key, and the room has not moved to one that
    /// never reached us. The only other people still sealing under an old key are whoever was removed.
    public func canSeal(roomId: Int32) -> Bool {
        !isSuperseded(roomId: roomId) && holds(roomId: roomId)
    }

    /// Which generation this room is sealing with.
    public func generationOf(roomId: Int32) -> Int {
        integer(for: current(roomId)) ?? Self.first
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

    /// `plaintext` sealed by `sender` for `roomId` under this hour's key, ready to travel. Nil when there is no key to
    /// seal with.
    ///
    /// `generation` is for a rotation, which is sealed under the key a member still holds. Anything else uses the
    /// room's current key, and nothing is sealed at all once the room has moved on without us.
    public func seal(roomId: Int32, sender: Int32, plaintext: Data, generation: Int? = nil) -> Meshchat_SealedMessage? {
        if generation == nil, isSuperseded(roomId: roomId) {
            return nil
        }
        let sealingGeneration = generation ?? generationOf(roomId: roomId)
        let now = RoomRatchet.hourOf(unixMillis: time.wallMillis())
        guard let held = advanced(roomId, sealingGeneration, now: erasableHour()) else {
            return nil
        }
        let hour = RoomRatchet.currentHour(heldHour: held.hour, nowHour: now)
        guard let key = senderKey(roomId, sealingGeneration, held: held, hour: hour, sender: sender) else {
            return nil
        }
        var message = Meshchat_SealedMessage()
        message.roomID = UInt32(bitPattern: roomId)
        message.ciphertext = SealedText.seal(
            key: key, hour: hour, plaintext: plaintext,
            context: SealedText.contextOf(roomId: roomId, senderNodeNum: sender))
        message.generation = UInt32(sealingGeneration)
        return message
    }

    /// What `sender` sealed for `roomId` under `generation`, opened, or why not.
    ///
    /// Only messages sealed in the hours this phone still holds keys for open, and each one only once.
    public func open(roomId: Int32, generation: Int, sender: Int32, payload: Data) -> Opening {
        guard let tag = SealedText.hourTagOf(payload) else {
            return SealedText.isFirstFormat(payload) ? .outdated : .unreadable
        }
        let now = RoomRatchet.hourOf(unixMillis: time.wallMillis())
        let erasable = erasableHour()
        guard let held = advanced(roomId, generation, now: erasable) else {
            return .noKey
        }
        let hour = RoomRatchet.hourNear(tag: tag, near: RoomRatchet.currentHour(heldHour: held.hour, nowHour: now))
        guard RoomRatchet.opens(heldHour: held.hour, nowHour: now, hour: hour) else {
            return .outOfHours(hour: hour, now: now)
        }
        guard let key = senderKey(roomId, generation, held: held, hour: hour, sender: sender) else {
            return .noKey
        }
        guard
            let plain = SealedText.open(
                key: key, payload: payload, context: SealedText.contextOf(roomId: roomId, senderNodeNum: sender)),
            let nonce = SealedText.nonceOf(payload)
        else {
            return .unreadable
        }
        guard
            seen.firstSight(
                roomId: roomId, generation: generation, sender: sender, hour: hour, nonce: nonce, nowHour: erasable)
        else {
            return .replayed
        }
        // Another phone sealed this in our hour: the room agrees with our clock.
        if abs(hour - now) <= 1 {
            time.agreed()
        }
        return .read(plain: plain)
    }

    /// This hour's key for `generation` of `roomId`: what somebody joining, or a member who missed a rotation, is
    /// handed. Never an earlier hour's, so they cannot read what was said before they had it.
    public func currentKey(roomId: Int32, generation: Int? = nil) -> HourKey? {
        let generation = generation ?? generationOf(roomId: roomId)
        let now = RoomRatchet.hourOf(unixMillis: time.wallMillis())
        guard let held = advanced(roomId, generation, now: erasableHour()) else {
            return nil
        }
        let hour = RoomRatchet.currentHour(heldHour: held.hour, nowHour: now)
        guard
            let key = RoomRatchet.forward(
                key: held.key, roomId: roomId, generation: generation, from: held.hour, to: hour)
        else {
            return nil
        }
        return HourKey(hour: hour, key: key)
    }

    public func directSecretForSealing(roomId: Int32) -> DirectSeal.RoomSecret? {
        gate.lock()
        defer { gate.unlock() }
        guard !isSuperseded(roomId: roomId) else {
            return nil
        }
        let generation = generationOf(roomId: roomId)
        let now = RoomRatchet.hourOf(unixMillis: time.wallMillis())
        guard let held = advanced(roomId, generation, now: erasableHour()) else {
            return nil
        }
        let hour = RoomRatchet.currentHour(heldHour: held.hour, nowHour: now)
        guard
            let key = RoomRatchet.forward(
                key: held.key, roomId: roomId, generation: generation, from: held.hour, to: hour)
        else {
            return nil
        }
        return DirectSeal.RoomSecret(roomId: roomId, generation: generation, hour: hour, key: key)
    }

    public func directSecretsForOpening(tag: Int) -> [DirectSeal.RoomSecret] {
        gate.lock()
        defer { gate.unlock() }
        let now = RoomRatchet.hourOf(unixMillis: time.wallMillis())
        let erasable = erasableHour()
        let slots: [RoomGeneration]
        do {
            slots = try store.accounts().compactMap(Self.parseSlot)
        } catch {
            roomKeyLog.error("could not list room keys for direct opening")
            return []
        }
        return Array(Set(slots))
            .sorted { $0.roomId == $1.roomId ? $0.generation < $1.generation : $0.roomId < $1.roomId }
            .compactMap { generation in
                guard let held = advanced(generation.roomId, generation.generation, now: erasable) else {
                    return nil
                }
                let hour = RoomRatchet.hourNear(
                    tag: tag, near: RoomRatchet.currentHour(heldHour: held.hour, nowHour: now))
                guard RoomRatchet.opens(heldHour: held.hour, nowHour: now, hour: hour),
                    let key = RoomRatchet.forward(
                        key: held.key, roomId: generation.roomId, generation: generation.generation,
                        from: held.hour, to: hour)
                else {
                    return nil
                }
                return DirectSeal.RoomSecret(
                    roomId: generation.roomId, generation: generation.generation, hour: hour, key: key)
            }
    }

    public func directTagInWindow(tag: Int) -> Bool {
        let now = RoomRatchet.hourOf(unixMillis: time.wallMillis())
        let hour = RoomRatchet.hourNear(tag: tag, near: now)
        return (now - 1)...(now + 1) ~= hour
    }

    public func firstDirectSight(sender: Int32, opening: DirectSeal.Opening) -> Bool {
        guard let room = opening.room, let hour = opening.hour, let nonce = opening.nonce else {
            return true
        }
        return seen.firstSight(
            roomId: room.roomId, generation: room.generation, sender: sender, hour: hour, nonce: nonce,
            nowHour: erasableHour())
    }

    public func remember(roomId: Int32, key: HourKey, generation: Int = first) throws {
        guard key.key.count == RoomCipher.keySize else {
            throw RoomKeyStoreError.wrongKeySize(key.key.count)
        }
        gate.lock()
        defer { gate.unlock() }
        let previous = generationOf(roomId: roomId)
        let now = erasableHour()
        try store.set(Self.encode(key), for: slot(roomId, generation))
        try store.remove(retired(roomId, generation))
        if generation < previous {
            // Never walk backwards: a late rotation message must not undo a newer one that has already been applied.
            try store.set(Data(String(now).utf8), for: retired(roomId, generation))
        } else {
            if generation > previous, try store.data(for: slot(roomId, previous)) != nil {
                // Kept a little longer, for packets sealed just before the move.
                try store.set(Data(String(now).utf8), for: retired(roomId, previous))
            }
            try store.set(Data(String(generation).utf8), for: current(roomId))
        }
        // Holding the key the room moved to is the end of being left behind.
        if generation >= (integer(for: superseded(roomId)) ?? 0) {
            try store.remove(superseded(roomId))
        }
        cached.withLock { state in
            state[slot(roomId, generation)] = key
        }
    }

    /// A new key for `roomId`, starting this hour.
    @discardableResult
    public func generate(roomId: Int32, generation: Int = first) throws -> HourKey {
        let key = HourKey(hour: RoomRatchet.hourOf(unixMillis: time.wallMillis()), key: RoomCipher.generateKey())
        try remember(roomId: roomId, key: key, generation: generation)
        return key
    }

    /// Destroys every key that is no longer needed, in every room.
    ///
    /// Run on the clock, not on traffic: a quiet room has to forget as surely as a busy one. A generation the room has
    /// moved on from goes entirely once late packets sealed under it have had time to arrive, unless it is in `owed`:
    /// a member who missed the move still holds it, and their new key has to be sealed under it.
    public func erase(owed: Set<RoomGeneration> = []) {
        let now = erasableHour()
        let slots: [RoomGeneration]
        do {
            slots = try store.accounts().compactMap(Self.parseSlot)
        } catch {
            roomKeyLog.error("could not list room keys to erase old ones")
            return
        }
        for held in slots {
            gate.lock()
            defer { gate.unlock() }
            if held.generation != generationOf(roomId: held.roomId) {
                let retiredAt: Int
                if let marked = integer(for: retired(held.roomId, held.generation)) {
                    retiredAt = marked
                } else {
                    retiredAt = now
                    try? store.set(Data(String(now).utf8), for: retired(held.roomId, held.generation))
                }
                if now - retiredAt >= Self.retiredGraceHours, !owed.contains(held) {
                    drop(held.roomId, held.generation)
                    continue
                }
            }
            _ = advanced(held.roomId, held.generation, now: now)
        }
    }

    /// Leaving a room takes every key it ever had, or leaving would not mean much.
    public func forget(roomId: Int32) throws {
        gate.lock()
        defer { gate.unlock() }
        try store.remove(current(roomId))
        try store.remove(superseded(roomId))
        try store.removeAll(prefix: "\(roomId)/")
        cached.withLock { state in
            state.keys
                .filter { $0.hasPrefix("\(roomId)/") }
                .forEach { state.removeValue(forKey: $0) }
        }
    }

    /// The hour old keys may be erased up to: real time, never a clock that jumped ahead.
    private func erasableHour() -> Int {
        RoomRatchet.hourOf(unixMillis: time.eraseMillis())
    }

    /// The held key for one generation, first moved on so that nothing older than the hour before `now` survives.
    /// `now` is ``erasableHour()``, not the wall clock.
    private func advanced(_ roomId: Int32, _ generation: Int, now: Int) -> HourKey? {
        gate.lock()
        defer { gate.unlock() }
        guard let held = held(roomId, generation) else {
            return nil
        }
        let keep = RoomRatchet.keepFrom(heldHour: held.hour, nowHour: now)
        guard keep != held.hour,
            // A clock decades out: keep what we have rather than spin.
            let moved = RoomRatchet.forward(
                key: held.key, roomId: roomId, generation: generation, from: held.hour, to: keep)
        else {
            return held
        }
        let next = HourKey(hour: keep, key: moved)
        do {
            try store.set(Self.encode(next), for: slot(roomId, generation))
        } catch {
            roomKeyLog.error("could not move a room key on")
        }
        cached.withLock { state in
            state[slot(roomId, generation)] = next
        }
        return next
    }

    private func senderKey(_ roomId: Int32, _ generation: Int, held: HourKey, hour: Int, sender: Int32) -> Data? {
        guard
            let hourKey = RoomRatchet.forward(
                key: held.key, roomId: roomId, generation: generation, from: held.hour, to: hour)
        else {
            return nil
        }
        return RoomRatchet.senderKey(
            hourKey: hourKey, roomId: roomId, generation: generation, hour: hour, sender: sender)
    }

    private func held(_ roomId: Int32, _ generation: Int) -> HourKey? {
        let account = slot(roomId, generation)
        if let known = cached.withLock({ $0[account] }) {
            return known
        }
        do {
            guard let stored = try store.data(for: account), let key = Self.decode(stored) else {
                return nil
            }
            cached.withLock { state in
                state[account] = key
            }
            return key
        } catch {
            roomKeyLog.error("could not read a room key")
            return nil
        }
    }

    private func drop(_ roomId: Int32, _ generation: Int) {
        try? store.remove(slot(roomId, generation))
        try? store.remove(retired(roomId, generation))
        cached.withLock { state in
            _ = state.removeValue(forKey: slot(roomId, generation))
        }
    }

    /// The hour, then its key.
    private static func encode(_ key: HourKey) -> Data {
        withUnsafeBytes(of: Int32(truncatingIfNeeded: key.hour).bigEndian) { Data($0) } + key.key
    }

    /// A bare 32-byte key was stored before keys moved on, and counts from ``RoomRatchet/legacyHour``.
    private static func decode(_ stored: Data) -> HourKey? {
        let bytes = [UInt8](stored)
        switch bytes.count {
        case RoomCipher.keySize:
            return HourKey(hour: RoomRatchet.legacyHour, key: Data(bytes))
        case hourSize + RoomCipher.keySize:
            let hour = bytes[0..<hourSize].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
            return HourKey(hour: Int(Int32(bitPattern: hour)), key: Data(bytes[hourSize...]))
        default:
            return nil
        }
    }

    private static func parseSlot(_ account: String) -> RoomGeneration? {
        let parts = account.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2, let roomId = Int32(parts[0]), let generation = Int(parts[1]) else {
            return nil
        }
        return RoomGeneration(roomId: roomId, generation: generation)
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

    private func retired(_ roomId: Int32, _ generation: Int) -> String {
        "\(roomId)/\(generation).retired"
    }

    private func current(_ roomId: Int32) -> String {
        "\(roomId).generation"
    }

    private func superseded(_ roomId: Int32) -> String {
        "\(roomId).superseded"
    }
}

/// One generation of one room's key.
public struct RoomGeneration: Hashable, Sendable {
    public let roomId: Int32
    public let generation: Int

    public init(roomId: Int32, generation: Int) {
        self.roomId = roomId
        self.generation = generation
    }
}

/// What came of trying to open a sealed message.
public enum Opening: Sendable, Equatable {
    /// It opened, for the first time.
    case read(plain: Data)
    /// This phone holds no key for that room and generation, or no longer does.
    case noKey
    /// Sealed in an hour whose key this phone has destroyed, or not yet reached: an old recording, or two clocks more
    /// than an hour apart.
    case outOfHours(hour: Int, now: Int)
    /// Opened before: a copy of a message already read.
    case replayed
    /// Sealed by a build from before hourly keys, which nobody on this one can open.
    case outdated
    /// Not a seal this build reads, or one that was changed or made with another key.
    case unreadable
}
