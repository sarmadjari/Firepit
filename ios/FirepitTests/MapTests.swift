import FirepitData
import FirepitModel
import FirepitProtocol
import Testing

@testable import Firepit

@Suite("Map")
struct MapTests {
    @Test func markerLabelLeavesSelfWithoutAge() {
        let marker = marker(isSelf: true, name: "Sam", minutes: 60)
        #expect(MapWords.markerLabel(marker) == "Sam")
    }

    @Test func markerLabelAddsMinuteAge() {
        let marker = marker(name: "Ava", minutes: 12)
        #expect(MapWords.markerLabel(marker) == "Ava · 12m")
    }

    @Test func markerLabelAddsHourAge() {
        let marker = marker(name: "Ava", minutes: 125)
        #expect(MapWords.markerLabel(marker) == "Ava · 2h")
    }

    @Test func markerLabelAddsDayAge() {
        let marker = marker(name: "Ava", minutes: 60 * 49)
        #expect(MapWords.markerLabel(marker) == "Ava · 2d")
    }

    @Test func agePhraseUsesPlainWords() {
        #expect(MapWords.agePhrase(minutes: 0) == "just now")
        #expect(MapWords.agePhrase(minutes: 1) == "1 minute ago")
        #expect(MapWords.agePhrase(minutes: 90) == "1 hour ago")
        #expect(MapWords.agePhrase(minutes: 60 * 24 * 3) == "3 days ago")
    }

    @Test func tallyCountsPeopleLiveAndPins() {
        let state = MapUiState(
            markers: [marker(isSelf: true), marker(isLive: true), marker(isLive: false)],
            pins: [pin(id: 1)],
            myNodeNum: 1
        )
        #expect(MapWords.tally(state) == "2 people · 1 live · 1 pin")
    }

    @Test func saidOfSilentExplainsMeshSilence() {
        let text = MapWords.saidOf(answer: .silent, name: "Ava")
        #expect(text.contains("No answer from Ava"))
        #expect(text.contains("pin still shows where they were last seen"))
    }

    @Test func sweptWordsIncludesCurrentAndOutOfReach() {
        #expect(
            MapWords.sweptWords(asked: 2, outOfReach: 1, current: 3)
                == "Asked 2 people; pins move as they answer. 3 already current. 1 out of reach."
        )
    }

    @Test func markerCourseRequiresMovement() {
        var node = node(latitude: 1, longitude: 2)
        node.groundTrack = 9_000
        node.groundSpeed = 2
        #expect(marker(node: node).course == nil)
        node.groundSpeed = 5
        #expect(marker(node: node).course == 90)
    }

    @Test func approximateMarkerFollowsPrecisionBits() {
        var node = node(latitude: 1, longitude: 2)
        node.positionPrecision = 24
        #expect(marker(node: node).isApproximate)
        node.positionPrecision = 32
        #expect(!marker(node: node).isApproximate)
    }

    @Test func hiddenNodesAreExcludedByReducerRule() {
        let hidden = SavedRadios.hiddenNodes(radios: [
            SavedRadio(identifier: "a", name: "Base", role: .base, nodeNum: 42, onMap: false),
            SavedRadio(identifier: "b", name: "Pocket", role: .personal, nodeNum: 7, onMap: true),
        ])
        #expect(hidden == [42])
    }

    @Test func pinValidationTrimsAndLimits() {
        let long = String(repeating: "x", count: 40)
        #expect(PinsReducer.validPinName("  Water  ") == "Water")
        #expect(PinsReducer.validPinName("   ") == nil)
        #expect(PinsReducer.validPinName(long)?.count == pinNameLimit)
    }

    @Test func pinsSortByNameThenId() {
        let pins = PinsReducer.sortedPins([
            pin(id: 3, name: "Zulu"), pin(id: 2, name: "alpha"), pin(id: 1, name: "Alpha"),
        ])
        #expect(pins.map(\.id) == [1, 2, 3])
    }

    @Test func cameraFramesOnlyOfflineInitialMarkers() {
        #expect(CameraDecision.shouldFrameInitially(markerCount: 1, offlineOnly: true, alreadyFramed: false))
        #expect(!CameraDecision.shouldFrameInitially(markerCount: 1, offlineOnly: false, alreadyFramed: false))
        #expect(!CameraDecision.shouldFrameInitially(markerCount: 0, offlineOnly: true, alreadyFramed: false))
        #expect(!CameraDecision.shouldFrameInitially(markerCount: 1, offlineOnly: true, alreadyFramed: true))
    }

    private func marker(
        node: MeshNode? = nil,
        isLive: Bool = true,
        isSelf: Bool = false,
        name: String = "Ava",
        minutes: Int64? = 1
    ) -> MapMarker {
        MapMarker(
            node: node ?? self.node(latitude: 37.33, longitude: -122.03),
            isLive: isLive,
            isSelf: isSelf,
            name: name,
            tag: "AV",
            fixAgeMinutes: minutes
        )
    }

    private func node(latitude: Double, longitude: Double) -> MeshNode {
        MeshNode(
            nodeNum: 7,
            shortName: "AV",
            latitudeI: Int32(latitude * 1e7),
            longitudeI: Int32(longitude * 1e7)
        )
    }

    private func pin(id: Int32, name: String = "Pin") -> MapPin {
        MapPin(
            id: id,
            channel: 1,
            latitudeI: 373_300_000,
            longitudeI: -1_220_300_000,
            name: name,
            createdBy: 7,
            receivedAt: 1
        )
    }
}
