import FirepitProtos
import Foundation

/// How far a node's traffic is allowed to travel, and through whom.
///
/// The primary channel's *name* is hashed into the LoRa frequency slot, so this is the one setting that decides which
/// radios can even hear us. Content is sealed either way; what changes is who relays it and how much airtime we share.
public enum RangeMode: String, CaseIterable, Sendable {
    /// Our own frequency slot. Only Firepit nodes hear us, and only Firepit nodes carry our packets onward.
    case groupOnly = "GROUP_ONLY"

    /// The public mesh's frequency slot. Stranger nodes rebroadcast our packets without being able to read them, which
    /// buys range at the cost of being visible as traffic.
    case publicRelay = "PUBLIC_RELAY"

    public var label: String {
        switch self {
        case .groupOnly:
            return "Our nodes only"
        case .publicRelay:
            return "Nearby radios too"
        }
    }

    public var summary: String {
        switch self {
        case .groupOnly:
            return "Quietest and hardest to notice. Messages travel only through Firepit "
                + "radios, so your range is your own group's range."
        case .publicRelay:
            return "Further reach. Other Meshtastic radios nearby pass your messages along "
                + "without being able to read them. They can tell that traffic exists, and your "
                + "radio carries theirs in return."
        }
    }

    public var wire: Meshchat_MeshMode {
        switch self {
        case .groupOnly:
            return .groupOnly
        case .publicRelay:
            return .publicRelay
        }
    }

    /// Android's enum constant name, for storage and test labels.
    public var name: String { rawValue }

    /// Quietest wins by default: range is a choice, exposure should not be.
    public static let `default` = RangeMode.groupOnly

    public static func named(_ name: String?) -> RangeMode {
        guard let name else { return .default }
        return allCases.first { $0.name == name } ?? .default
    }

    public static func of(_ mode: Meshchat_MeshMode?) -> RangeMode {
        switch mode {
        case .publicRelay:
            return .publicRelay
        case .groupOnly:
            return .groupOnly
        case nil:
            return .default
        default:
            return .default
        }
    }
}

/// Whether this radio's own identity has been made private to Firepit.
///
/// Three states rather than two, because "nobody has been asked yet" is not the same as "somebody said no". Only the
/// first is worth interrupting for.
public enum RadioPrivacy: String, CaseIterable, Sendable {
    /// Nobody has been asked. The radio stays exactly as it was found.
    case undecided = "UNDECIDED"

    /// Slot 0 carries Firepit's key: ordinary Meshtastic radios no longer see this node. The key ships in the app, so
    /// every Firepit install can.
    case firepit = "FIREPIT"

    /// Left as the factory or its owner set it.
    case open = "OPEN"

    public var label: String {
        switch self {
        case .undecided:
            return "Not set"
        case .firepit:
            return "Private to Firepit"
        case .open:
            return "Public, as it came"
        }
    }

    public var summary: String {
        switch self {
        case .undecided:
            return "This radio is still set up the way you found it."
        case .firepit:
            return "Ordinary Meshtastic radios no longer see this one. Its name and battery level "
                + "are still sent in the open to anyone running Firepit, because that key comes with "
                + "the app. If you also use this radio on another Meshtastic mesh, it will leave that mesh."
        case .open:
            return "This radio keeps working as a normal Meshtastic node, on whatever mesh you "
                + "already use it for. Its name and battery level are visible to any Meshtastic "
                + "device nearby."
        }
    }

    /// Android's enum constant name, for storage and test labels.
    public var name: String { rawValue }

    /// Nothing is taken over until somebody says so.
    public static let `default` = RadioPrivacy.undecided

    public static func named(_ name: String?) -> RadioPrivacy {
        guard let name else { return .default }
        return allCases.first { $0.name == name } ?? .default
    }
}

/// Slot 0, which every Firepit node must be holding before it says anything.
///
/// The firmware ships this slot with the published default key (`AQ==`, "listed in this source code"), and it is the
/// slot that carries NodeInfo and telemetry and that periodic broadcasts default to. Left alone, a Firepit node
/// announces its owner's name and battery to every Meshtastic radio in relay range, and any packet that falls back to
/// channel encryption is readable by all of them.
///
/// Writing it does three things: names and telemetry become unreadable to strangers, the NodeDB stops filling with
/// nodes
/// we will never talk to (the firmware admits only packets it could decrypt), and the name we choose picks the
/// frequency
/// slot, which is what [RangeMode] is.
///
/// The key here is **not a secret**. It ships in every copy of the app, so anyone can extract it; it is what makes
/// Firepit nodes recognise each other, not what keeps words private. Rooms and direct messages carry their own
/// encryption on top and never rely on this.
public enum PrimaryChannel {
    public static let slot = ChannelSlotManager.primarySlot

    /// "MESH". Marks the slot as ours so a re-provision can tell it from a user's own channel.
    public static let id: UInt32 = 0x4D455348

    /// Hashed into a frequency slot of our own, away from the public mesh.
    public static let groupName = "MeshChat"

    /// Empty, so the firmware hashes the modem preset's display name instead and lands on the same slot as the public
    /// mesh.
    public static let publicName = ""

    /// The app-wide primary key, base64, byte-for-byte identical on every platform. Asserted against
    /// `protos/meshchat-primary-key.txt` in PrimaryChannelTest: the two apps cannot see each other if it drifts.
    public static let keyBase64 = "3RrNdyWIx+i2Z2QBs2sRVxXC1uGZs7oqCN3lIyVMl3k="

    public static let key: Data = Data(base64Encoded: keyBase64)!

    /// True for the key every copy of Firepit carries, which keeps nothing from anyone who has the app.
    public static func isAppWideKey(_ psk: Data?) -> Bool {
        psk == key
    }

    /// How a channel's key reads to a person: the app-wide key is named for what it is.
    public static func keyLabel(_ psk: Data?) -> String {
        if isAppWideKey(psk) { return appWideKeyLabel }
        return ChannelKey.of(psk).label
    }

    public static let appWideKeyLabel = "Firepit's shared key: anyone with the app can read it"

    public static func nameFor(mode: RangeMode) -> String {
        switch mode {
        case .groupOnly:
            return groupName
        case .publicRelay:
            return publicName
        }
    }

    /// What slot 0 must look like for [mode].
    public static func channelFor(mode: RangeMode) -> Channel {
        var moduleSettings = ModuleSettings()
        moduleSettings.positionPrecision = UInt32(PositionPrecision.disabled)

        var settings = ChannelSettings()
        settings.name = nameFor(mode: mode)
        settings.psk = key
        settings.id = id
        // The primary is the audience nobody chose, and the firmware's own periodic broadcasts default to it.
        settings.uplinkEnabled = false
        settings.downlinkEnabled = false
        settings.moduleSettings = moduleSettings

        var channel = Channel()
        channel.index = Int32(slot)
        channel.role = .primary
        channel.settings = settings
        return channel
    }

    /// The mode a radio is currently in, or nil when slot 0 is not ours at all.
    public static func modeOf(_ channel: Channel?) -> RangeMode? {
        guard let channel else { return nil }
        let settings = channel.hasSettings ? channel.settings : nil
        guard let settings else { return nil }
        if channel.role != .primary || settings.psk != key { return nil }
        switch settings.name {
        case groupName:
            return .groupOnly
        case publicName:
            return .publicRelay
        default:
            return nil
        }
    }

    /// True when the radio already agrees, so a reconnect writes nothing in the normal case. Compares only what we set:
    /// the firmware fills other fields.
    public static func matches(_ channel: Channel?, mode: RangeMode) -> Bool {
        guard let channel else { return false }
        let settings = channel.hasSettings ? channel.settings : nil
        guard let settings else { return false }
        return channel.role == .primary
            && settings.name == nameFor(mode: mode)
            && settings.psk == key
            && settings.id == id
            && !settings.uplinkEnabled
            && !settings.downlinkEnabled
            && Int(settings.hasModuleSettings ? settings.moduleSettings.positionPrecision : 0)
                == PositionPrecision.disabled
    }

    /// True when this slot still carries a key every Meshtastic radio holds.
    ///
    /// Worth saying out loud rather than silently fixing: until it is rewritten, the node's name and battery are
    /// public.
    public static func isPublic(_ channel: Channel?) -> Bool {
        let settings = channel?.hasSettings == true ? channel?.settings : nil
        return MeshtasticChannel.isPublic(settings)
    }
}
