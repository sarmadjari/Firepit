import Foundation

/// The name a node broadcasts about itself.
///
/// Both halves are capped in bytes, not characters, because the firmware stores
/// them in fixed buffers.
public enum OwnerName {
    /// Firmware allows 40 bytes including its terminator.
    public static let maxLongBytes = 39

    /// Firmware allows 5 bytes including its terminator.
    public static let maxShortBytes = 4

    public static func longName(text: String) -> String {
        MeshConstants.truncateToBytes(text.trimmingCharacters(in: .whitespacesAndNewlines), maxBytes: maxLongBytes)
    }

    public static func shortName(text: String) -> String {
        MeshConstants.truncateToBytes(text.trimmingCharacters(in: .whitespacesAndNewlines), maxBytes: maxShortBytes)
    }

    /// A tag for someone who has not chosen one.
    public static func suggestShort(longName: String) -> String {
        let words = longName.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(whereSeparator: { $0.isWhitespace })
            .filter { word in
                !word.isEmpty
            }
        let initials = words.compactMap { word in
            word.first?.uppercased()
        }
        let candidate =
            initials.count >= 2
            ? initials.joined() : longName.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return shortName(text: candidate)
    }

    /// True when the radio can carry both halves as given.
    public static func fits(longName: String, shortName: String) -> Bool {
        longName.utf8.count <= maxLongBytes && shortName.utf8.count <= maxShortBytes
    }
}
