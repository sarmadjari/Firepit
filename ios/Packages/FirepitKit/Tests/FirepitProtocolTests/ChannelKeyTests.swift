import Foundation
import Testing

@testable import FirepitProtocol

@Suite struct ChannelKeyTests {
    @Test func noKeyAtAllIsNotEncrypted() {
        #expect(ChannelKey.of(nil) == .none)
        #expect(ChannelKey.of(Data()) == .none)
        #expect(ChannelKey.of(bytes(0)) == .none)
    }

    @Test func theOneByteShorthandIsTheKeyEveryRadioShipsWith() {
        #expect(ChannelKey.of(bytes(1)) == .default)
        #expect(ChannelKey.of(bytes(10)) == .default)
    }

    @Test func aFullLengthKeyIsPrivate() {
        #expect(ChannelKey.of(repeatedBytes(count: 16, value: 7)) == .private)
        #expect(ChannelKey.of(repeatedBytes(count: 32, value: 7)) == .private)
    }

    @Test func anUnrecognisedKeyIsCalledPublicRatherThanGuessedPrivate() {
        #expect(ChannelKey.of(repeatedBytes(count: 8, value: 7)) == .default)
    }

    /// The default key is sixteen bytes, exactly like a real AES-128 key, so a check that only measured length would
    /// call the most public key in the protocol private.
    @Test func theWellKnownKeyWrittenOutInFullIsStillTheDefault() {
        #expect(ChannelKey.of(MeshtasticChannel.defaultKey) == .default)
        #expect(!ChannelKey.of(MeshtasticChannel.defaultKey).isPrivate)
    }

    @Test func onlyAFullKeyCountsAsPrivate() {
        #expect(ChannelKey.of(repeatedBytes(count: 32, value: 7)).isPrivate)
        #expect(!ChannelKey.of(bytes(1)).isPrivate)
        #expect(!ChannelKey.of(nil).isPrivate)
    }
}
