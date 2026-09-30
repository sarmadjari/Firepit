import CryptoKit
import Foundation

/// Room keys and the rotating QR token.
///
/// HMAC-SHA256 and the system CSPRNG, as on Android (JDK primitives there, CryptoKit here).
public enum RoomCrypto {
    public static let pskSize = 32
    public static let tokenSize = 8

    /// How long one QR code stays valid before it is redrawn.
    public static let rotationSeconds: Int64 = 8

    /// Windows either side of the scanner's own that are still accepted, to absorb clock skew between two phones. Two
    /// windows is ~16 s.
    public static let windowTolerance: Int32 = 2

    /// How far back the inviter will recognise one of its own tokens.
    ///
    /// Deliberately short: the joiner replies the moment it scans, because the inviter's public key is in the code and
    /// nothing has to propagate first. Every window past that is time a photographed code stays usable.
    public static let defaultLookbackWindows: Int32 = 4

    private static let inviteContext = Data("meshchat-invite-v1".utf8)

    /// A room's pre-shared key: 32 bytes for AES-256.
    ///
    /// Never derived from a name or anything guessable, and never empty — an empty PSK makes the firmware silently fall
    /// back to the primary key.
    public static func generatePsk() -> Data { randomBytes(pskSize) }

    /// Non-zero identifier that survives renames, re-indexing and key rotation.
    public static func generateRoomId() -> Int32 {
        var id: Int32 = 0
        while id == 0 {
            id = randomBytes(4).withUnsafeBytes { $0.loadUnaligned(as: Int32.self) }
        }
        return id
    }

    /// Derived from the room key, so every current key-holder can issue invites without any of them holding extra
    /// authority.
    public static func inviteKey(roomPsk: Data, roomId: Int32, generation: Int32) -> Data {
        precondition(roomPsk.count == pskSize, "room PSK must be \(pskSize) bytes, was \(roomPsk.count)")
        return hmac(key: roomPsk, message: inviteContext + bigEndian(roomId) + bigEndian(generation))
    }

    /// Which rotation window a moment falls in (Android truncates the 64-bit quotient to an Int the same way).
    public static func windowFor(epochMillis: Int64) -> Int32 {
        Int32(truncatingIfNeeded: epochMillis / 1000 / rotationSeconds)
    }

    /// Binds an invite to one inviter and one moment. It narrows the window in which a shoulder-surfed code is usable;
    /// it does not revoke the room key afterwards, which nothing can.
    public static func token(inviteKey: Data, inviterNodeNum: Int32, window: Int32) -> Data {
        hmac(key: inviteKey, message: bigEndian(inviterNodeNum) + bigEndian(window)).prefix(tokenSize)
    }

    /// True when `token` was minted by us within the last `lookbackWindows`.
    ///
    /// A join hello echoes the token but not the window it came from, so the inviter has to search. Only the inviter
    /// can do this at all: the token is an HMAC under the room's key, and a scanner holds no key until it is let in.
    public static func matchesRecentToken(
        inviteKey: Data,
        inviterNodeNum: Int32,
        token: Data,
        nowMillis: Int64,
        lookbackWindows: Int32 = defaultLookbackWindows
    ) -> Bool {
        guard token.count == tokenSize else { return false }
        let current = windowFor(epochMillis: nowMillis)
        // Always walk the whole range so timing does not reveal which window hit.
        var matches = 0
        for candidate in (current - lookbackWindows)...(current + windowTolerance) {
            if constantTimeEquals(
                token,
                self.token(
                    inviteKey: inviteKey, inviterNodeNum: inviterNodeNum,
                    window: candidate))
            {
                matches += 1
            }
        }
        return matches > 0
    }

    static func hmac(key: Data, message: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: key)))
    }
}
