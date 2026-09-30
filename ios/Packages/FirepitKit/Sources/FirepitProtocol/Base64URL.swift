import Foundation

/// URL-safe base64 exactly as Java's `Base64.getUrlEncoder().withoutPadding()` / `getUrlDecoder()` do it, so a code
/// or link one platform writes is read the same way on the other.
public enum Base64URL {
    /// URL-safe alphabet, padding stripped.
    public static func encode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Nil for anything Java's URL decoder would reject: characters outside `A-Z a-z 0-9 - _`, padding anywhere but the
    /// end, or a length no encoding produces. Trailing padding is optional.
    public static func decode(_ text: String) -> Data? {
        var body = Substring(text)
        var padding = 0
        while body.hasSuffix("=") {
            body = body.dropLast()
            padding += 1
        }
        guard padding <= 2 else { return nil }
        for byte in body.utf8 {
            let valid =
                (byte >= 0x41 && byte <= 0x5A) || (byte >= 0x61 && byte <= 0x7A)
                || (byte >= 0x30 && byte <= 0x39) || byte == 0x2D || byte == 0x5F
            if !valid { return nil }
        }
        let remainder = body.utf8.count % 4
        if remainder == 1 { return nil }
        if padding > 0 && (remainder == 0 || padding != 4 - remainder) { return nil }
        let standard =
            body
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let padded = remainder == 0 ? standard : standard + String(repeating: "=", count: 4 - remainder)
        return Data(base64Encoded: padded)
    }
}
