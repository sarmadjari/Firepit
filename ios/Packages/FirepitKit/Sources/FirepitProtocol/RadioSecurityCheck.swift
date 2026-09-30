import FirepitProtos
import Foundation

/// Something in a radio's own settings that lets people read it or run it.
///
/// The radio holds every room's channel key and its own private key, and hands
/// both to whatever connects to it. Sealing keeps the words and positions out of
/// reach either way, but the radio is still the easiest thing in the group to
/// take over, so these are checked on every connection rather than trusted.
public enum RadioRisk: CaseIterable, Equatable, Sendable {
    case bluetoothOpen
    case bluetoothDefaultPin
    case remoteAdminKey
    case managed
    case legacyAdminChannel
    case debugLog
    case mqttUplink
    case mqttMapReport

    public var title: String {
        switch self {
        case .bluetoothOpen:
            return "Bluetooth has no PIN"
        case .bluetoothDefaultPin:
            return "Bluetooth uses the default PIN"
        case .remoteAdminKey:
            return "Remote admin is on"
        case .managed:
            return "Managed by someone else"
        case .legacyAdminChannel:
            return "Admin channel is on"
        case .debugLog:
            return "Debug logs go to connected apps"
        case .mqttUplink:
            return "Copies traffic to the internet"
        case .mqttMapReport:
            return "Reports to a public map"
        }
    }

    public var detail: String {
        switch self {
        case .bluetoothOpen:
            return
                """
                Anyone nearby can connect to this radio whenever your phone isn't, read its room and private keys, and \
                change its settings. Setting a PIN closes that.
                """
        case .bluetoothDefaultPin:
            return
                """
                This radio pairs with 123456, the PIN every radio without a screen ships with. Anyone nearby can pair \
                whenever your phone isn't connected, and read or change everything on it. Setting a new PIN closes \
                that.
                """
        case .remoteAdminKey:
            return
                """
                An admin key is set, so whoever holds its partner can read this radio's keys and change its settings \
                over the mesh — even after a room moves to new keys. Remove it unless you set it yourself.
                """
        case .managed:
            return
                """
                This radio is in managed mode, so it ignores settings from this phone. Firepit can't keep it from \
                broadcasting or change its keys. Whoever manages it can.
                """
        case .legacyAdminChannel:
            return
                """
                The old admin channel lets anyone holding that channel's key run this radio. Turning it off closes \
                that.
                """
        case .debugLog:
            return
                """
                Debug logging hands the radio's own log to whatever app connects, including what it hears. Turning it \
                off closes that.
                """
        case .mqttUplink:
            return
                """
                MQTT is on with an uplink, so this radio copies packets to an internet server — including the private \
                messages that carry room keys. Turning MQTT off stops it.
                """
        case .mqttMapReport:
            return
                """
                This radio publishes its name and position to an internet map whenever it has a connection. Turning \
                MQTT off stops it.
                """
        }
    }

    /// False when the radio itself refuses to be changed from this phone.
    public var canFix: Bool {
        self != .managed
    }
}

public enum RadioSecurityCheck {
    /// What every radio without a screen pairs with out of the box.
    public static let defaultPin: UInt32 = 123_456

    /// What is wrong with this radio, most serious first. Empty when nothing is,
    /// and also when the radio has not reported: absence is not a finding.
    public static func risksOf(snapshot: RadioSnapshot) -> [RadioRisk] {
        var risks: [RadioRisk] = []
        if let bluetooth = snapshot.bluetooth, bluetooth.enabled {
            switch bluetooth.mode {
            case .noPin:
                risks.append(.bluetoothOpen)
            case .fixedPin where bluetooth.fixedPin == defaultPin:
                risks.append(.bluetoothDefaultPin)
            default:
                break
            }
        }
        if let security = snapshot.security {
            if security.adminKey.contains(where: { !$0.isEmpty }) {
                risks.append(.remoteAdminKey)
            }
            if security.isManaged {
                risks.append(.managed)
            }
            if security.adminChannelEnabled {
                risks.append(.legacyAdminChannel)
            }
            if security.debugLogApiEnabled {
                risks.append(.debugLog)
            }
        }
        if let mqtt = snapshot.mqtt, mqtt.enabled {
            // One uplinked channel is enough: the firmware then uplinks every
            // PKI packet it carries as well, whichever channel it came in on.
            let uplinking = snapshot.channels.values.contains { channel in
                channel.hasSettings && channel.settings.uplinkEnabled
            }
            if uplinking {
                risks.append(.mqttUplink)
            }
            if mqtt.mapReportingEnabled {
                risks.append(.mqttMapReport)
            }
        }
        return risks
    }

    /// A six-digit PIN that is not the default. Kept away from the published one
    /// so a fix can never land back on it.
    public static func newPin(random: @escaping @Sendable (Range<Int>) -> Int = { range in Int.random(in: range) })
        -> UInt32
    {
        var pin = Int(defaultPin)
        while pin == defaultPin {
            pin = random(100_000..<1_000_000)
        }
        return UInt32(pin)
    }

    /// The radio's own Bluetooth section with a PIN required, everything else kept.
    public static func withPin(current: Config.BluetoothConfig, pin: UInt32) -> Config.BluetoothConfig {
        var copy = current
        copy.enabled = true
        copy.mode = .fixedPin
        copy.fixedPin = pin
        return copy
    }

    /// The radio's own security section with every way in from elsewhere closed.
    /// Its own keys are kept, or it would stop being the node everyone knows.
    public static func withoutRemoteAccess(current: Config.SecurityConfig) -> Config.SecurityConfig {
        var copy = current
        copy.adminKey = []
        copy.adminChannelEnabled = false
        copy.debugLogApiEnabled = false
        return copy
    }

    /// The radio's own MQTT section, switched off, its server details kept for whoever set them.
    public static func withoutMqtt(current: ModuleConfig.MQTTConfig) -> ModuleConfig.MQTTConfig {
        var copy = current
        copy.enabled = false
        copy.mapReportingEnabled = false
        return copy
    }
}
