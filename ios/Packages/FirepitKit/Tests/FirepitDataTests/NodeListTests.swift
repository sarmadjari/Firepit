import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation
import Testing

@testable import FirepitData

/// How a node from the radio's node list is stored: MeshRepository.saveNode against android/…/MeshRepository.kt.
@Suite("Nodes from the radio's list")
struct NodeListTests {
    private func node(
        _ num: Int32,
        latitudeI: Int32? = 377_396_000,
        battery: UInt32? = nil,
        favorite: Bool = false
    ) -> NodeInfo {
        var user = User()
        user.id = MeshConstants.formatNodeId(num)
        user.longName = "Radio \(num)"
        user.shortName = "R\(num % 10)"
        user.hwModel = .tbeam
        user.role = .router
        var info = NodeInfo()
        info.num = UInt32(bitPattern: num)
        info.user = user
        if let latitudeI {
            var position = Position()
            position.latitudeI = latitudeI
            position.longitudeI = -1_195_655_000
            position.precisionBits = 13
            info.position = position
        }
        if let battery {
            var metrics = DeviceMetrics()
            metrics.batteryLevel = battery
            info.deviceMetrics = metrics
        }
        info.isFavorite = favorite
        return info
    }

    private func load(_ harness: Harness, nodes: [NodeInfo]) async throws {
        var snapshot = makeSnapshot()
        for info in nodes {
            snapshot.nodes[Int32(bitPattern: info.num)] = info
        }
        harness.mesh.start()
        await harness.connect(snapshot)
        let expected = nodes.map { Int32(bitPattern: $0.num) }
        #expect(
            try await eventually {
                for num in expected where try await harness.nodeDao.find(nodeNum: num) == nil {
                    return false
                }
                return true
            })
    }

    @Test func anUnsealedPositionIsKeptForSomebodyOutsideOurRooms() async throws {
        let harness = try Harness()
        try await load(harness, nodes: [node(222)])

        let stored = try await harness.nodeDao.find(nodeNum: 222)
        #expect(stored?.latitudeI == 377_396_000)
        #expect(stored?.longitudeI == -1_195_655_000)
        #expect(stored?.positionPrecision == 13)
    }

    /// Members' positions only travel sealed, so one in the radio's list was written by whoever holds a radio.
    @Test func anUnsealedPositionIsNeverBelievedForARoomMember() async throws {
        let harness = try Harness()
        try await harness.memberDao.record(roomId: 42, nodeNum: 333, now: 1)
        try await load(harness, nodes: [node(333)])

        let stored = try await harness.nodeDao.find(nodeNum: 333)
        #expect(stored != nil)
        #expect(stored?.latitudeI == nil)
        #expect(stored?.longitudeI == nil)
    }

    @Test func aZeroLatitudeMeansNoFix() async throws {
        let harness = try Harness()
        try await load(harness, nodes: [node(444, latitudeI: 0)])

        #expect(try await harness.nodeDao.find(nodeNum: 444)?.latitudeI == nil)
    }

    @Test func anAbsentReadingIsUnknownNotZero() async throws {
        let harness = try Harness()
        try await load(harness, nodes: [node(555), node(556, battery: 64)])

        #expect(try await harness.nodeDao.find(nodeNum: 555)?.batteryLevel == nil)
        #expect(try await harness.nodeDao.find(nodeNum: 556)?.batteryLevel == 64)
    }

    @Test func theRadiosFavouritesAreKept() async throws {
        let harness = try Harness()
        try await load(harness, nodes: [node(666, favorite: true), node(667)])

        #expect(try await harness.nodeDao.find(nodeNum: 666)?.isFavorite == true)
        #expect(try await harness.nodeDao.find(nodeNum: 667)?.isFavorite == false)
    }

    @Test func hardwareAndRoleAreStoredByTheirProtobufNames() async throws {
        let harness = try Harness()
        try await load(harness, nodes: [node(777)])

        let stored = try await harness.nodeDao.find(nodeNum: 777)
        #expect(stored?.hwModel == "TBEAM")
        #expect(stored?.role == "ROUTER")
    }
}
