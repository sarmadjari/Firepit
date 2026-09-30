import CryptoKit
import Foundation

/// A short, readable digest of a node's public key.
///
/// Names on a mesh prove nothing: anyone can call themselves anything, on any
/// radio. The key is the part that cannot be borrowed, so two people who read
/// the same fingerprint aloud have checked something worth checking.
public enum KeyFingerprint {
    /// Six bytes: short enough to say out loud, long enough to be worth saying.
    private static let bytes = 6

    public static func of(publicKeyBase64: String?) -> String? {
        guard let key = publicKeyBase64 else {
            return nil
        }
        if key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return nil
        }
        guard let raw = Data(base64Encoded: key) else {
            return nil
        }
        if raw.isEmpty {
            return nil
        }
        return readable(Array(raw))
    }

    /// One line for somebody asking to join: their radio's key and their
    /// phone's key together.
    public static func ofJoin(radioKey: Data, phoneKey: Data) -> String? {
        if radioKey.isEmpty || phoneKey.isEmpty {
            return nil
        }
        var bytes = Data(joinDomain.utf8)
        bytes.append(radioKey)
        bytes.append(phoneKey)
        return readable(Array(bytes))
    }

    private static func readable(_ bytes: [UInt8]) -> String {
        let digest = SHA256.hash(data: Data(bytes))
        let head = digest.prefix(Self.bytes)
        let hex = head.map { byte in
            String(format: "%02X", byte)
        }.joined()
        var groups: [String] = []
        var index = hex.startIndex
        while index < hex.endIndex {
            let end = hex.index(index, offsetBy: 4, limitedBy: hex.endIndex) ?? hex.endIndex
            groups.append(String(hex[index..<end]))
            index = end
        }
        return groups.joined(separator: " ")
    }

    /// Keeps a join line from ever equalling a plain key's, whatever the bytes.
    private static let joinDomain = "firepit-join-v1"
}
