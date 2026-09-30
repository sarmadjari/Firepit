/// How busy the radio channel is.
///
/// On a shared LoRa channel there is no collision detection: a congested channel
/// does not refuse messages, it swallows them. Surfacing this is the difference
/// between "nobody replied" and "nothing left the antenna".
public enum ChannelLoad: CaseIterable, Sendable {
    /// Room to talk.
    case clear

    /// Usable, but messages may take a while.
    case busy

    /// Firmware throttles its own telemetry here; expect losses.
    case congested

    public var name: String {
        switch self {
        case .clear: "CLEAR"
        case .busy: "BUSY"
        case .congested: "CONGESTED"
        }
    }

    /// Matches the firmware's own threshold for suppressing telemetry.
    public static let busyPercent: Float = 25
    public static let congestedPercent: Float = 50

    public static func of(utilizationPercent: Float?) -> ChannelLoad? {
        if utilizationPercent == nil {
            return nil
        }
        if utilizationPercent! >= congestedPercent {
            return .congested
        }
        if utilizationPercent! >= busyPercent {
            return .busy
        }
        return .clear
    }
}
