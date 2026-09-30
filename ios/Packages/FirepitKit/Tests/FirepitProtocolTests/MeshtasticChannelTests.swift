import FirepitProtos
import Foundation
import Testing

@testable import FirepitProtocol

@Suite struct MeshtasticChannelTests {
    private let preset = Config.LoRaConfig.ModemPreset.longFast

    @Test func theDefaultKeyMatchesTheOneQuotedInChannelProto() {
        #expect(hex(MeshtasticChannel.defaultKey) == "d4f1bb3a20290759f0bcffabcf4e6901")
        #expect(MeshtasticChannel.defaultKey.count == 16)
    }

    @Test func theOneByteShorthandExpandsToTheWellKnownKey() {
        #expect(MeshtasticChannel.expandPsk(bytes(1)) == MeshtasticChannel.defaultKey)
    }

    @Test func shorthandsTwoToTenStepTheLastByte() {
        for shorthand in 2...10 {
            let expanded = MeshtasticChannel.expandPsk(bytes(UInt8(shorthand)))
            var expected = MeshtasticChannel.defaultKey
            let last = expected.index(before: expected.endIndex)
            expected[last] = UInt8(truncatingIfNeeded: Int(expected[last]) + (shorthand - 1))

            #expect(expanded == expected, "shorthand \(shorthand)")
        }
    }

    @Test func shorthandZeroMeansNoEncryptionAtAll() {
        #expect(MeshtasticChannel.expandPsk(bytes(0)) == Data())
    }

    @Test func aRealKeyIsLeftAlone() {
        let real = incrementingBytes(count: 32)
        #expect(MeshtasticChannel.expandPsk(real) == real)
    }

    /// The reason the shorthand has to be expanded at all: without it, a channel carrying a single byte looks like an
    /// unrecognised key, and the app would describe a channel the whole world can read as private.
    @Test func aShorthandChannelIsReportedPublicNotPrivate() {
        var stock = ChannelSettings()
        stock.name = "LongFast"
        stock.psk = bytes(1)

        #expect(MeshtasticChannel.isPublic(stock))
        #expect(ChannelKey.of(stock.psk) == .default)
    }

    @Test func a32ByteKeyIsPrivate() {
        var shared = ChannelSettings()
        shared.name = "camp"
        shared.psk = repeatedBytes(count: 32, value: 7)

        #expect(!MeshtasticChannel.isPublic(shared))
    }

    @Test func anAbsentOrEmptyKeyIsNeverCalledPrivate() {
        #expect(MeshtasticChannel.isPublic(nil))
        #expect(MeshtasticChannel.isPublic(ChannelSettings()))
    }

    @Test func publicChannelNamesFollowTheModemPreset() {
        #expect(MeshtasticChannel.publicNameFor(preset: preset) == "LongFast")
        #expect(MeshtasticChannel.publicNameFor(preset: nil) == "LongFast")
        #expect(MeshtasticChannel.publicNameFor(preset: .mediumSlow) == "MediumSlow")
        #expect(MeshtasticChannel.publicNameFor(preset: .shortTurbo) == "ShortTurbo")
    }

    /// The trap that length-based classification falls into: the default key is sixteen bytes, exactly like a real
    /// AES-128 key. A channel carrying it written out in full is still readable by every radio on the mesh.
    @Test func theDefaultKeyWrittenOutInFullIsStillPublic() {
        var spelledOut = ChannelSettings()
        spelledOut.name = "LongFast"
        spelledOut.psk = MeshtasticChannel.defaultKey

        #expect(MeshtasticChannel.isPublic(spelledOut))
        #expect(MeshtasticChannel.isWellKnown(MeshtasticChannel.defaultKey))
    }

    @Test func everyWellKnownNeighbourOfTheDefaultKeyIsPublicHoweverItIsWritten() {
        for shorthand in 1...MeshtasticChannel.maxShorthand {
            let expanded = MeshtasticChannel.expandPsk(bytes(UInt8(shorthand)))
            var settings = ChannelSettings()
            settings.name = "x"
            settings.psk = expanded

            #expect(MeshtasticChannel.isWellKnown(expanded), "shorthand \(shorthand)")
            #expect(MeshtasticChannel.isPublic(settings), "shorthand \(shorthand)")
        }
    }

    @Test func aRealKeyIsNotMistakenForAWellKnownOne() {
        #expect(!MeshtasticChannel.isWellKnown(repeatedBytes(count: 32, value: 4)))
        #expect(!MeshtasticChannel.isWellKnown(repeatedBytes(count: 16, value: 4)))
        #expect(!MeshtasticChannel.isWellKnown(nil))
    }

    @Test func everyPresetNameFitsTheElevenByteChannelNameLimit() {
        // nanopb caps ChannelSettings.name at 12 bytes including the terminator, so a longer name could not be what the
        // firmware uses.
        for preset in Config.LoRaConfig.ModemPreset.allCases {
            let name = MeshtasticChannel.publicNameFor(preset: preset)
            #expect(name.utf8.count <= 11, "\(preset) -> \"\(name)\" is \(name.utf8.count) bytes")
        }
    }

    @Test func interopChannelsNeverGatewayToTheInternetOrCarryAPosition() throws {
        let built = [
            MeshtasticChannel.publicChannel(index: 1, preset: preset),
            try MeshtasticChannel.privateChannel(
                index: 2,
                name: "camp",
                psk: repeatedBytes(count: 32, value: 3)
            ),
        ]

        for channel in built {
            let settings = channel.settings
            #expect(!settings.uplinkEnabled)
            #expect(!settings.downlinkEnabled)
            #expect(Int(settings.moduleSettings.positionPrecision) == PositionPrecision.disabled)
        }
    }

    @Test func aPrivateInteropChannelRefusesAKeyLengthTheFirmwareWouldNotAccept() {
        let tooShort = repeatedBytes(count: 8, value: 0)

        #expect(throws: MeshtasticChannelError.self) {
            _ = try MeshtasticChannel.privateChannel(index: 1, name: "camp", psk: tooShort)
        }
    }

    @Test func aPublicInteropChannelIsRecognisableAsPublicOnceBuilt() {
        let channel = MeshtasticChannel.publicChannel(index: 1, preset: preset)

        #expect(MeshtasticChannel.isPublic(channel.settings))
        #expect(PrimaryChannel.modeOf(channel) == nil)
    }
}
