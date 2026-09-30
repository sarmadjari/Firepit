import FirepitProtos
import Foundation
import Testing

@testable import FirepitProtocol

@Suite struct ChannelUrlTests {
    private var primary: ChannelSettings {
        var settings = ChannelSettings()
        settings.name = "LongFast"
        settings.psk = bytes(1)
        return settings
    }

    private var secondary: ChannelSettings {
        var settings = ChannelSettings()
        settings.name = "camp"
        settings.psk = repeatedBytes(count: 32, value: 9)
        return settings
    }

    private var lora: Config.LoRaConfig {
        var lora = Config.LoRaConfig()
        lora.usePreset = true
        lora.modemPreset = .longFast
        lora.region = .eu868
        return lora
    }

    @Test func aLinkWeProduceIsOneWeCanReadBack() throws {
        let url = ChannelUrl.encode([primary, secondary], lora: lora)

        let shared = try #require(ChannelUrl.decode(url))

        #expect(shared.channels == [primary, secondary])
        #expect(shared.lora == lora)
        #expect(!shared.addOnly)
    }

    @Test func theLinkIsTheFormTheOfficialClientsProduce() {
        let url = ChannelUrl.encode([primary], lora: lora)

        #expect(url.starts(with: "https://meshtastic.org/e/#"), "\(url)")
        // Url-safe alphabet with the padding stripped, per python getURL.
        let payload = String(url.split(separator: "/#", maxSplits: 1, omittingEmptySubsequences: false).last ?? "")
        #expect(!payload.contains("+"), "\(payload)")
        #expect(!payload.contains("/"), "\(payload)")
        #expect(!payload.contains("="), "\(payload)")
    }

    /// Primary first is the format, not a convention: python `setURL` assigns roles by position.
    @Test func thePrimaryKeepsItsPlaceAtTheFront() {
        let shared = ChannelUrl.decode(ChannelUrl.encode([primary, secondary], lora: lora))

        #expect(shared?.primary == primary)
    }

    @Test func paddingStrippedByAnotherClientIsRestored() {
        // Every payload length mod 4 has to survive, since the reference implementation removes '=' before sharing.
        for count in 1...6 {
            let channels = (0..<count).map { index -> ChannelSettings in
                var settings = ChannelSettings()
                settings.name = "ch\(index)"
                settings.psk = repeatedBytes(count: 32, value: UInt8(index))
                return settings
            }
            let url = ChannelUrl.encode(channels, lora: lora)

            #expect(ChannelUrl.decode(url)?.channels == channels, "count \(count)")
        }
    }

    @Test func anAddOnlyLinkIsRecognisedAsAddingRatherThanReplacing() throws {
        let payload = payloadOf(ChannelUrl.encode([secondary], lora: lora))

        let shared = try #require(ChannelUrl.decode("https://meshtastic.org/e/?add=true#\(payload)"))

        #expect(shared.addOnly)
        #expect(shared.channels == [secondary])
    }

    /// The reference splits on `/#` rather than matching a host, so older links still work.
    @Test func olderLinkFormsStillParse() {
        let payload = payloadOf(ChannelUrl.encode([primary], lora: lora))

        for url in [
            "https://meshtastic.org/d/#\(payload)",
            "https://www.meshtastic.org/c/#\(payload)",
            "https://meshtastic.org/e/#\(payload)",
        ] {
            #expect(ChannelUrl.decode(url)?.channels == [primary], "\(url)")
        }
    }

    @Test func whitespaceAroundAPastedLinkIsTolerated() {
        let url = ChannelUrl.encode([primary], lora: lora)

        #expect(ChannelUrl.decode("  \(url)\n") != nil)
    }

    @Test func anythingThatIsNotAChannelLinkIsRefused() {
        for text in [
            "",
            "not a url",
            "https://meshtastic.org/e/#",
            "https://meshtastic.org/e/#!!!not base64!!!",
            "firepit://join?v=1&d=abc",
            "https://example.com/",
        ] {
            #expect(ChannelUrl.decode(text) == nil, "\(text)")
        }
    }

    /// A key the firmware would reject is worse than no channel: it would be written, look configured, and silently
    /// fail
    /// to decrypt anything. An empty one is worse still, because a secondary with no key inherits the primary's.
    @Test func channelsWithAnUnusableKeyAreDropped() {
        var bad = ChannelSettings()
        bad.name = "broken"
        bad.psk = repeatedBytes(count: 7, value: 0)
        let url = ChannelUrl.encode([bad], lora: lora)

        #expect(ChannelUrl.decode(url) == nil)
    }

    @Test func aUsableChannelSurvivesAlongsideAnUnusableOne() {
        var bad = ChannelSettings()
        bad.name = "broken"
        bad.psk = repeatedBytes(count: 7, value: 0)
        let url = ChannelUrl.encode([secondary, bad], lora: lora)

        #expect(ChannelUrl.decode(url)?.channels == [secondary])
    }

    @Test func aLinkWithNoLoraConfigStillParses() throws {
        let shared = try #require(ChannelUrl.decode(ChannelUrl.encode([secondary], lora: nil)))

        #expect(shared.lora == nil)
        #expect(shared.channels == [secondary])
    }

    /// A Firepit invite must never be mistaken for a Meshtastic channel link.
    @Test func aFirepitInviteIsNotAChannelUrl() {
        #expect(ChannelUrl.decode("firepit://join?v=1&d=CghNZXNoQ2hhdA") == nil)
    }

    private func payloadOf(_ url: String) -> String {
        String(url.split(separator: "/#", maxSplits: 1, omittingEmptySubsequences: false).last ?? "")
    }
}
