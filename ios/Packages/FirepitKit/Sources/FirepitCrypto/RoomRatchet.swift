import Foundation

/// Moves a room's key on once an hour, one way.
///
/// A key that never changes opens everything ever recorded under it, so a phone taken next month would read last
/// month off any recording of the air. Here each hour's key is derived from the hour before, and the phone keeps only
/// the hour just gone (for packets the mesh delivers late). The step cannot be run backwards, so what was said before
/// that is out of reach of anyone who takes the phone, the key store, or a backup of either.
///
/// Every member derives the same keys from the same clock, so none of this costs a byte or a packet: the nonce of each
/// sealed message already carries which hour sealed it (see ``SealedText``).
///
/// Within an hour each sender seals under a key of their own, derived from the hour's key and their node number, so no
/// two members ever share a key and nonce, however busy the room.
///
/// HKDF-SHA256 expand steps (RFC 5869 §2.3), keyed by keys that are already uniformly random, so no extract step is
/// needed. Android: `RoomRatchet`.
public enum RoomRatchet {
    public static let hourMillis: Int64 = 3_600_000

    /// How many bytes of the hour a sealed message carries in its nonce.
    public static let hourTagSize = 2

    /// The hour a key stored before the ratchet existed is taken to belong to: 2026-01-01T00:00Z. Fixed, so phones
    /// that update at different times still agree on every later key without saying a word to each other.
    public static let legacyHour = 490_896

    /// A clock decades out is a broken clock, not a reason to spin.
    private static let maxSteps = 1_000_000

    private static let tagSpan = 1 << (hourTagSize * 8)
    private static let hourInfo = Data("firepit-hour-v1".utf8)
    private static let senderInfo = Data("firepit-sender-v1".utf8)

    public static func hourOf(unixMillis: Int64) -> Int {
        let quotient = unixMillis / hourMillis
        // Floor, not truncation, so a clock before 1970 still counts the way Kotlin's floorDiv does.
        return Int(unixMillis % hourMillis < 0 ? quotient - 1 : quotient)
    }

    /// What a sealed message carries of `hour`: it repeats every 7½ years.
    public static func tagOf(_ hour: Int) -> Int { hour & (tagSpan - 1) }

    /// The hour carrying `tag` that lies nearest `near`.
    public static func hourNear(tag: Int, near: Int) -> Int {
        let base = near - tagOf(near) + (tag & (tagSpan - 1))
        return [base - tagSpan, base, base + tagSpan].min { abs($0 - near) < abs($1 - near) } ?? base
    }

    /// The key for `hour` + 1, from the key for `hour`. There is no way back.
    public static func next(key: Data, roomId: Int32, generation: Int, hour: Int) -> Data {
        expand(key, label(hourInfo, roomId, generation, hour + 1))
    }

    /// The key for `to`, from the key for `from`. Nil when `to` is earlier, which is the whole point, or absurdly far
    /// ahead.
    public static func forward(key: Data, roomId: Int32, generation: Int, from: Int, to: Int) -> Data? {
        guard to >= from, to - from <= maxSteps else {
            return nil
        }
        var current = key
        for hour in from..<to {
            current = next(key: current, roomId: roomId, generation: generation, hour: hour)
        }
        return current
    }

    /// One sender's key for one hour.
    public static func senderKey(hourKey: Data, roomId: Int32, generation: Int, hour: Int, sender: Int32) -> Data {
        expand(hourKey, label(senderInfo, roomId, generation, hour) + bigEndian(sender))
    }

    /// The hour this phone takes to be now. Never earlier than the hour it holds a key for: a clock set back must not
    /// make it seal under an hour whose key it has already destroyed.
    public static func currentHour(heldHour: Int, nowHour: Int) -> Int { max(heldHour, nowHour) }

    /// Whether a message sealed in `hour` may open here: the hour just gone, for packets the mesh delivered late,
    /// this one, and the next, for a sender whose clock runs a little ahead. Nothing older, which is what makes taking
    /// the phone worthless against what was said before.
    public static func opens(heldHour: Int, nowHour: Int, hour: Int) -> Bool {
        let current = currentHour(heldHour: heldHour, nowHour: nowHour)
        return hour >= max(heldHour, current - 1) && hour <= current + 1
    }

    /// The oldest hour worth keeping a key for: the one just gone.
    public static func keepFrom(heldHour: Int, nowHour: Int) -> Int { max(heldHour, nowHour - 1) }

    private static func expand(_ key: Data, _ info: Data) -> Data {
        precondition(key.count == RoomCipher.keySize, "A room key is \(RoomCipher.keySize) bytes, not \(key.count)")
        return RoomCrypto.hmac(key: key, message: info + Data([1]))
    }

    private static func label(_ info: Data, _ roomId: Int32, _ generation: Int, _ hour: Int) -> Data {
        info + bigEndian(roomId) + bigEndian(Int32(truncatingIfNeeded: generation))
            + bigEndian(Int32(truncatingIfNeeded: hour))
    }
}

/// One hour's key, and which hour it is: what a grant or a rotation hands over.
public struct HourKey: Sendable, Equatable {
    public let hour: Int
    public let key: Data

    public init(hour: Int, key: Data) {
        precondition(key.count == RoomCipher.keySize, "A room key is \(RoomCipher.keySize) bytes, not \(key.count)")
        self.hour = hour
        self.key = key
    }
}
