import FirepitModel
import Testing

@testable import Firepit

/// The map beside a conversation follows it (UX §6.11.6). Ported from
/// android/app/src/test/…/map/MapSelectionTest.kt, case for case.
@Suite("Map selection")
struct MapSelectionTests {
    private let me: Int32 = 1
    private let maya: Int32 = 2
    private let ryan: Int32 = 3
    private let stranger: Int32 = 9
    private let camp = Following.room(roomId: 100, name: "Camp")
    private let withMaya = Following.direct(nodeNum: 2, name: "Maya")

    private var onMap: [MeshNode] { [me, maya, ryan, stranger].map { MeshNode(nodeNum: $0) } }

    private func nums(_ nodes: [MeshNode]) -> [Int32] { nodes.map(\.nodeNum) }

    private func pin(_ id: Int32, roomId: Int32) -> MapPin {
        MapPin(
            id: id, channel: 1, latitudeI: 0, longitudeI: 0, name: "p\(id)", createdBy: maya, receivedAt: 0,
            roomId: roomId)
    }

    private func room(_ id: Int32, index: Int) -> RoomChannel {
        RoomChannel(index: index, name: "r\(id)", role: .secondary, id: id, positionPrecision: 32, kind: .firepit)
    }

    @Test func followingARoomShowsItsMembersAndYouAndNobodyElse() {
        let shown = MapSelection.nodes(
            onMap, following: camp, followedNodes: [maya], filter: .all, ours: [maya, ryan], myNodeNum: me)
        #expect(nums(shown) == [me, maya])
    }

    @Test func followingADirectChatShowsThatPersonAndYou() {
        let shown = MapSelection.nodes(
            onMap, following: withMaya, followedNodes: [maya], filter: .ours, ours: [], myNodeNum: me)
        #expect(nums(shown) == [me, maya])
    }

    @Test func withNothingFollowedTheChosenFilterDecides() {
        #expect(
            nums(MapSelection.nodes(onMap, following: nil, followedNodes: [], filter: .all, ours: [maya], myNodeNum: me))
                == nums(onMap))
        #expect(
            nums(MapSelection.nodes(onMap, following: nil, followedNodes: [], filter: .ours, ours: [maya], myNodeNum: me))
                == [maya])
    }

    @Test func aFollowedRoomKeepsItsOwnPinsAndADirectChatNone() {
        let pins = [pin(1, roomId: 100), pin(2, roomId: 200)]
        #expect(MapSelection.pins(pins, following: camp).map(\.id) == [1])
        #expect(MapSelection.pins(pins, following: withMaya).isEmpty)
        #expect(MapSelection.pins(pins, following: nil).map(\.id) == [1, 2])
    }

    @Test func stoppingHoldsOnlyForTheConversationItWasStoppedFor() {
        #expect(MapSelection.followed(open: camp, stopped: camp) == nil)
        #expect(MapSelection.followed(open: withMaya, stopped: camp) == withMaya)
        #expect(MapSelection.followed(open: camp, stopped: nil) == camp)
        #expect(MapSelection.followed(open: nil, stopped: nil) == nil)
    }

    @Test func aPinGoesToTheOpenRoomFirstThenTheSharedOneThenTheOnlyOne() {
        let a = room(100, index: 1)
        let b = room(200, index: 2)
        #expect(MapSelection.pinRoom([a, b], open: camp, sharingRoomId: 200) == a)
        #expect(MapSelection.pinRoom([a, b], open: withMaya, sharingRoomId: 200) == b)
        #expect(MapSelection.pinRoom([a, b], open: nil, sharingRoomId: 200) == b)
        #expect(MapSelection.pinRoom([a], open: nil, sharingRoomId: nil) == a)
        #expect(MapSelection.pinRoom([a, b], open: nil, sharingRoomId: nil) == nil)
    }

    @Test func anOpenRoomYouCannotShareInIsPassedOverNotGuessedAt() {
        let b = room(200, index: 2)
        #expect(MapSelection.pinRoom([b], open: camp, sharingRoomId: nil) == b)
    }
}
