import Foundation

/// How private a channel's traffic actually is.
///
/// Meshtastic's one-byte shorthand keys are the default key and its nine neighbours, and the protocol says plainly
/// that they are "listed in this source code". A channel using one is readable by any Meshtastic device out of the box,
/// which is worth saying out loud before someone trusts it.
public enum ChannelKey: String, CaseIterable, Sendable {
    case none = "NONE"
    case `default` = "DEFAULT"
    case `private` = "PRIVATE"

    public var label: String {
        switch self {
        case .none:
            return "Not encrypted"
        case .default:
            return "Default key, which every radio has"
        case .private:
            return "Private key"
        }
    }

    public var isPrivate: Bool { self == .private }

    /// Android's enum constant name, for storage and test labels.
    public var name: String { rawValue }

    /// Only a full key nobody else has counts as private.
    ///
    /// Length alone is not enough to decide: the published default key is sixteen bytes, the same as a real AES-128
    /// key,
    /// so it is compared by value. Anything unrecognised is reported as public, because calling an unknown key private
    /// would be the dangerous way round to be wrong.
    public static func of(_ psk: Data?) -> ChannelKey {
        let expanded = MeshtasticChannel.expandPsk(psk)
        if expanded.count == 0 { return .none }
        if MeshtasticChannel.isWellKnown(expanded) { return .default }
        if expanded.count == 16 || expanded.count == 32 { return .private }
        return .default
    }
}
