import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation
import Testing

@testable import FirepitData

@Suite("LocationRepository port tests", .serialized)
struct LocationRepositoryTests {
    @Test func shareWithRecordsDeadline() async throws {
        let harness = try d4Harness()
        let source = ScriptedLocationSource()
        let repository = LocationRepository(
            mesh: harness.base.mesh,
            admin: harness.admin,
            phoneLocation: source,
            rooms: harness.repository,
            sharingStore: harness.sharing,
            nowMillis: { 1_000 }
        )
        try await repository.shareWith(roomId: 42, choice: .hour)
        #expect(harness.sharing.deadline.value?.roomId == 42)
        #expect(harness.sharing.deadline.value?.endsAt == 3_601_000)
    }

    @Test func shareWithNilStopsSharing() async throws {
        let harness = try d4Harness()
        let repository = LocationRepository(
            mesh: harness.base.mesh,
            admin: harness.admin,
            phoneLocation: ScriptedLocationSource(),
            rooms: harness.repository,
            sharingStore: harness.sharing,
            nowMillis: { 1_000 }
        )
        try await repository.shareWith(roomId: 42, choice: .hour)
        try await repository.shareWith(roomId: nil, choice: .hour)
        #expect(harness.sharing.deadline.value == nil)
    }

    @Test func shareWithRejectsUnknownRoom() async throws {
        let harness = try d4Harness()
        let repository = LocationRepository(
            mesh: harness.base.mesh,
            admin: harness.admin,
            phoneLocation: ScriptedLocationSource(),
            rooms: harness.repository,
            sharingStore: harness.sharing
        )
        await #expect(throws: Error.self) {
            try await repository.shareWith(roomId: 99, choice: .hour)
        }
    }

    @Test func activeSharingRoomPausesWhenRoomDisappears() async throws {
        let harness = try d4Harness()
        let repository = LocationRepository(
            mesh: harness.base.mesh,
            admin: harness.admin,
            phoneLocation: ScriptedLocationSource(),
            rooms: harness.repository,
            sharingStore: harness.sharing
        )
        try await repository.shareWith(roomId: 42, choice: .hour)
        #expect(repository.activeSharingRoom() == 42)
        harness.base.mesh.channels.set([])
        #expect(repository.sharingRoomId() == nil)
        #expect(harness.sharing.deadline.value?.roomId == 42)
    }

    @Test func enforceDeadlineClearsExpiredShare() async throws {
        let now = Mutex<Int64>(1_000)
        let harness = try d4Harness()
        let repository = LocationRepository(
            mesh: harness.base.mesh,
            admin: harness.admin,
            phoneLocation: ScriptedLocationSource(),
            rooms: harness.repository,
            sharingStore: harness.sharing,
            nowMillis: { now.withLock { $0 } }
        )
        try await repository.shareWith(roomId: 42, choice: .hour)
        now.withLock { $0 = 4_000_000 }
        repository.enforceDeadline()
        #expect(harness.sharing.deadline.value == nil)
    }

    @Test func setMapVisibleStartsLocalPositionStorage() async throws {
        let harness = try d4Harness()
        try await harness.base.nodeDao.save(node: MeshNode(nodeNum: 111), now: 1)
        let source = ScriptedLocationSource()
        let repository = LocationRepository(
            mesh: harness.base.mesh,
            admin: harness.admin,
            phoneLocation: source,
            rooms: harness.repository,
            sharingStore: harness.sharing
        )
        repository.start()
        repository.setMapVisible(visible: true)
        try? await Task.sleep(for: .milliseconds(20))
        source.push(location(lat: 1.2, lon: 3.4, timeMillis: 2_000))
        try? await Task.sleep(for: .milliseconds(20))
        let node = try await harness.base.nodeDao.find(nodeNum: 111)
        #expect(node?.latitudeI == 12_000_000)
        #expect(node?.longitudeI == 34_000_000)
    }

    @Test func sharingSendsSealedPositionToChosenRoom() async throws {
        let harness = try d4Harness()
        let source = ScriptedLocationSource()
        let repository = LocationRepository(
            mesh: harness.base.mesh,
            admin: harness.admin,
            phoneLocation: source,
            rooms: harness.repository,
            sharingStore: harness.sharing
        )
        repository.start()
        try await repository.shareWith(roomId: 42, choice: .hour)
        try? await Task.sleep(for: .milliseconds(20))
        source.push(location(lat: 1.0, lon: 2.0, altitude: 9, speed: 3, timeMillis: 2_000))
        #expect(await waitUntil { harness.base.link.sent.contains { $0.packet.decoded.portnum == .privateApp } })
        let packet = try lastSentPacket(harness)
        #expect(packet.decoded.portnum == .privateApp)
        #expect(packet.channel == 1)
        #expect(packet.wantAck)
        #expect(packet.hopLimit == MeshConstants.defaultHopLimit)
        let control = try openedControl(from: packet, key: roomKey(harness), roomId: 42, sender: 111)
        #expect(control.position.latitudeI == 10_000_000)
        #expect(!packet.decoded.payload.contains(Data([0x80, 0x96, 0x98])))
    }

    @Test func noPositionSentWithoutChosenRoom() async throws {
        let harness = try d4Harness()
        let source = ScriptedLocationSource()
        let repository = LocationRepository(
            mesh: harness.base.mesh,
            admin: harness.admin,
            phoneLocation: source,
            rooms: harness.repository,
            sharingStore: harness.sharing
        )
        repository.start()
        repository.setMapVisible(visible: true)
        try? await Task.sleep(for: .milliseconds(20))
        source.push(location(lat: 1, lon: 2))
        try? await Task.sleep(for: .milliseconds(20))
        #expect(harness.base.link.sent.isEmpty)
    }

    @Test func smartIntervalPreventsStationaryResend() async throws {
        let harness = try d4Harness()
        let now = Mutex<Int64>(1_000)
        var snapshot = makeSnapshot()
        var position = Config.PositionConfig()
        position.positionBroadcastSecs = 900
        position.positionBroadcastSmartEnabled = true
        position.broadcastSmartMinimumDistance = 50
        position.broadcastSmartMinimumIntervalSecs = 30
        var config = Config()
        config.position = position
        snapshot.configs.append(config)
        harness.base.mesh.snapshot.set(snapshot)
        let source = ScriptedLocationSource()
        let repository = LocationRepository(
            mesh: harness.base.mesh,
            admin: harness.admin,
            phoneLocation: source,
            rooms: harness.repository,
            sharingStore: harness.sharing,
            nowMillis: { now.withLock { $0 } }
        )
        repository.start()
        try await repository.shareWith(roomId: 42, choice: .hour)
        try? await Task.sleep(for: .milliseconds(20))
        source.push(location(lat: 1, lon: 2, timeMillis: now.withLock { $0 }))
        #expect(await waitUntil { !harness.base.link.sent.isEmpty })
        now.withLock { $0 += 31_000 }
        source.push(location(lat: 1.00001, lon: 2, timeMillis: now.withLock { $0 }))
        try? await Task.sleep(for: .milliseconds(20))
        #expect(harness.base.link.sent.count == 1)
    }

    @Test func movedEnoughSendsBeforeRegularInterval() async throws {
        let harness = try d4Harness()
        let now = Mutex<Int64>(1_000)
        var snapshot = makeSnapshot()
        var position = Config.PositionConfig()
        position.positionBroadcastSecs = 900
        position.positionBroadcastSmartEnabled = true
        position.broadcastSmartMinimumDistance = 5
        position.broadcastSmartMinimumIntervalSecs = 30
        var config = Config()
        config.position = position
        snapshot.configs.append(config)
        harness.base.mesh.snapshot.set(snapshot)
        let source = ScriptedLocationSource()
        let repository = LocationRepository(
            mesh: harness.base.mesh,
            admin: harness.admin,
            phoneLocation: source,
            rooms: harness.repository,
            sharingStore: harness.sharing,
            nowMillis: { now.withLock { $0 } }
        )
        repository.start()
        try await repository.shareWith(roomId: 42, choice: .hour)
        try? await Task.sleep(for: .milliseconds(20))
        source.push(location(lat: 1, lon: 2, timeMillis: now.withLock { $0 }))
        #expect(await waitUntil { harness.base.link.sent.count == 1 })
        now.withLock { $0 += 31_000 }
        source.push(location(lat: 1.001, lon: 2, timeMillis: now.withLock { $0 }))
        #expect(await waitUntil { harness.base.link.sent.count == 2 })
    }

    @Test func receivedSealedPositionIsStoredFullPrecision() async throws {
        let harness = try d4Harness()
        let repository = LocationRepository(
            mesh: harness.base.mesh,
            admin: harness.admin,
            phoneLocation: ScriptedLocationSource(),
            rooms: harness.repository,
            sharingStore: harness.sharing
        )
        try await harness.base.nodeDao.save(node: MeshNode(nodeNum: 222), now: 1)
        repository.start()
        try? await Task.sleep(for: .milliseconds(20))
        harness.repository.openedInRooms.send(positionPacket(from: 222, to: 111))
        #expect(
            await waitUntilAsync {
                (try? await harness.base.nodeDao.find(nodeNum: 222)?.positionPrecision) == PositionPrecision.full
            })
        let node = try await harness.base.nodeDao.find(nodeNum: 222)
        #expect(node?.positionPrecision == PositionPrecision.full)
    }

    @Test func askForPositionNeedsConnection() async throws {
        let harness = try d4Harness(myNodeNum: nil)
        let repository = LocationRepository(
            mesh: harness.base.mesh,
            admin: harness.admin,
            phoneLocation: ScriptedLocationSource(),
            rooms: harness.repository,
            sharingStore: harness.sharing
        )
        #expect(await repository.askForPosition(nodeNum: 222) == .notConnected)
    }

    @Test func askForPositionNeedsSharedRoom() async throws {
        let harness = try d4Harness()
        let repository = LocationRepository(
            mesh: harness.base.mesh,
            admin: harness.admin,
            phoneLocation: ScriptedLocationSource(),
            rooms: harness.repository,
            sharingStore: harness.sharing
        )
        #expect(await repository.askForPosition(nodeNum: 222) == .noSharedRoom)
    }

    @Test func askForPositionSendsQuery() async throws {
        let harness = try d4Harness()
        try await rememberMembers(harness, nodes: [222])
        let repository = LocationRepository(
            mesh: harness.base.mesh,
            admin: harness.admin,
            phoneLocation: ScriptedLocationSource(),
            rooms: harness.repository,
            sharingStore: harness.sharing
        )
        #expect(await repository.askForPosition(nodeNum: 222) == .asked)
        let packet = try lastSentPacket(harness)
        #expect(Int32(bitPattern: packet.to) == 222)
        let control = try openedControl(from: packet, key: roomKey(harness), roomId: 42, sender: 111)
        if case .positionQuery = control.payload {
            #expect(true)
        } else {
            Issue.record("expected position query")
        }
    }
}
