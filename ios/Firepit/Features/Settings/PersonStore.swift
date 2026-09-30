import FirepitData
import FirepitProtocol
import Foundation
import Observation

/// You, on this phone. Ported from android/app/…/settings/PersonStore.kt.
///
/// Deliberately not the radio's owner name: this is the person, and a person keeps their name when they pick up a
/// different radio. Nothing here is written to a device, so it can be set with no radio connected at all.
@Observable
final class PersonStore {
    enum PersonError: LocalizedError, Equatable {
        case emptyName

        var errorDescription: String? { String(localized: "A name cannot be empty") }
    }

    /// Nil until this phone has been given a name.
    private(set) var person: Person?

    @ObservationIgnored private let rooms: RoomRepository
    @ObservationIgnored private let defaults: UserDefaults

    init(rooms: RoomRepository, defaults: UserDefaults = .standard) {
        self.rooms = rooms
        self.defaults = defaults
        person = Self.read(from: defaults)
        // Restored rather than re-sent: a relaunch is not news, but a room joined later still needs something to
        // introduce.
        if let person {
            rooms.rememberPersonCard(name: person.name, tag: person.tag, colourSlot: person.colourSlot)
        }
    }

    func save(name: String, tag: String) throws {
        let saved = Person.of(
            id: Self.storedId(in: defaults) ?? Self.newId(),
            name: name,
            tag: tag,
            colourSlot: person?.colourSlot
        )
        guard !saved.name.isEmpty else { throw PersonError.emptyName }
        write(saved)
    }

    func chooseColour(_ slot: Int?) {
        guard var current = person else { return }
        current.colourSlot = slot
        write(current)
    }

    private func write(_ person: Person) {
        defaults.set(Int(person.id), forKey: Self.keyId)
        defaults.set(person.name, forKey: Self.keyName)
        defaults.set(person.tag, forKey: Self.keyTag)
        if let slot = person.colourSlot {
            defaults.set(slot, forKey: Self.keySlot)
        } else {
            defaults.removeObject(forKey: Self.keySlot)
        }
        self.person = person
        // Told to the rooms we are already in, and to nobody else. A room we join later is told at the time we join
        // it.
        let rooms = rooms
        Task {
            await rooms.sharePersonCard(name: person.name, tag: person.tag, colourSlot: person.colourSlot)
        }
    }

    private static func read(from defaults: UserDefaults) -> Person? {
        guard let name = defaults.string(forKey: keyName) else { return nil }
        return Person(
            id: storedId(in: defaults) ?? newId(),
            name: name,
            tag: defaults.string(forKey: keyTag) ?? "",
            colourSlot: defaults.object(forKey: keySlot) == nil ? nil : defaults.integer(forKey: keySlot)
        )
    }

    private static func storedId(in defaults: UserDefaults) -> Int32? {
        (defaults.object(forKey: keyId) as? NSNumber).map { Int32(truncatingIfNeeded: $0.intValue) }
    }

    /// Never a node number: a person outlives the radio they happen to hold.
    private static func newId() -> Int32 {
        Int32.random(in: .min ... .max)
    }

    private static let keyId = "person_id"
    private static let keyName = "person_name"
    private static let keyTag = "person_tag"
    /// Kept from when this setting held only a colour, so nobody loses theirs.
    private static let keySlot = "identity_slot"
}
