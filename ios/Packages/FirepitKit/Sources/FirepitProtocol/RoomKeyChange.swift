import Foundation

/// How often this phone changes keys for rooms it made.
public enum RoomKeyChange: String, CaseIterable, Sendable, Equatable {
    case daily = "DAILY"
    case weekly = "WEEKLY"
    case never = "NEVER"
}

/// Pure decision for scheduled room-key changes; ported from Android's `ScheduledKeyChange`.
public enum ScheduledKeyChange {
    public static let dayMillis: Int64 = 24 * 60 * 60 * 1_000
    public static let weekMillis: Int64 = 7 * dayMillis

    public static func intervalMillis(setting: RoomKeyChange) -> Int64? {
        switch setting {
        case .daily: dayMillis
        case .weekly: weekMillis
        case .never: nil
        }
    }

    public static func shouldChange(
        isMaker: Bool,
        setting: RoomKeyChange,
        keyAgeMillis: Int64?,
        hasCurrentGenerationEvidence: Bool,
        connected: Bool,
        alreadyRotating: Bool
    ) -> Bool {
        guard let interval = intervalMillis(setting: setting) else { return false }
        return isMaker
            && connected
            && !alreadyRotating
            && hasCurrentGenerationEvidence
            && keyAgeMillis != nil
            && keyAgeMillis! >= interval
    }
}
