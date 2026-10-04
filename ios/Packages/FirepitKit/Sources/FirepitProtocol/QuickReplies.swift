import Foundation

/// Quick replies: short, ready-made messages that one tap sends (UX §5.4). Ported from
/// android/core/protocol/…/QuickReplies.kt.
///
/// Each is a whole LoRa message, so they are kept short: at `maxBytes` a quick reply never comes near a room's limit,
/// sealed or not, and goes out through the same paced queue as anything typed.
public enum QuickReplies {
    public static let maxBytes = 40

    /// Enough to choose from in one glance over the composer.
    public static let maxCount = 10

    /// What a phone starts with; editable in Settings › Quick replies.
    public static let defaults = ["On my way", "Where are you?", "Wait for me", "I'm here", "OK"]

    /// One line, trimmed, and within `maxBytes` without splitting a character. Nil when nothing is left to send.
    public static func clean(_ text: String) -> String? {
        let oneLine = text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        let cut = MeshConstants.truncateToBytes(oneLine, maxBytes: maxBytes).trimmingCharacters(in: .whitespaces)
        return cut.isEmpty ? nil : cut
    }

    /// The list as it is kept: each one cleaned, empty ones and repeats dropped, at most `maxCount`.
    public static func normalise(_ replies: [String]) -> [String] {
        var kept: [String] = []
        for reply in replies {
            guard let cleaned = clean(reply), !kept.contains(cleaned) else { continue }
            kept.append(cleaned)
            if kept.count == maxCount { break }
        }
        return kept
    }
}
