import FirepitProtos

/// Where an arriving message announces itself.
///
/// The radio's alert is the firmware's own, so it still happens with Firepit
/// closed, and it is the only one that reaches you when the phone is in a bag or
/// out of Bluetooth range.
public enum MessageAlerts: CaseIterable, Sendable {
    case phoneOnly
    case phoneAndNode

    public var label: String {
        switch self {
        case .phoneOnly: "Phone only"
        case .phoneAndNode: "Phone and radio"
        }
    }

    public var summary: String {
        switch self {
        case .phoneOnly:
            "Only this phone announces a message. The radio stays silent."
        case .phoneAndNode:
            """
            The radio beeps, vibrates or blinks as well — whichever its board has. Useful when the phone is away from \
            you.
            """
        }
    }

    public var name: String {
        switch self {
        case .phoneOnly: "PHONE_ONLY"
        case .phoneAndNode: "PHONE_AND_NODE"
        }
    }

    /// Read from the radio rather than from what we last asked it to do.
    public static func of(config: ModuleConfig.ExternalNotificationConfig?) -> MessageAlerts {
        if config?.enabled == true && config?.alertMessage == true {
            return .phoneAndNode
        }
        return .phoneOnly
    }

    /// The section to write back.
    ///
    /// Built from what the radio reported, because the firmware replaces the
    /// whole section: anything left out would be reset to its default.
    public static func applyTo(
        config: ModuleConfig.ExternalNotificationConfig?,
        choice: MessageAlerts
    ) -> ModuleConfig.ExternalNotificationConfig {
        let on = choice == .phoneAndNode
        var written = config ?? ModuleConfig.ExternalNotificationConfig()
        written.enabled = on
        written.alertMessage = on
        written.alertMessageBuzzer = on
        written.alertMessageVibra = on
        written.alertBell = on
        written.alertBellBuzzer = on
        written.alertBellVibra = on
        return written
    }
}
