import Foundation

/// How long a location stays shared before the app stops it on its own.
///
/// A location is not like a message. It keeps describing you long after you
/// stopped paying attention to it, so the question is how long.
public enum ShareDuration: CaseIterable, Sendable {
    case hour
    case fourHours
    case day
    case untilOff

    public var label: String {
        switch self {
        case .hour: "For 1 hour"
        case .fourHours: "For 4 hours"
        case .day: "For 1 day"
        case .untilOff: "Until I turn it off"
        }
    }

    public var duration: Duration? {
        switch self {
        case .hour: .hours(1)
        case .fourHours: .hours(4)
        case .day: .hours(24)
        case .untilOff: nil
        }
    }

    public var name: String {
        switch self {
        case .hour: "HOUR"
        case .fourHours: "FOUR_HOURS"
        case .day: "DAY"
        case .untilOff: "UNTIL_OFF"
        }
    }

    /// When sharing should stop, or nil when it never does on its own.
    public func endsAt(nowMillis: Int64) -> Int64? {
        guard let duration else {
            return nil
        }
        return nowMillis + duration.inWholeMilliseconds
    }

    /// Long enough for an evening out, short enough to be forgotten safely.
    public static let `default`: ShareDuration = .fourHours

    public static func named(name: String?) -> ShareDuration {
        allCases.first { entry in
            entry.name == name
        } ?? `default`
    }
}
