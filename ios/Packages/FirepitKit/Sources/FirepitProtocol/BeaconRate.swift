/// How often a shared position goes to its room.
///
/// Kept in the radio's position settings, where Meshtastic has always kept it,
/// but the phone does the sending: it seals each fix under the room's key, which
/// the radio's own broadcast could not. So it pauses while the phone is away
/// from its radio.
public enum BeaconRate: CaseIterable, Sendable {
    case brisk
    case steady
    case sparing
    case hourly

    public var label: String {
        switch self {
        case .brisk: "5 min"
        case .steady: "15 min"
        case .sparing: "30 min"
        case .hourly: "1 hour"
        }
    }

    public var seconds: Int {
        switch self {
        case .brisk: 300
        case .steady: 900
        case .sparing: 1_800
        case .hourly: 3_600
        }
    }

    public var name: String {
        switch self {
        case .brisk: "BRISK"
        case .steady: "STEADY"
        case .sparing: "SPARING"
        case .hourly: "HOURLY"
        }
    }

    /// What the firmware uses when the field is left at zero.
    public static let firmwareDefaultSeconds = 900

    /// Reads the radio's setting back.
    ///
    /// Zero is not "never": it means the firmware's own default, so it is
    /// reported as the rate that default actually produces. An interval Firepit
    /// does not offer returns nil rather than being rounded to a neighbour.
    public static func of(seconds: Int?) -> BeaconRate? {
        let wanted = seconds == 0 ? firmwareDefaultSeconds : seconds
        return allCases.first { entry in
            entry.seconds == wanted
        }
    }
}
