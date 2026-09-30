import FirepitProtos
import Foundation

/// Meshtastic's own channel-sharing URL, so Firepit can join a channel somebody made in the official app.
///
/// Reading only. Firepit issues its own invites, which carry a rotating token and a room key the radio never holds;
/// this format can carry neither, so anything shared through it would be weaker than the person sharing it expects.
///
/// Mirrors python `Node.setURL`: a `ChannelSet` protobuf, url-safe base64 with the padding stripped, after `/#`.
/// Parsing splits on `/#` rather than matching a host, which is what the reference does and what makes the older
/// `/d/#` and `/c/#` links still work.
public enum ChannelUrl {
    private static let prefix = "https://meshtastic.org/e/#"

    /// The marker the apps use for "add these, do not replace what I have".
    private static let addSeparator = "/?add=true#"
    private static let separator = "/#"

    public struct Shared: Sendable, Equatable {
        /// Primary first, then secondaries, exactly as the format orders them.
        public var channels: [ChannelSettings]
        public var lora: Config.LoRaConfig?
        /// True when the link asked to be added alongside, rather than replace.
        public var addOnly: Bool

        public init(channels: [ChannelSettings], lora: Config.LoRaConfig?, addOnly: Bool) {
            self.channels = channels
            self.lora = lora
            self.addOnly = addOnly
        }

        public var primary: ChannelSettings? { channels.first }
    }

    /// Not offered to the app: Firepit shares rooms through its own invites. Kept so the decoder can be tested against
    /// links this codec produced, rather than only against hand-copied fixtures.
    static func encode(_ channels: [ChannelSettings], lora: Config.LoRaConfig?) -> String {
        var set = ChannelSet()
        set.settings = channels
        if let lora { set.loraConfig = lora }
        let bytes: Data = (try? set.serializedBytes()) ?? Data()
        return prefix + Base64URL.encode(bytes)
    }

    /// Nil for anything that is not a channel link we can read.
    public static func decode(_ url: String) -> Shared? {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        let addOnly = trimmed.contains(addSeparator)
        let afterSeparator: Substring
        if addOnly {
            afterSeparator = trimmed.substring(afterLast: addSeparator)
        } else if trimmed.contains(separator) {
            afterSeparator = trimmed.substring(afterLast: separator)
        } else {
            return nil
        }
        let payload = afterSeparator.substring(before: "?").trimmingCharacters(in: .whitespacesAndNewlines)
        if payload.isEmpty { return nil }

        // The reference strips padding on the way out; Base64URL accepts it either way.
        guard let bytes = Base64URL.decode(payload),
            let set = try? ChannelSet(serializedBytes: bytes)
        else { return nil }

        let usable = set.settings.filter { isUsableKey($0.psk) }
        if usable.isEmpty { return nil }
        return Shared(channels: usable, lora: set.hasLoraConfig ? set.loraConfig : nil, addOnly: addOnly)
    }

    /// A key length the firmware will actually accept. Anything else would be written to the radio and then silently
    /// not work, or — for an empty key on a secondary — inherit the primary's, which is not what the link said.
    private static func isUsableKey(_ psk: Data) -> Bool {
        psk.count == 1 || psk.count == 16 || psk.count == 32
    }
}

extension StringProtocol {
    /// Kotlin's `substringAfterLast(delimiter)` with the whole string as the missing-delimiter value.
    func substring(afterLast delimiter: String) -> Substring {
        guard let range = range(of: delimiter, options: .backwards) else { return Substring(self) }
        return Substring(self[range.upperBound...])
    }

    /// Kotlin's `substringBefore(delimiter)` with the whole string as the missing-delimiter value.
    func substring(before delimiter: Character) -> Substring {
        guard let index = firstIndex(of: delimiter) else { return Substring(self) }
        return Substring(self[..<index])
    }
}
