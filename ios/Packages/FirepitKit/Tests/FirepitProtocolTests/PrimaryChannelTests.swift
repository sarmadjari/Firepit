import FirepitProtos
import Foundation
import Testing

@testable import FirepitProtocol

@Suite struct PrimaryChannelTests {
    /// The one fact both apps must agree on byte-for-byte. Android and iOS generate from the same `protos/` tree, so
    /// the key lives there and this asserts the embedded copy has not drifted from it.
    @Test func embeddedKeyMatchesTheSharedProtosFile() throws {
        let file = try #require(
            Self.sharedProtosFile("meshchat-primary-key.txt"),
            "protos/meshchat-primary-key.txt not found above this source file or the test working directory")

        #expect(
            try String(contentsOf: file, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
                == PrimaryChannel.keyBase64)
    }

    /// The repository's `protos/<name>`, found by walking up from this source file first: under `xcodebuild test`
    /// the bundle runs in the simulator, whose working directory is not the repository. The working directory is the
    /// fallback. Only a directory named exactly `protos` counts, so on a case-insensitive disk the package's vendored
    /// `Protos/` copy is not mistaken for the shared tree.
    private static func sharedProtosFile(_ name: String, sourceFile: String = #filePath) -> URL? {
        let starts = [
            URL(fileURLWithPath: sourceFile).deletingLastPathComponent(),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath),
        ]
        for start in starts {
            var directory = start
            while true {
                let entries = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
                let candidate = directory.appendingPathComponent("protos").appendingPathComponent(name)
                if entries.contains("protos"), FileManager.default.fileExists(atPath: candidate.path) {
                    return candidate
                }
                let parent = directory.deletingLastPathComponent()
                if parent.path == directory.path { break }
                directory = parent
            }
        }
        return nil
    }

    @Test func keyIsAFull32BytesSoTheFirmwareTreatsItAsAES256() {
        // 16 and 32 are the only private lengths; a 1-byte key is one of the published defaults and an empty one
        // disables encryption entirely.
        #expect(PrimaryChannel.key.count == 32)
        #expect(ChannelKey.of(PrimaryChannel.key).isPrivate)
    }

    @Test func groupOnlyNamesThePrimaryPublicRelayLeavesItEmpty() {
        // The firmware hashes this name into the frequency slot: a name of our own is a slot of our own, and an empty
        // one follows the modem preset onto the public mesh.
        #expect(PrimaryChannel.nameFor(mode: .groupOnly) == "MeshChat")
        #expect(PrimaryChannel.nameFor(mode: .publicRelay) == "")
    }

    @Test func thePrimaryNeverGatewaysToTheInternetOrCarriesAPosition() {
        for mode in RangeMode.allCases {
            let settings = PrimaryChannel.channelFor(mode: mode).settings
            #expect(!settings.uplinkEnabled, "\(mode.name)")
            #expect(!settings.downlinkEnabled, "\(mode.name)")
            #expect(Int(settings.moduleSettings.positionPrecision) == PositionPrecision.disabled, "\(mode.name)")
            #expect(PrimaryChannel.channelFor(mode: mode).role == .primary, "\(mode.name)")
            #expect(Int(PrimaryChannel.channelFor(mode: mode).index) == PrimaryChannel.slot, "\(mode.name)")
        }
    }

    @Test func aChannelWeJustBuiltIsRecognisedAsAlreadyCorrect() {
        for mode in RangeMode.allCases {
            #expect(PrimaryChannel.matches(PrimaryChannel.channelFor(mode: mode), mode: mode), "\(mode.name)")
            #expect(PrimaryChannel.modeOf(PrimaryChannel.channelFor(mode: mode)) == mode, "\(mode.name)")
        }
    }

    @Test func switchingModeIsARewriteNotANoOp() {
        let groupOnly = PrimaryChannel.channelFor(mode: .groupOnly)
        #expect(!PrimaryChannel.matches(groupOnly, mode: .publicRelay))
    }

    @Test func aStockLongFastPrimaryIsNeitherOursNorPrivate() {
        // What every radio ships with: the published one-byte default key.
        var settings = ChannelSettings()
        settings.name = ""
        settings.psk = bytes(0x01)
        var stock = Channel()
        stock.index = 0
        stock.role = .primary
        stock.settings = settings

        #expect(PrimaryChannel.isPublic(stock))
        #expect(PrimaryChannel.modeOf(stock) == nil)
        for mode in RangeMode.allCases {
            #expect(!PrimaryChannel.matches(stock, mode: mode), "\(mode.name)")
        }
    }

    @Test func anUnwrittenPrimaryIsReportedPublicRatherThanAssumedFine() {
        var channel = Channel()
        channel.index = 0
        channel.settings = ChannelSettings()
        #expect(PrimaryChannel.isPublic(nil))
        #expect(PrimaryChannel.isPublic(channel))
    }

    @Test func rangeModeRoundTripsThroughTheWireEnum() {
        for mode in RangeMode.allCases {
            #expect(RangeMode.of(mode.wire) == mode)
        }
        #expect(RangeMode.of(.groupOnly) == .groupOnly)
        #expect(RangeMode.of(.publicRelay) == .publicRelay)
    }

    @Test func anUnknownOrAbsentModeFallsBackToTheQuietOne() {
        #expect(RangeMode.groupOnly == RangeMode.default)
        #expect(RangeMode.of(nil) == RangeMode.default)
        #expect(RangeMode.named(nil) == RangeMode.default)
        #expect(RangeMode.named("SOMETHING_A_LATER_BUILD_ADDED") == RangeMode.default)
        #expect(RangeMode.named("PUBLIC_RELAY") == .publicRelay)
    }

    /// Nothing is taken over until somebody says so, and "not asked" has to stay distinct from "said no" — only the
    /// first is worth interrupting for.
    @Test func aRadioStartsUndecidedRatherThanClaimed() {
        #expect(RadioPrivacy.undecided == RadioPrivacy.default)
        #expect(RadioPrivacy.named(nil) == RadioPrivacy.default)
        #expect(RadioPrivacy.named("") == RadioPrivacy.default)
        #expect(RadioPrivacy.named("SOMETHING_LATER") == RadioPrivacy.default)
    }

    @Test func eachPrivacyChoiceRoundTripsThroughItsStoredName() {
        for choice in RadioPrivacy.allCases {
            #expect(RadioPrivacy.named(choice.name) == choice)
        }
    }

    /// Every state has something to show, so the settings screen never renders blank.
    @Test func everyChoiceCarriesWordingForTheScreen() {
        for choice in RadioPrivacy.allCases {
            #expect(!choice.label.isEmpty, "\(choice.name)")
            #expect(!choice.summary.isEmpty, "\(choice.name)")
        }
        for choice in RangeMode.allCases {
            #expect(!choice.label.isEmpty, "\(choice.name)")
            #expect(!choice.summary.isEmpty, "\(choice.name)")
        }
    }
}
