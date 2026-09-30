import Foundation

/// How long a room outlives the talking in it.
///
/// A room nobody uses is a key sitting on a phone and a slot the radio cannot
/// give to anything else. Left alone it survives the trip it was made for by
/// months.
public enum RoomLifetime: CaseIterable, Sendable {
    case forever
    case month
    case quarter

    public var label: String {
        switch self {
        case .forever: "Keep until I leave"
        case .month: "After a month of silence"
        case .quarter: "After three months of silence"
        }
    }

    public var silence: Duration? {
        switch self {
        case .forever: nil
        case .month: .days(30)
        case .quarter: .days(90)
        }
    }

    public var name: String {
        switch self {
        case .forever: "FOREVER"
        case .month: "MONTH"
        case .quarter: "QUARTER"
        }
    }

    /// Nothing is forgotten unless it is asked for: leaving a room is not undoable.
    public static let `default`: RoomLifetime = .forever

    public static func named(name: String?) -> RoomLifetime {
        allCases.first { entry in
            entry.name == name
        } ?? `default`
    }

    /// Which rooms have gone quiet for longer than `lifetime`.
    public static func silentRooms(
        lastActivity: [Int32: Int64],
        lifetime: RoomLifetime,
        nowMillis: Int64
    ) -> [Int32] {
        guard let silence = lifetime.silence else {
            return []
        }
        let cutoff = nowMillis - silence.inWholeMilliseconds
        return
            lastActivity
            .filter { entry in
                entry.value >= 1 && entry.value < cutoff
            }
            .map { entry in
                entry.key
            }
            .sorted()
    }
}
