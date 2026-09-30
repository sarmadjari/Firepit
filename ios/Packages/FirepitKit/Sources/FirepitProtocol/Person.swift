import Foundation

/// Who you are, as distinct from what you are carrying.
///
/// The mesh addresses radios, not people: every node number belongs to a device,
/// and a person who moves from their pocket radio to a base station changes node
/// number without becoming someone else.
public struct Person: Hashable, Sendable {
    public var id: Int32
    public var name: String
    public var tag: String
    public var colourSlot: Int?

    public init(id: Int32, name: String, tag: String, colourSlot: Int? = nil) {
        self.id = id
        self.name = name
        self.tag = tag
        self.colourSlot = colourSlot
    }

    /// Applies the same limits as a node name.
    public static func of(id: Int32, name: String, tag: String, colourSlot: Int? = nil) -> Person {
        let trimmedName = OwnerName.longName(text: name)
        return Person(
            id: id,
            name: trimmedName,
            tag: OwnerName.shortName(text: tag.isEmpty ? initialsFor(name: trimmedName) : tag),
            colourSlot: colourSlot
        )
    }

    /// Initials as a person would write them: first name, then surname.
    public static func initialsFor(name: String) -> String {
        let words = name.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(whereSeparator: { $0.isWhitespace })
            .filter { word in
                !word.isEmpty
            }
        guard let first = words.first?.first else {
            return ""
        }
        let second: Character?
        if words.count >= 2 {
            second = words.last?.first
        } else {
            let word = words.first ?? ""
            second = word.dropFirst().first
        }
        return (String(first) + (second.map(String.init) ?? "")).uppercased()
    }
}
