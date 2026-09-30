import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation
import Testing

@testable import FirepitData

extension LocationRepositoryTests {
    @Test func consolidatedWaypointDropSendsSealedWaypoint() async throws {
        let harness = try d4Harness()
        let repository = WaypointRepository(
            mesh: harness.base.mesh,
            rooms: harness.repository,
            pinDao: harness.pinDao,
            randomId: { 77 }
        )
        try await repository.drop(channel: 1, latitudeI: 11, longitudeI: 22, name: "Tent")
        let packet = try lastSentPacket(harness)
        #expect(packet.decoded.portnum == .privateApp)
        let control = try openedControl(from: packet, key: roomKey(harness), roomId: 42, sender: 111)
        #expect(control.pin.id == 77)
    }

    @Test func consolidatedWaypointDropStoresLocalPin() async throws {
        let harness = try d4Harness()
        let repository = WaypointRepository(
            mesh: harness.base.mesh,
            rooms: harness.repository,
            pinDao: harness.pinDao,
            randomId: { 78 }
        )
        try await repository.drop(channel: 1, latitudeI: 11, longitudeI: 22, name: "Tent")
        #expect(try await harness.pinDao.find(id: 78)?.roomId == 42)
    }

    @Test func consolidatedWaypointLimitsText() async throws {
        let harness = try d4Harness()
        let repository = WaypointRepository(
            mesh: harness.base.mesh,
            rooms: harness.repository,
            pinDao: harness.pinDao,
            randomId: { 79 }
        )
        try await repository.drop(
            channel: 1,
            latitudeI: 1,
            longitudeI: 2,
            name: String(repeating: "é", count: 40),
            description: String(repeating: "x", count: 150)
        )
        let control = try openedControl(
            from: try lastSentPacket(harness),
            key: roomKey(harness),
            roomId: 42,
            sender: 111
        )
        #expect(control.pin.name.utf8.count <= 30)
        #expect(control.pin.description_p.utf8.count <= 100)
    }

    @Test func consolidatedWaypointRenameSendsSameId() async throws {
        let harness = try d4Harness()
        let repository = WaypointRepository(mesh: harness.base.mesh, rooms: harness.repository, pinDao: harness.pinDao)
        let pin = MapPin(
            id: 10, channel: 1, latitudeI: 1, longitudeI: 2, name: "Old", lockedTo: 111,
            createdBy: 111, receivedAt: 1, roomId: 42)
        try await repository.rename(pin: pin, name: "New")
        let control = try openedControl(
            from: try lastSentPacket(harness),
            key: roomKey(harness),
            roomId: 42,
            sender: 111
        )
        #expect(control.pin.id == 10)
        #expect(control.pin.name == "New")
    }

    @Test func consolidatedWaypointRenameRejectsLockedPin() async throws {
        let harness = try d4Harness()
        let repository = WaypointRepository(mesh: harness.base.mesh, rooms: harness.repository, pinDao: harness.pinDao)
        let pin = MapPin(
            id: 10, channel: 1, latitudeI: 1, longitudeI: 2, name: "Old", lockedTo: 222,
            createdBy: 222, receivedAt: 1, roomId: 42)
        await #expect(throws: Error.self) {
            try await repository.rename(pin: pin, name: "Nope")
        }
    }

    @Test func consolidatedWaypointRemoveTombstones() async throws {
        let harness = try d4Harness()
        let repository = WaypointRepository(
            mesh: harness.base.mesh,
            rooms: harness.repository,
            pinDao: harness.pinDao,
            sleep: { _ in }
        )
        let pin = MapPin(
            id: 12, channel: 1, latitudeI: 1, longitudeI: 2, name: "Old", lockedTo: 111,
            createdBy: 111, receivedAt: 1, roomId: 42)
        try await harness.pinDao.save(pin: pin)
        try await repository.remove(pin: pin)
        #expect(try await harness.pinDao.wasDeleted(id: 12))
    }

    @Test func consolidatedWaypointReceivedPinIsStored() async throws {
        let harness = try d4Harness()
        let repository = WaypointRepository(mesh: harness.base.mesh, rooms: harness.repository, pinDao: harness.pinDao)
        repository.start()
        try? await Task.sleep(for: .milliseconds(20))
        harness.repository.openedInRooms.send(makePinOpened(id: 20, sender: 222))
        #expect(await waitUntilAsync { (try? await harness.pinDao.find(id: 20)?.name) == "Camp" })
    }

    @Test func consolidatedWaypointExpiredReceivedPinDeletes() async throws {
        let harness = try d4Harness()
        let repository = WaypointRepository(mesh: harness.base.mesh, rooms: harness.repository, pinDao: harness.pinDao)
        repository.start()
        try await harness.pinDao.save(
            pin: MapPin(
                id: 21, channel: 1, latitudeI: 1, longitudeI: 2, name: "Camp", lockedTo: 222,
                createdBy: 222, receivedAt: 1, roomId: 42)
        )
        try? await Task.sleep(for: .milliseconds(20))
        harness.repository.openedInRooms.send(makePinOpened(id: 21, sender: 222, expire: 1))
        try? await Task.sleep(for: .milliseconds(20))
        #expect(try await harness.pinDao.find(id: 21) == nil)
    }

    @Test func consolidatedWaypointWrongSlotIgnored() async throws {
        let harness = try d4Harness()
        let repository = WaypointRepository(mesh: harness.base.mesh, rooms: harness.repository, pinDao: harness.pinDao)
        repository.start()
        try? await Task.sleep(for: .milliseconds(20))
        harness.repository.openedInRooms.send(makePinOpened(id: 22, sender: 222, onItsSlot: false))
        try? await Task.sleep(for: .milliseconds(20))
        #expect(try await harness.pinDao.find(id: 22) == nil)
    }

    @Test func consolidatedWaypointDeletedDoesNotResurrect() async throws {
        let harness = try d4Harness()
        let repository = WaypointRepository(mesh: harness.base.mesh, rooms: harness.repository, pinDao: harness.pinDao)
        repository.start()
        try await harness.pinDao.remember(deleted: DeletedPinEntity(id: 23, channel: 1, deletedAt: 1))
        try? await Task.sleep(for: .milliseconds(20))
        harness.repository.openedInRooms.send(makePinOpened(id: 23, sender: 222))
        try? await Task.sleep(for: .milliseconds(20))
        #expect(try await harness.pinDao.find(id: 23) == nil)
    }

    @Test func consolidatedWaypointObservePinsYieldsStoredPins() async throws {
        let harness = try d4Harness()
        let repository = WaypointRepository(mesh: harness.base.mesh, rooms: harness.repository, pinDao: harness.pinDao)
        try await harness.pinDao.save(
            pin: MapPin(
                id: 30, channel: 1, latitudeI: 1, longitudeI: 2, name: "Camp", createdBy: 111,
                receivedAt: 1, roomId: 42)
        )
        let pins = try await firstValue(repository.observePins())
        #expect(pins.map(\.id).contains(30))
    }

    @Test func consolidatedTraceNeedsConnection() async throws {
        let harness = try d4Harness(myNodeNum: nil)
        let client = TracerouteClient(link: harness.base.link, mesh: harness.base.mesh, rooms: harness.repository)
        #expect(await client.trace(nodeNum: 222, timeout: .milliseconds(1)) == nil)
    }

    @Test func consolidatedTraceNeedsSharedRoom() async throws {
        let harness = try d4Harness()
        let client = TracerouteClient(link: harness.base.link, mesh: harness.base.mesh, rooms: harness.repository)
        #expect(await client.trace(nodeNum: 222, timeout: .milliseconds(1)) == nil)
    }

    @Test func consolidatedTraceRequestPacketIsRoomScoped() async throws {
        let harness = try d4Harness()
        try await rememberMembers(harness, nodes: [222])
        let client = TracerouteClient(link: harness.base.link, mesh: harness.base.mesh, rooms: harness.repository)
        Task {
            try? await Task.sleep(for: .milliseconds(20))
            harness.base.link.push(d4TracerouteReply(from: 222))
        }
        _ = await client.trace(nodeNum: 222, timeout: .seconds(1))
        let packet = try lastSentPacket(harness)
        #expect(packet.decoded.portnum == .tracerouteApp)
        #expect(packet.decoded.wantResponse)
        #expect(packet.channel == 1)
    }

    @Test func consolidatedTraceParsesRouteAndSnrs() async throws {
        let harness = try d4Harness()
        try await rememberMembers(harness, nodes: [222])
        let client = TracerouteClient(link: harness.base.link, mesh: harness.base.mesh, rooms: harness.repository)
        Task {
            try? await Task.sleep(for: .milliseconds(20))
            harness.base.link.push(d4TracerouteReply(from: 222, route: [10], snr: [8]))
        }
        let result = try #require(await client.trace(nodeNum: 222, timeout: .seconds(1)))
        #expect(result.towards.first?.nodeNum == 10)
        #expect(result.towards.first?.snr == 2)
    }

    @Test func consolidatedTraceSeesReplySentBeforeSendReturns() async throws {
        let harness = try D4SyncHarness()
        try await harness.memberDao.record(roomId: 42, nodeNum: 222, now: 1, invitedBy: 111)
        harness.link.setOnSend { _ in
            harness.link.push(d4TracerouteReply(from: 222, route: [10], snr: [8]))
        }
        let client = TracerouteClient(link: harness.link, mesh: harness.mesh, rooms: harness.repository)
        let result = try #require(await client.trace(nodeNum: 222, timeout: .seconds(1)))
        #expect(result.towards.first?.nodeNum == 10)
        #expect(result.towards.first?.snr == 2)
    }

    @Test func consolidatedTraceParsesBackRoute() async throws {
        let harness = try d4Harness()
        try await rememberMembers(harness, nodes: [222])
        let client = TracerouteClient(link: harness.base.link, mesh: harness.base.mesh, rooms: harness.repository)
        Task {
            try? await Task.sleep(for: .milliseconds(20))
            harness.base.link.push(d4TracerouteReply(from: 222, back: [30], snrBack: [4]))
        }
        let result = try #require(await client.trace(nodeNum: 222, timeout: .seconds(1)))
        #expect(result.back.first?.nodeNum == 30)
        #expect(result.back.first?.snr == 1)
    }

    @Test func consolidatedTraceTimeoutReturnsNil() async throws {
        let harness = try d4Harness()
        try await rememberMembers(harness, nodes: [222])
        let client = TracerouteClient(link: harness.base.link, mesh: harness.base.mesh, rooms: harness.repository)
        #expect(await client.trace(nodeNum: 222, timeout: .milliseconds(1)) == nil)
    }

    @Test func consolidatedTraceIgnoresUnrelatedReplies() async throws {
        let harness = try d4Harness()
        try await rememberMembers(harness, nodes: [222])
        let client = TracerouteClient(link: harness.base.link, mesh: harness.base.mesh, rooms: harness.repository)
        Task {
            try? await Task.sleep(for: .milliseconds(20))
            harness.base.link.push(d4TracerouteReply(from: 333))
        }
        #expect(await client.trace(nodeNum: 222, timeout: .milliseconds(80)) == nil)
    }

    @Test func consolidatedRequestPositionSeesReplySentBeforeSendReturns() async throws {
        let harness = try D4SyncHarness()
        try await harness.memberDao.record(roomId: 42, nodeNum: 222, now: 1, invitedBy: 111)
        let repository = LocationRepository(
            mesh: harness.mesh,
            admin: harness.admin,
            phoneLocation: ScriptedLocationSource(),
            rooms: harness.repository,
            sharingStore: harness.sharing
        )
        repository.start()
        try? await Task.sleep(for: .milliseconds(20))
        harness.link.setOnSend { _ in
            harness.repository.openedInRooms.send(positionPacket(from: 222, to: 111))
        }
        let result = await repository.requestPosition(nodeNum: 222, timeout: .seconds(1))
        #expect(result == .answered)
    }
}

private func makePinOpened(id: UInt32, sender: Int32, expire: UInt32 = 0, onItsSlot: Bool = true) -> OpenedInRoom {
    var waypoint = Waypoint()
    waypoint.id = id
    waypoint.latitudeI = 10
    waypoint.longitudeI = 20
    waypoint.lockedTo = UInt32(bitPattern: sender)
    waypoint.expire = expire
    waypoint.name = "Camp"
    var control = Meshchat_MeshChatControl()
    control.pin = waypoint
    var packet = MeshPacket()
    packet.from = UInt32(bitPattern: sender)
    packet.channel = 1
    return OpenedInRoom(
        packet: packet,
        roomId: 42,
        sealedUnderCurrent: true,
        onItsSlot: onItsSlot,
        control: control
    )
}

private func d4TracerouteReply(
    from node: Int32,
    route: [UInt32] = [],
    snr: [Int32] = [],
    back: [UInt32] = [],
    snrBack: [Int32] = []
) -> FromRadio {
    var discovery = RouteDiscovery()
    discovery.route = route
    discovery.snrTowards = snr
    discovery.routeBack = back
    discovery.snrBack = snrBack
    var data = DataMessage()
    data.portnum = .tracerouteApp
    data.payload = (try? discovery.serializedData()) ?? Data()
    var packet = MeshPacket()
    packet.from = UInt32(bitPattern: node)
    packet.decoded = data
    return fromRadio(packet: packet)
}
