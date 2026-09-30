import Foundation

/// Text arriving from the mesh is attacker-controlled.
///
/// Strip anything that could corrupt the UI and cap the length, but keep the result recognisable rather than silently
/// dropping the message.
public func sanitizeMeshText(_ raw: String, maxChars: Int = 500) -> String {
    String(
        raw.filter { character in
            character == "\n"
                || !character.unicodeScalars.contains { scalar in
                    CharacterSet.controlCharacters.contains(scalar)
                }
        }.prefix(maxChars)
    )
    .trimmingCharacters(in: .whitespacesAndNewlines)
}
