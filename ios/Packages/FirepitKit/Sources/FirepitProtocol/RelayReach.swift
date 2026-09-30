import FirepitProtos

/// Who a radio will relay for.
///
/// A relay does not need to read a packet to pass it on, so by default a radio
/// carries traffic for every mesh sharing its frequency.
public enum RelayReach: CaseIterable, Sendable {
    /// Carries anything it hears, including other people's meshes.
    case everyone

    /// Only passes on what it can decrypt: traffic on your own channels.
    case group

    public var label: String {
        switch self {
        case .everyone: "Everybody"
        case .group: "My group only"
        }
    }

    public var mode: Config.DeviceConfig.RebroadcastMode {
        switch self {
        case .everyone: .all
        case .group: .localOnly
        }
    }

    public var name: String {
        switch self {
        case .everyone: "EVERYONE"
        case .group: "GROUP"
        }
    }

    /// Anything other than the two modes Firepit offers is left alone rather
    /// than reported as one of them.
    public static func of(mode: Config.DeviceConfig.RebroadcastMode?) -> RelayReach? {
        allCases.first { entry in
            entry.mode == mode
        }
    }
}
