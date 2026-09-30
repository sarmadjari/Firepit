import FirepitProtos
import Foundation

/// What Firepit needs to speak plain Meshtastic.
///
/// A Firepit room is sealed inside `PRIVATE_APP` and is unreadable to any other client. That is the point of it, and
/// it is also why it cannot be used to talk to somebody running the official app. An interoperable channel is the
/// opposite trade: ordinary `TEXT_MESSAGE_APP` on an ordinary channel, readable by whoever holds the key — which on
/// the default key is everybody.
///
/// Both are legitimate; only one of them is private, and the app has to say which is which rather than let someone
/// assume.
public enum MeshtasticChannel {
    /// The published default key, from `channel.proto`. Quoted there in full, which is the whole problem with it: a
    /// channel using this is readable by any Meshtastic device out of the box.
    public static let defaultKey = Data([
        0xd4, 0xf1, 0xbb, 0x3a,
        0x20, 0x29, 0x07, 0x59,
        0xf0, 0xbc, 0xff, 0xab,
        0xcf, 0x4e, 0x69, 0x01,
    ])

    /// `psk = [1]` is the shorthand for [defaultKey]; 2..10 are it plus 1..9.
    public static let defaultKeyShorthand: UInt8 = 1
    public static let maxShorthand = 10

    /// Resolves the one-byte shorthand the firmware allows in `psk`.
    ///
    /// Without this a channel carrying `[1]` looks like a one-byte key rather than the well-known default, and the app
    /// would call a public channel private.
    public static func expandPsk(_ psk: Data?) -> Data {
        guard let psk else { return Data() }
        if psk.count != 1 { return psk }
        if psk[0] == 0 { return Data() }
        if Int(psk[0]) >= 1 && Int(psk[0]) <= maxShorthand {
            let offset = Int(psk[0]) - 1
            var bytes = defaultKey
            let last = bytes.index(before: bytes.endIndex)
            bytes[last] = UInt8(truncatingIfNeeded: Int(bytes[last]) + offset)
            return bytes
        }

        return psk
    }

    /// The name a channel must carry to sit on the public mesh.
    ///
    /// The firmware derives the frequency slot from the primary channel's name, and treats an empty name as the modem
    /// preset's own label. A channel that means to reach public nodes has to use that same label, because the channel
    /// hash every receiver checks is taken over the name and the key together.
    ///
    /// The protos have retired LONG_SLOW and VERY_LONG_SLOW, but a radio can still be set to either, and its channel is
    /// still named after it.
    public static func publicNameFor(preset: Config.LoRaConfig.ModemPreset?) -> String {
        switch preset {
        case .longSlow:
            return "LongSlow"
        case .longModerate:
            return "LongMod"
        case .longTurbo:
            return "LongTurbo"
        case .mediumSlow:
            return "MediumSlow"
        case .mediumFast:
            return "MediumFast"
        case .shortSlow:
            return "ShortSlow"
        case .shortFast:
            return "ShortFast"
        case .shortTurbo:
            return "ShortTurbo"
        case .veryLongSlow:
            return "VLongSlow"
        default:
            return "LongFast"
        }
    }

    /// A channel that reaches the open mesh: the preset's own name and the key everybody already has.
    public static func publicChannel(
        index: Int,
        preset: Config.LoRaConfig.ModemPreset?,
        role: Channel.Role = .secondary
    ) -> Channel {
        var moduleSettings = ModuleSettings()
        moduleSettings.positionPrecision = UInt32(PositionPrecision.disabled)

        var settings = ChannelSettings()
        settings.name = publicNameFor(preset: preset)
        settings.psk = Data([defaultKeyShorthand])
        settings.uplinkEnabled = false
        settings.downlinkEnabled = false
        settings.moduleSettings = moduleSettings

        var channel = Channel()
        channel.index = Int32(index)
        channel.role = role
        channel.settings = settings
        return channel
    }

    /// A channel shared with particular people: a name and a real key.
    ///
    /// Private from the mesh at large, but not from whoever holds the key, and the radio holds it — so this is weaker
    /// than a Firepit room and is only offered for talking to people who are not running Firepit.
    public static func privateChannel(
        index: Int,
        name: String,
        psk: Data,
        role: Channel.Role = .secondary
    ) throws -> Channel {
        guard psk.count == 16 || psk.count == 32 else {
            throw MeshtasticChannelError.invalidKeyLength("a Meshtastic key is 16 or 32 bytes, was \(psk.count)")
        }

        var moduleSettings = ModuleSettings()
        moduleSettings.positionPrecision = UInt32(PositionPrecision.disabled)

        var settings = ChannelSettings()
        settings.name = name
        settings.psk = psk
        settings.uplinkEnabled = false
        settings.downlinkEnabled = false
        settings.moduleSettings = moduleSettings

        var channel = Channel()
        channel.index = Int32(index)
        channel.role = role
        channel.settings = settings
        return channel
    }

    /// True when this channel's traffic is readable by any Meshtastic radio.
    public static func isPublic(_ settings: ChannelSettings?) -> Bool {
        !ChannelKey.of(settings?.psk).isPrivate
    }

    /// True for the published default key and its nine neighbours, whether they arrived as the one-byte shorthand or
    /// written out in full.
    ///
    /// Length alone cannot answer this: the default key is sixteen bytes, the same as a real AES-128 key, so a check
    /// that only measured it would call the most public key in the protocol private.
    public static func isWellKnown(_ psk: Data?) -> Bool {
        let expanded = expandPsk(psk)
        if expanded.count != defaultKey.count { return false }
        for shorthand in 1...maxShorthand {
            if expanded == expandPsk(Data([UInt8(shorthand)])) { return true }
        }
        return false
    }
}

public enum MeshtasticChannelError: Error, CustomStringConvertible, Equatable, Sendable {
    case invalidKeyLength(String)

    public var description: String {
        switch self {
        case .invalidKeyLength(let message):
            return message
        }
    }
}
