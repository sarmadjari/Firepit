import FirepitModel
import Foundation

/// The little that must outlive a radio session.
///
/// Our own node number is stored so the map can still tell which marker is us when the radio is not connected.
/// Everything else about a node comes from the mesh and is already in the database.
public final class SessionStore: Sendable {
    private let defaults: Mutex<UserDefaults>

    public init(defaults: UserDefaults = .standard) {
        self.defaults = Mutex(defaults)
    }

    public var myNodeNum: Int32? {
        get {
            defaults.withLock { defaults in
                let value = defaults.integer(forKey: Self.myNodeNumKey)
                return value == 0 ? nil : Int32(value)
            }
        }
        set {
            defaults.withLock { defaults in
                defaults.set(newValue.map(Int.init) ?? 0, forKey: Self.myNodeNumKey)
            }
        }
    }

    /// BLE identifier of the last radio we connected to, for reconnecting without asking.
    public var lastRadioId: String? {
        get {
            defaults.withLock { defaults in
                defaults.string(forKey: Self.lastRadioKey)
            }
        }
        set {
            defaults.withLock { defaults in
                defaults.set(newValue, forKey: Self.lastRadioKey)
            }
        }
    }

    /// Whether history kept before rooms were recorded on each message has been filed under rooms in their slots.
    public var historyFiledByRoom: Bool {
        get {
            defaults.withLock { defaults in
                defaults.bool(forKey: Self.historyFiledKey)
            }
        }
        set {
            defaults.withLock { defaults in
                defaults.set(newValue, forKey: Self.historyFiledKey)
            }
        }
    }

    private static let myNodeNumKey = "my_node_num"
    private static let lastRadioKey = "last_radio_id"
    private static let historyFiledKey = "history_filed_by_room"
}
