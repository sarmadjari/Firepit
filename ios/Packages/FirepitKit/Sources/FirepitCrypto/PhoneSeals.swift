import CryptoKit
import Foundation

/// A P-256 private key that can agree a secret: a software key, or one that never leaves the Secure Enclave.
///
/// Either produces the raw x-coordinate of the shared point, which is exactly what Android's `KeyAgreement("ECDH")`
/// returns, so a phone key held in hardware interoperates with one held in the Android Keystore.
public protocol PhoneKeyAgreementKey: Sendable {
    var publicKey: P256.KeyAgreement.PublicKey { get }
    func sharedSecretFromKeyAgreement(with publicKeyShare: P256.KeyAgreement.PublicKey) throws -> SharedSecret
}

extension P256.KeyAgreement.PrivateKey: PhoneKeyAgreementKey {}

#if !os(watchOS)
    extension SecureEnclave.P256.KeyAgreement.PrivateKey: PhoneKeyAgreementKey {}
#endif

public enum FirepitCryptoError: Error, Equatable {
    /// A peer key that is not a point on P-256; callers validate with `KeyEnvelope.isValidPublicKey` first.
    case unusablePublicKey
}

/// Hands one phone a secret that the radio carrying it cannot read.
///
/// A grant or a key rotation travels as a PKI direct message, which the firmware encrypts to the recipient's *radio*.
/// Whoever holds that radio can read its private key over Bluetooth, so the firmware's layer alone would give the
/// room's own key to anybody who picks the hardware up. This seals it a second time, to a key pair that only ever
/// exists on the phone.
///
/// ECDH on P-256, HKDF-SHA256 and AES-256-GCM. Nothing here is invented beyond putting the three together in the
/// standard way — and byte for byte the way Android does.
public enum KeyEnvelope {
    /// A compressed P-256 point: a parity byte, then the x coordinate.
    public static let publicKeySize = 33

    /// The sender's one-off key, then nonce, the sealed 32-byte secret and its tag.
    public static let sealedSize = publicKeySize + RoomCipher.nonceSize + RoomCipher.keySize + RoomCipher.tagSize

    private static let info = Data("firepit-key-envelope-v1".utf8)
    private static let hedgedInfo = Data("firepit-key-envelope-v2".utf8)
    private static let inviteHedgeInfo = Data("firepit-invite-hedge-v1".utf8)
    private static let inviteSecretSize = 16

    public static func generateKeyPair() -> P256.KeyAgreement.PrivateKey { P256.KeyAgreement.PrivateKey() }

    /// What goes on the wire and into other people's databases.
    public static func publicBytes(_ key: P256.KeyAgreement.PublicKey) -> Data { key.compressedRepresentation }

    /// For storage only; the caller keeps it in the keychain. Never leaves the device, so its format is iOS's own
    /// (Android stores PKCS#8 in its keystore for the same purpose).
    public static func privateBytes(_ key: P256.KeyAgreement.PrivateKey) -> Data { key.rawRepresentation }

    public static func restorePrivate(_ encoded: Data) -> P256.KeyAgreement.PrivateKey? {
        try? P256.KeyAgreement.PrivateKey(rawRepresentation: encoded)
    }

    /// True when `bytes` is a point on the curve, and so something a secret can be sealed to.
    public static func isValidPublicKey(_ bytes: Data) -> Bool { decode(bytes) != nil }

    /// Binds a sealed secret to one room, one generation, one recipient and the hour it is the key for, so it cannot be
    /// replayed as a different room's key, handed to somebody else, or relabelled as another hour's.
    public static func contextOf(roomId: Int32, generation: Int32, recipientNodeNum: Int32, hour: Int32) -> Data {
        bigEndian(roomId) + bigEndian(generation) + bigEndian(recipientNodeNum) + bigEndian(hour)
    }

    /// The in-person QR secret mixed into the grant envelope. The secret itself never goes over the radio.
    public static func inviteHedge(secret: Data, roomId: Int32, inviteId: Int32) -> Data {
        precondition(secret.count == inviteSecretSize, "invite secret must be \(inviteSecretSize) bytes")
        return RoomCrypto.hmac(
            key: secret,
            message: inviteHedgeInfo + bigEndian(roomId) + bigEndian(inviteId) + Data([1])
        )
    }

    /// Seals `secret` so that only the holder of the private half of `recipient` can open it.
    public static func seal(recipient: Data, secret: Data, context: Data, hedge: Data? = nil) throws -> Data {
        guard let recipientKey = decode(recipient) else { throw FirepitCryptoError.unusablePublicKey }
        let ephemeral = P256.KeyAgreement.PrivateKey()
        let ephemeralBytes = publicBytes(ephemeral.publicKey)
        let shared = try ephemeral.sharedSecretFromKeyAgreement(with: recipientKey)
        let key = derive(shared: shared, ephemeral: ephemeralBytes, recipient: recipient, context: context, hedge: hedge)
        return ephemeralBytes + RoomCipher.seal(key: key, plaintext: secret, context: context)
    }

    /// Nil when this was not sealed to us, the context differs, or a byte was changed. `ownPublic` is the recipient's
    /// own public key, which the sender mixed into the derivation.
    public static func open(
        privateKey: any PhoneKeyAgreementKey, ownPublic: Data, sealed: Data,
        context: Data, hedge: Data? = nil
    ) -> Data? {
        guard sealed.count >= publicKeySize + RoomCipher.overhead else { return nil }
        let ephemeralBytes = Data(sealed.prefix(publicKeySize))
        // Validated before use: agreeing on a point that is not on the curve is how a static private key is leaked a
        // few bits at a time.
        guard let ephemeral = decode(ephemeralBytes),
            let shared = try? privateKey.sharedSecretFromKeyAgreement(with: ephemeral)
        else { return nil }
        let key = derive(shared: shared, ephemeral: ephemeralBytes, recipient: ownPublic, context: context, hedge: hedge)
        return RoomCipher.open(key: key, sealed: Data(sealed.dropFirst(publicKeySize)), context: context)
    }

    /// Back to a key, or nil for anything that is not a compressed point on P-256 (CryptoKit decompresses and checks
    /// the
    /// curve equation, as Android's hand-written square root does).
    static func decode(_ bytes: Data) -> P256.KeyAgreement.PublicKey? {
        guard bytes.count == publicKeySize, let prefix = bytes.first, prefix == 0x02 || prefix == 0x03 else {
            return nil
        }
        return try? P256.KeyAgreement.PublicKey(compressedRepresentation: bytes)
    }

    /// HKDF-SHA256 (RFC 5869), one block: exactly one AES-256 key is needed.
    private static func derive(
        shared: SharedSecret,
        ephemeral: Data,
        recipient: Data,
        context: Data,
        hedge: Data?
    ) -> Data {
        let material = hedge.map { rawBytes(shared) + $0 } ?? rawBytes(shared)
        let prk = RoomCrypto.hmac(key: ephemeral + recipient, message: material)
        return RoomCrypto.hmac(key: prk, message: (hedge == nil ? info : hedgedInfo) + context + Data([1]))
    }
}

/// One person's words, sealed from this phone to theirs.
///
/// A direct message already travels under the firmware's PKI, but that is the radios' encryption: a radio hands its
/// private key to any phone that connects to it, so whoever holds either radio could read everything it carried. This
/// seals the words again under a key only the two phones can derive, from the same P-256 keys a room key is sealed to.
///
/// Static-static ECDH, HMAC-SHA256 and AES-256-GCM. Both phones derive the same secret; when they share a room, that
/// room's hourly key is mixed in too. The direction and both node numbers are mixed into the key and bound as
/// associated data, so a message cannot be turned round or re-addressed. Opening one proves it came from whoever holds
/// the sender's phone key, which a radio in between cannot forge.
public enum DirectSeal {
    private static let firstVersion: UInt8 = 0x01
    private static let version: UInt8 = 0x02
    private static let header = 1
    private static let firstInfo = Data("firepit-direct-v1".utf8)
    private static let info = Data("firepit-direct-v2".utf8)
    private static let roomInfo = Data("firepit-direct-room-v1".utf8)
    private static let randomSize = RoomCipher.nonceSize - RoomRatchet.hourTagSize

    /// What sealing costs against the message budget.
    public static let overhead = header + RoomCipher.overhead

    public struct RoomSecret: Sendable, Equatable {
        public let roomId: Int32
        public let generation: Int
        public let hour: Int
        public let key: Data

        public init(roomId: Int32, generation: Int, hour: Int, key: Data) {
            self.roomId = roomId
            self.generation = generation
            self.hour = hour
            self.key = key
        }
    }

    public struct Opening: Sendable, Equatable {
        public let plain: Data
        public let room: RoomSecret?
        public let hour: Int?
        public let nonce: Data?
    }

    /// Seals `plaintext` for the holder of `peerPublic`. `ownPublic` is this phone's own public key; both halves go
    /// into
    /// the derivation in a fixed order, so the two phones agree on it.
    public static func seal(
        ownPrivate: any PhoneKeyAgreementKey, ownPublic: Data, peerPublic: Data,
        plaintext: Data, context: Data, room: RoomSecret? = nil
    ) throws -> Data {
        guard
            let key = keyFor(
                ownPrivate: ownPrivate, ownPublic: ownPublic, peerPublic: peerPublic, context: context, room: room)
        else { throw FirepitCryptoError.unusablePublicKey }
        if let room {
            let tag = RoomRatchet.tagOf(room.hour)
            let nonce =
                Data([UInt8(truncatingIfNeeded: tag >> 8), UInt8(truncatingIfNeeded: tag)])
                + randomBytes(randomSize)
            return Data([version]) + RoomCipher.seal(key: key, plaintext: plaintext, context: context, nonce: nonce)
        }
        return Data([firstVersion]) + RoomCipher.seal(key: key, plaintext: plaintext, context: context)
    }

    /// Nil when this was not sealed between these two phones, the context differs, a byte was changed, or the version
    /// is one this build does not know.
    public static func open(
        ownPrivate: any PhoneKeyAgreementKey, ownPublic: Data, peerPublic: Data, sealed: Data,
        context: Data, rooms: [RoomSecret] = []
    ) -> Opening? {
        guard sealed.count > header + RoomCipher.overhead else { return nil }
        if sealed[sealed.startIndex] == firstVersion {
            guard
                let key = keyFor(
                    ownPrivate: ownPrivate, ownPublic: ownPublic, peerPublic: peerPublic, context: context, room: nil),
                let plain = RoomCipher.open(key: key, sealed: Data(sealed.dropFirst(header)), context: context)
            else { return nil }
            return Opening(plain: plain, room: nil, hour: nil, nonce: nil)
        }
        guard sealed[sealed.startIndex] == version, let nonce = nonceOf(sealed) else { return nil }
        guard let peer = KeyEnvelope.decode(peerPublic),
            let shared = try? ownPrivate.sharedSecretFromKeyAgreement(with: peer)
        else { return nil }
        let sharedBytes = rawBytes(shared)
        for room in rooms {
            guard
                let key = keyFor(
                    shared: sharedBytes, ownPublic: ownPublic, peerPublic: peerPublic, context: context, room: room),
                let plain = RoomCipher.open(key: key, sealed: Data(sealed.dropFirst(header)), context: context)
            else { continue }
            return Opening(plain: plain, room: room, hour: room.hour, nonce: nonce)
        }
        return nil
    }

    public static func hourTagOf(_ payload: Data) -> Int? {
        guard payload.count >= header + RoomCipher.overhead, payload[payload.startIndex] == version else {
            return nil
        }
        let high = Int(payload[payload.startIndex + header])
        let low = Int(payload[payload.startIndex + header + 1])
        return (high << 8) | low
    }

    public static func nonceOf(_ payload: Data) -> Data? {
        guard hourTagOf(payload) != nil else { return nil }
        let start = payload.startIndex + header
        return Data(payload[start..<(start + RoomCipher.nonceSize)])
    }

    /// Binds a message to who sent it and who it is for, in that order, so the reply direction uses a different key and
    /// neither can be re-addressed.
    public static func contextOf(senderNodeNum: Int32, recipientNodeNum: Int32) -> Data {
        bigEndian(senderNodeNum) + bigEndian(recipientNodeNum)
    }

    private static func keyFor(
        ownPrivate: any PhoneKeyAgreementKey, ownPublic: Data, peerPublic: Data,
        context: Data, room: RoomSecret?
    ) -> Data? {
        // Validated before use: agreeing on a point that is not on the curve is how a static private key is leaked a
        // few bits at a time.
        guard let peer = KeyEnvelope.decode(peerPublic),
            let shared = try? ownPrivate.sharedSecretFromKeyAgreement(with: peer)
        else { return nil }
        return keyFor(shared: rawBytes(shared), ownPublic: ownPublic, peerPublic: peerPublic, context: context, room: room)
    }

    private static func keyFor(shared: Data, ownPublic: Data, peerPublic: Data, context: Data, room: RoomSecret?) -> Data? {
        let material = room.map { shared + roomPart($0) } ?? shared
        let prk = RoomCrypto.hmac(key: ordered(ownPublic, peerPublic), message: material)
        return RoomCrypto.hmac(key: prk, message: (room == nil ? firstInfo : info) + context + Data([1]))
    }

    public static func roomPart(_ room: RoomSecret) -> Data {
        RoomCrypto.hmac(
            key: room.key,
            message: roomInfo + bigEndian(room.roomId) + bigEndian(Int32(truncatingIfNeeded: room.generation))
                + bigEndian(Int32(truncatingIfNeeded: room.hour)) + Data([1]))
    }

    /// Both public keys in one order both phones agree on, whichever of them is asking: unsigned bytewise, and the
    /// shorter first when one is a prefix of the other.
    static func ordered(_ a: Data, _ b: Data) -> Data {
        for (left, right) in zip(a, b) where left != right {
            return left < right ? a + b : b + a
        }
        return a.count <= b.count ? a + b : b + a
    }
}

/// The shared point's x coordinate, exactly the bytes Java's `KeyAgreement.generateSecret()` returns.
func rawBytes(_ secret: SharedSecret) -> Data {
    secret.withUnsafeBytes { Data($0) }
}
