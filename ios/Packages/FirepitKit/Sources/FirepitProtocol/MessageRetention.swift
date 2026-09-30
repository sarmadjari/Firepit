/// How long messages are kept before the app deletes them.
///
/// There is deliberately no "keep everything": a chat that never forgets becomes
/// a record of everyone who was ever in it, sitting on a phone that can be lost
/// or taken. The only choice is how long.
public enum MessageRetention: CaseIterable, Sendable {
    case day
    case week
    case month

    public var label: String {
        switch self {
        case .day: "1 day"
        case .week: "1 week"
        case .month: "1 month"
        }
    }

    public var days: Int {
        switch self {
        case .day: 1
        case .week: 7
        case .month: 30
        }
    }

    public var name: String {
        switch self {
        case .day: "DAY"
        case .week: "WEEK"
        case .month: "MONTH"
        }
    }

    /// Messages sent before this instant are deleted.
    public func cutoff(nowMillis: Int64) -> Int64 {
        nowMillis - Int64(days) * MessageRetention.dayMillis
    }

    private static let dayMillis: Int64 = 24 * 60 * 60 * 1_000

    /// Long enough to hold a conversation, short enough not to be a record.
    public static let `default`: MessageRetention = .week

    public static func named(name: String?) -> MessageRetention {
        allCases.first { entry in
            entry.name == name
        } ?? `default`
    }
}
