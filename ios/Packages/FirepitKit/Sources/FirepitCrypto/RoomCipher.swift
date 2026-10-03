import CryptoKit
import FirepitProtocol
import Foundation

/// Encrypts a room's words with a key the radio never holds.
///
/// The channel key cannot keep a room private, because it lives on the radio: anyone holding the hardware can read it
/// out with the official app and then decrypt everything. Sealing the content again under a key that only ever sits on
/// members' phones leaves such a person with a timestamp and a node number, and nothing to read.
///
/// It also gives a room something Meshtastic channels do not have: tampering is detected rather than delivered.
///
/// AES-256-GCM from the platform (Android: `AES/GCM/NoPadding`), because inventing a construction here would be the
/// most dangerous thing in the codebase. The layout is Java's: nonce, then ciphertext with the tag appended.
public enum RoomCipher {
    public static let keySize = 32
    public static let nonceSize = 12
    public static let tagSize = 16

    /// What sealing costs against the text budget.
    public static let overhead = nonceSize + tagSize

    public static func generateKey() -> Data { randomBytes(keySize) }

    /// Returns nonce followed by ciphertext and tag.
    ///
    /// The nonce is random and sent rather than derived from anything in the packet: repeating one under the same key
    /// breaks GCM completely, and a packet id is not ours to guarantee unique.
    ///
    /// `context` is authenticated but not sent — bind the room and sender to it so a sealed message cannot be replayed
    /// into another room or re-attributed.
    public static func seal(key: Data, plaintext: Data, context: Data = Data()) -> Data {
        seal(key: key, plaintext: plaintext, context: context, nonce: randomBytes(nonceSize))
    }

    /// With a nonce the caller built, for a format that carries something of its own in it. Whatever that is, the
    /// caller still owes GCM a nonce that never repeats under `key`.
    static func seal(key: Data, plaintext: Data, context: Data, nonce: Data) -> Data {
        precondition(key.count == keySize, "A room key is \(keySize) bytes, not \(key.count)")
        precondition(nonce.count == nonceSize, "A nonce is \(nonceSize) bytes, not \(nonce.count)")
        // A 12-byte nonce always has a combined representation; sealing with a valid key cannot fail.
        guard
            let gcmNonce = try? AES.GCM.Nonce(data: nonce),
            let box = try? AES.GCM.seal(
                plaintext, using: SymmetricKey(data: key), nonce: gcmNonce,
                authenticating: context),
            let combined = box.combined
        else { preconditionFailure("AES-GCM sealing failed") }
        return combined
    }

    /// Nil when the key is wrong, the context differs, or a byte was changed.
    public static func open(key: Data, sealed: Data, context: Data = Data()) -> Data? {
        guard sealed.count >= nonceSize + tagSize, key.count == keySize,
            let box = try? AES.GCM.SealedBox(combined: sealed)
        else { return nil }
        return try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: context)
    }
}

/// A sealed payload as it travels: a version, a nonce, then ciphertext and tag.
///
/// Carried in MeshChatControl on PRIVATE_APP, the way the rest of this protocol travels, so a client that is not
/// Firepit ignores it and the radio's own screen does not display it. The version byte exists so a later format is
/// recognised rather than mis-read.
///
/// Words and receipts use the same envelope, so a listener cannot tell a conversation from an acknowledgement by the
/// shape of the traffic.
///
/// Version 2 seals under one sender's key for one hour (``RoomRatchet``). The first two bytes of the nonce say which
/// hour, so the receiver knows which key to derive without a byte more on the air; the other ten are random. Version 1,
/// which sealed under a key that never changed, is no longer read.
public enum SealedText {
    private static let version: UInt8 = 0x02
    private static let header = 1
    private static let randomSize = RoomCipher.nonceSize - RoomRatchet.hourTagSize

    /// What sealing costs against the message budget.
    public static let overhead = header + RoomCipher.overhead

    /// What is left for the person typing.
    public static let maxTextBytes = MeshConstants.maxTextBytes - overhead

    /// Sealed under `key`, one sender's key for `hour`. Ten random bytes of nonce under a key nobody else seals with:
    /// a repeat is not a risk worth counting.
    public static func seal(key: Data, hour: Int, plaintext: Data, context: Data) -> Data {
        let tag = RoomRatchet.tagOf(hour)
        let nonce =
            Data([UInt8(truncatingIfNeeded: tag >> 8), UInt8(truncatingIfNeeded: tag)]) + randomBytes(randomSize)
        return Data([version]) + RoomCipher.seal(key: key, plaintext: plaintext, context: context, nonce: nonce)
    }

    /// Which hour `payload` says it was sealed in, or nil when it is not something this build reads.
    public static func hourTagOf(_ payload: Data) -> Int? {
        guard payload.count >= header + RoomCipher.overhead, payload[payload.startIndex] == version else {
            return nil
        }
        let high = Int(payload[payload.startIndex + header])
        let low = Int(payload[payload.startIndex + header + 1])
        return (high << 8) | low
    }

    /// Different for every message ever sealed, so a second arrival of one is a copy.
    public static func nonceOf(_ payload: Data) -> Data? {
        guard hourTagOf(payload) != nil else {
            return nil
        }
        let start = payload.startIndex + header
        return Data(payload[start..<(start + RoomCipher.nonceSize)])
    }

    /// Nil when this is not ours to read: a wrong key, a changed byte, or a version this build does not know. The
    /// caller says a message arrived and could not be opened, rather than showing rubbish as though it were words.
    public static func open(key: Data, payload: Data, context: Data) -> Data? {
        guard hourTagOf(payload) != nil else {
            return nil
        }
        return RoomCipher.open(key: key, sealed: payload.dropFirst(header), context: context)
    }

    /// Binds a message to where and from whom it was sent, so one cannot be lifted into another room or re-attributed
    /// to someone else.
    public static func contextOf(roomId: Int32, senderNodeNum: Int32) -> Data {
        bigEndian(roomId) + bigEndian(senderNodeNum)
    }
}

/// `value` as four big-endian bytes (Android's `ushr` shifts), the order every Firepit context and HMAC input uses.
func bigEndian(_ value: Int32) -> Data {
    withUnsafeBytes(of: value.bigEndian) { Data($0) }
}

/// Bytes from the system CSPRNG (Android: `SecureRandom`).
func randomBytes(_ count: Int) -> Data {
    var bytes = [UInt8](repeating: 0, count: count)
    let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
    precondition(status == errSecSuccess, "the system random generator failed")
    return Data(bytes)
}

/// Equal-length comparison whose timing does not depend on where the inputs differ (Java's `MessageDigest.isEqual`).
func constantTimeEquals(_ lhs: Data, _ rhs: Data) -> Bool {
    guard lhs.count == rhs.count else { return false }
    var difference: UInt8 = 0
    for (left, right) in zip(lhs, rhs) {
        difference |= left ^ right
    }
    return difference == 0
}
