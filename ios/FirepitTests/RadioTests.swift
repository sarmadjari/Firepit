import FirepitData
import FirepitModel
import FirepitProtocol
import FirepitTransport
import Foundation
import Testing

@testable import Firepit

@Suite("Radio view model helpers")
struct RadioTests {
    @Test func shortAgeFormatsRecentTimes() {
        #expect(shortAge(1_000_000, now: 1_000_030) == "just now")
        #expect(shortAge(1_000_000, now: 1_000_000 + 5 * 60_000) == "5m ago")
    }

    @Test func shortAgeFormatsHoursDaysAndLongAgo() {
        #expect(shortAge(0, now: 2 * 60 * 60_000) == "2h ago")
        #expect(shortAge(0, now: 3 * 24 * 60 * 60_000) == "3d ago")
        #expect(shortAge(0, now: 40 * 24 * 60 * 60_000) == "long ago")
    }

    @Test func prettyNameTurnsConstantsIntoWords() {
        #expect(prettyName("CLIENT_BASE") == "Client Base")
        #expect(prettyName("ROUTER_LATE") == "Router Late")
    }

    @Test func pinTextPadsToSixDigits() {
        #expect(pinText(42) == "000042")
        #expect(pinText(654_321) == "654321")
    }

    @Test func nodeDetailIncludesIdentitySignalPowerAndAge() {
        let node = MeshNode(nodeNum: 0x1234, lastHeard: 0, snr: 2.25, hopsAway: 0, batteryLevel: 101)
        #expect(nodeDetail(node, now: 60_000) == "!00001234 · direct · 2.2 dB · powered · heard 1m ago")
    }

    @Test func nodeDetailDescribesUnknownHeardTime() {
        let node = MeshNode(nodeNum: -1, hopsAway: 2, batteryLevel: 87)
        #expect(nodeDetail(node, now: 0) == "!ffffffff · 2 hops · 87% · not heard yet")
    }

    @Test func nodeFactsOnlyIncludesReportedFields() {
        let node = MeshNode(nodeNum: 7, hwModel: "T_ECHO", role: "CLIENT", publicKey: nil)
        let labels = nodeFacts(node).map(\.0)
        #expect(labels.contains("Node ID"))
        #expect(labels.contains("Hardware"))
        #expect(!labels.contains("Signal strength"))
        #expect(nodeFacts(node).contains { $0 == "Encryption key" && $1 == "Not shared" })
    }

    @Test func nodeFactsFormatsPositionAndTelemetry() {
        let node = MeshNode(
            nodeNum: 7,
            rssi: -91,
            voltage: 3.91,
            channelUtilization: 4.25,
            airUtilTx: 1.2,
            latitudeI: 525_200_000,
            longitudeI: 134_050_000,
            altitude: 34,
            groundSpeed: 6,
            groundTrack: 90
        )
        let facts = Dictionary(uniqueKeysWithValues: nodeFacts(node))
        #expect(facts["Position"] == "52.52000, 13.40500")
        #expect(facts["Voltage"] == "3.91 V")
        #expect(facts["Channel busy"] == "4.2%")
        #expect(facts["Air time sending"] == "1.2%")
    }

    @Test func savedNodesSortSelfFirstThenNearestThenNewest() {
        let old = MeshNode(nodeNum: 1, lastHeard: 10, hopsAway: 2)
        let own = MeshNode(nodeNum: 2, lastHeard: 1, hopsAway: 9)
        let near = MeshNode(nodeNum: 3, lastHeard: 2, hopsAway: 0)
        let newer = MeshNode(nodeNum: 4, lastHeard: 20, hopsAway: 2)
        #expect(RadioViewModel.sortedNodes([old, own, near, newer], myNodeNum: 2).map(\.nodeNum) == [2, 3, 4, 1])
    }

    @Test func linkStateWordsMatchAndroidCopy() {
        #expect(linkStateWords(.disconnected) == "Not connected")
        #expect(linkStateWords(.connecting(attempt: 0)) == "Connecting…")
        #expect(linkStateWords(.downloading) == "Reading your node…")
        #expect(linkStateWords(.ready(.init())) == "Active · you are administering this one")
    }

    @Test func linkStateWordsIncludeReconnectAndFirmwareFailures() {
        #expect(linkStateWords(.reconnecting(attempt: 3, cause: "lost")) == "Reconnecting (attempt 3) · lost")
        #expect(
            linkStateWords(.unsupported(nil), minimumFirmware: "2.7.0") == "Firmware unknown · needs 2.7.0 or newer")
    }

    @Test func buzzCopyIsHonestAboutSilence() {
        #expect(RadioViewModel.describeBuzz(.delivered, name: "T-Echo").contains("Silence means"))
        #expect(
            RadioViewModel.describeBuzz(.noAnswer, name: "T-Echo")
                == "T-Echo did not answer. It may be off or out of range.")
    }

    @Test func routeCopyCoversTimeoutDirectAndRelayed() {
        #expect(
            RadioViewModel.describeTrace(nil, name: "Alex")
                == "No reply from Alex within a minute. It may be out of range.")
    }
}
