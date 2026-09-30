import FirepitModel
import Foundation
import Testing

@testable import FirepitData

private func room(_ index: Int, _ id: Int32) -> RoomChannel {
    RoomChannel(index: index, name: "Room \(id)", role: .secondary, id: id, positionPrecision: 32, kind: .firepit)
}

private func pin(_ id: Int32, channel: Int, roomId: Int32 = 0) -> MapPin {
    MapPin(
        id: id, channel: channel, latitudeI: 1, longitudeI: 2, name: "p", createdBy: 1, receivedAt: 1,
        roomId: roomId)
}

@Test func roomHistoryFilesExistingMessagesUnderRoomOnStart() async throws {
    let h = try Harness()
    try await h.messageDao.save(message: sampleMessage(id: 1, channel: 1, roomId: 0), myNodeNum: 111)
    h.mesh.channels.set([room(1, 42)])
    let history = RoomHistory(
        mesh: h.mesh, messageDao: h.messageDao, pinDao: MapPinDao(h.db),
        channelState: ChannelStateDao(h.db), roomActivity: h.roomActivity,
        sessionStore: SessionStore(defaults: UserDefaults(suiteName: UUID().uuidString)!))
    history.start()
    #expect(try await eventually { try await h.messageDao.find(id: 1)?.roomId == 42 })
}

@Test func roomHistoryFilesExistingPinsUnderRoomOnStart() async throws {
    let h = try Harness()
    let pinDao = MapPinDao(h.db)
    try await pinDao.save(pin: pin(9, channel: 1))
    h.mesh.channels.set([room(1, 42)])
    let history = RoomHistory(
        mesh: h.mesh, messageDao: h.messageDao, pinDao: pinDao,
        channelState: ChannelStateDao(h.db), roomActivity: h.roomActivity,
        sessionStore: SessionStore(defaults: UserDefaults(suiteName: UUID().uuidString)!))
    history.start()
    #expect(try await eventually { try await pinDao.find(id: 9)?.roomId == 42 })
}

@Test func roomHistoryMovesRoomMessagesWhenSlotChanges() async throws {
    let h = try Harness()
    try await h.messageDao.save(message: sampleMessage(id: 2, channel: 1, roomId: 42), myNodeNum: 111)
    h.mesh.channels.set([room(2, 42)])
    let history = RoomHistory(
        mesh: h.mesh, messageDao: h.messageDao, pinDao: MapPinDao(h.db),
        channelState: ChannelStateDao(h.db), roomActivity: h.roomActivity,
        sessionStore: SessionStore(defaults: UserDefaults(suiteName: UUID().uuidString)!))
    history.start()
    #expect(try await eventually { try await h.messageDao.find(id: 2)?.channel == 2 })
}

@Test func roomHistoryParksUnfiledSlotHistoryWhileRoomOccupiesSlot() async throws {
    let h = try Harness()
    try await h.messageDao.save(message: sampleMessage(id: 3, channel: 1, roomId: 0), myNodeNum: 111)
    let store = SessionStore(defaults: UserDefaults(suiteName: UUID().uuidString)!)
    store.historyFiledByRoom = true
    h.mesh.channels.set([room(1, 42)])
    let history = RoomHistory(
        mesh: h.mesh, messageDao: h.messageDao, pinDao: MapPinDao(h.db),
        channelState: ChannelStateDao(h.db), roomActivity: h.roomActivity,
        sessionStore: store)
    history.start()
    #expect(try await eventually { try await h.messageDao.find(id: 3)?.channel == unfiledParkingFor(slot: 1) })
}

@Test func roomHistorySetMutedStoresChannelAndRoomMute() async throws {
    let h = try Harness()
    let stateDao = ChannelStateDao(h.db)
    h.mesh.channels.set([room(1, 42)])
    let history = RoomHistory(
        mesh: h.mesh, messageDao: h.messageDao, pinDao: MapPinDao(h.db),
        channelState: stateDao, roomActivity: h.roomActivity,
        sessionStore: SessionStore(defaults: UserDefaults(suiteName: UUID().uuidString)!))
    try await history.setMuted(channel: 1, muted: true)
    #expect(try await stateDao.find(channel: 1)?.muted == true)
    #expect(try await h.roomActivity.isMuted(roomId: 42))
}

@Test func roomHistoryRoomInReturnsZeroForNonRoomSlot() throws {
    let h = try Harness()
    h.mesh.channels.set([room(2, 42)])
    let history = RoomHistory(
        mesh: h.mesh, messageDao: h.messageDao, pinDao: MapPinDao(h.db),
        channelState: ChannelStateDao(h.db), roomActivity: h.roomActivity,
        sessionStore: SessionStore(defaults: UserDefaults(suiteName: UUID().uuidString)!))
    #expect(history.roomIn(channel: 1) == 0)
    #expect(history.roomIn(channel: 2) == 42)
}

@Test func roomHistoryWhileRearrangingExcludesConcurrentRearrangements() async throws {
    let h = try Harness()
    let history = RoomHistory(
        mesh: h.mesh, messageDao: h.messageDao, pinDao: MapPinDao(h.db),
        channelState: ChannelStateDao(h.db), roomActivity: h.roomActivity,
        sessionStore: SessionStore(defaults: UserDefaults(suiteName: UUID().uuidString)!))
    let order = Mutex<[Int]>([])
    async let first: Void = history.whileRearranging {
        order.withLock { $0.append(1) }
        try await Task.sleep(for: .milliseconds(50))
        order.withLock { $0.append(2) }
    }
    try await Task.sleep(for: .milliseconds(10))
    async let second: Void = history.whileRearranging {
        order.withLock { $0.append(3) }
    }
    _ = try await (first, second)
    #expect(order.withLock { $0 } == [1, 2, 3])
}
