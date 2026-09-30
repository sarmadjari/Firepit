import FirepitData
import FirepitModel
import FirepitProtocol
import FirepitProtos
import FirepitTransport
import Foundation
import SwiftProtobuf
import Testing

@testable import Firepit

@MainActor
@Suite("Settings")
struct SettingsTests {
    private func defaults(_ name: String = UUID().uuidString) -> UserDefaults {
        let suite = "settings-tests-\(name)"
        guard let defaults = UserDefaults(suiteName: suite) else { preconditionFailure("suite defaults unavailable") }
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }

    private func store(
        defaults: UserDefaults? = nil,
        daos: FirepitDaos? = nil,
        myNode: Int32? = 99,
        rooms: [RoomChannel] = [],
        left: [Int32] = [],
        tiles: Bool = false,
        sweepEvery: Duration = .seconds(6 * 60 * 60)
    ) throws -> (RetentionStore, FirepitDaos, Box) {
        let daos = try daos ?? FirepitDaos.inMemory()
        let box = Box(left: left, tiles: tiles)
        let store = RetentionStore(
            defaults: defaults ?? self.defaults(),
            messageDao: daos.messageDao,
            nodeDao: daos.nodeDao,
            pinDao: daos.mapPinDao,
            personCardDao: daos.personCardDao,
            peerKeyDao: daos.peerKeyDao,
            roomActivity: daos.roomActivityDao,
            myNodeNum: { myNode },
            heldRooms: { rooms },
            leaveRoom: { roomId in box.left.append(roomId) },
            forgetBrowsedTiles: {
                box.tileCalls += 1
                return box.tiles
            },
            sweepEvery: sweepEvery
        )
        return (store, daos, box)
    }

    @Test func retentionDefaultsMatchAndroid() throws {
        let (store, _, _) = try store()

        #expect(store.choice == .week)
        #expect(store.roomLifetime == .forever)
    }

    @Test func retentionChoicePersistsAndReloads() throws {
        let defaults = defaults()
        let (store, daos, _) = try store(defaults: defaults)

        store.choose(.day)
        let (reloaded, _, _) = try self.store(defaults: defaults, daos: daos)

        #expect(reloaded.choice == .day)
    }

    @Test func roomLifetimePersistsAndReloads() throws {
        let defaults = defaults()
        let (store, daos, _) = try store(defaults: defaults)

        store.chooseRoomLifetime(.quarter)
        let (reloaded, _, _) = try self.store(defaults: defaults, daos: daos)

        #expect(reloaded.roomLifetime == .quarter)
    }

    @Test func sweepDeletesOnlyMessagesOlderThanWindow() async throws {
        let defaults = defaults()
        defaults.set(MessageRetention.day.name, forKey: "retention")
        let (store, daos, _) = try store(defaults: defaults)
        try await daos.messageDao.save(message: message(id: 1, sentAt: 1_000), myNodeNum: 99)
        try await daos.messageDao.save(message: message(id: 2, sentAt: 90_000_000), myNodeNum: 99)

        let result = try await store.sweep(nowMillis: 100_000_000)

        #expect(result.messages == 1)
        #expect(try await daos.messageDao.find(id: 1) == nil)
        #expect(try await daos.messageDao.find(id: 2) != nil)
    }

    @Test func sweepForgetsOnlyPositionsOlderThanWindow() async throws {
        let defaults = defaults()
        defaults.set(MessageRetention.day.name, forKey: "retention")
        let (store, daos, _) = try store(defaults: defaults)
        try await daos.nodeDao.save(node: node(1, positionTime: 1_000), now: 1_000)
        try await daos.nodeDao.save(node: node(2, positionTime: 90_000_000), now: 90_000_000)

        let result = try await store.sweep(nowMillis: 100_000_000)

        #expect(result.positions == 1)
        #expect(try await daos.nodeDao.find(nodeNum: 1)?.latitudeI == nil)
        #expect(try await daos.nodeDao.find(nodeNum: 2)?.latitudeI == 2)
    }

    @Test func sweepForgetsOnlyOldStrangersAndKeepsMyNode() async throws {
        let defaults = defaults()
        defaults.set(MessageRetention.day.name, forKey: "retention")
        let (store, daos, _) = try store(defaults: defaults, myNode: 99)
        try await daos.nodeDao.save(node: MeshNode(nodeNum: 3, lastHeard: 1_000), now: 1_000)
        try await daos.nodeDao.save(node: MeshNode(nodeNum: 4, lastHeard: 90_000_000), now: 90_000_000)
        try await daos.nodeDao.save(node: MeshNode(nodeNum: 99, lastHeard: 1_000), now: 1_000)

        let result = try await store.sweep(nowMillis: 100_000_000)

        #expect(result.strangers == 1)
        #expect(try await daos.nodeDao.find(nodeNum: 3) == nil)
        #expect(try await daos.nodeDao.find(nodeNum: 4) != nil)
        #expect(try await daos.nodeDao.find(nodeNum: 99) != nil)
    }

    @Test func sweepDeletesExpiredPinsOnly() async throws {
        let (store, daos, _) = try store()
        try await daos.mapPinDao.save(pin: pin(id: 1, expire: 1))
        try await daos.mapPinDao.save(pin: pin(id: 2, expire: 200))
        try await daos.mapPinDao.save(pin: pin(id: 3, expire: 0))

        let result = try await store.sweep(nowMillis: 100_000)

        #expect(result.pins == 1)
        #expect(try await daos.mapPinDao.find(id: 1) == nil)
        #expect(try await daos.mapPinDao.find(id: 2) != nil)
        #expect(try await daos.mapPinDao.find(id: 3) != nil)
    }

    @Test func sweepDeletesOnlyOldPinTombstones() async throws {
        let now: Int64 = 100 * 24 * 60 * 60 * 1_000
        let (store, daos, _) = try store()
        try await daos.mapPinDao.remember(deleted: DeletedPinEntity(id: 1, channel: 1, deletedAt: 1_000))
        try await daos.mapPinDao.remember(deleted: DeletedPinEntity(id: 2, channel: 1, deletedAt: now))

        let result = try await store.sweep(nowMillis: now)

        #expect(result.tombstones == 1)
        #expect(!(try await daos.mapPinDao.wasDeleted(id: 1)))
        #expect(try await daos.mapPinDao.wasDeleted(id: 2))
    }

    @Test func sweepForgetsCardsAndKeysOutsideRoomsOnly() async throws {
        let (store, daos, _) = try store()
        try await daos.roomMemberDao.record(roomId: 1, nodeNum: 10, now: 1)
        try await daos.personCardDao.upsert(card: card(10))
        try await daos.personCardDao.upsert(card: card(11))
        try await daos.peerKeyDao.upsert(key: PeerKeyEntity(nodeNum: 10, phoneKey: "kept", learnedAt: 1))
        try await daos.peerKeyDao.upsert(key: PeerKeyEntity(nodeNum: 11, phoneKey: "gone", learnedAt: 1))

        let result = try await store.sweep(nowMillis: 100_000_000)
        let cards = try await firstValue(daos.personCardDao.observeAll())

        #expect(result.cards == 1)
        #expect(result.keys == 1)
        #expect(cards[10]?.name == "Person 10")
        #expect(cards[11] == nil)
        #expect(try await daos.peerKeyDao.find(nodeNum: 10) != nil)
        #expect(try await daos.peerKeyDao.find(nodeNum: 11) == nil)
    }

    @Test func browsedTilesAreClearedOnlyOncePerRetentionWindow() async throws {
        let defaults = defaults()
        defaults.set(MessageRetention.day.name, forKey: "retention")
        let (store, _, box) = try store(defaults: defaults, tiles: true)

        let first = try await store.sweep(nowMillis: 100_000_000)
        let second = try await store.sweep(nowMillis: 100_000_100)

        #expect(first.browsedTiles)
        #expect(!second.browsedTiles)
        #expect(box.tileCalls == 1)
    }

    @Test func sweepLeavesSilentRoomsAndStartsUnknownRoomsNow() async throws {
        let old: Int64 = 1
        let now: Int64 = 100 * 24 * 60 * 60 * 1_000
        let quiet = RoomChannel(
            index: 1, name: "Quiet", role: .secondary, id: 10, positionPrecision: 32, kind: .firepit)
        let active = RoomChannel(
            index: 2, name: "Active", role: .secondary, id: 11, positionPrecision: 32, kind: .firepit)
        let unknown = RoomChannel(
            index: 3, name: "New", role: .secondary, id: 12, positionPrecision: 32, kind: .firepit)
        let defaults = defaults()
        defaults.set(RoomLifetime.month.name, forKey: "room_lifetime")
        let (store, daos, box) = try store(defaults: defaults, rooms: [quiet, active, unknown])
        try await daos.roomActivityDao.joined(roomId: 10, now: old)
        try await daos.roomActivityDao.joined(roomId: 11, now: now)

        let result = try await store.sweep(nowMillis: now)
        let activity = try await daos.roomActivityDao.all()

        #expect(result.rooms == [10])
        #expect(box.left == [10])
        #expect(activity.contains { $0.roomId == 12 && $0.joinedAt == now })
    }

    @Test func startRunsPeriodicSweep() async throws {
        let defaults = defaults()
        defaults.set(MessageRetention.day.name, forKey: "retention")
        let (store, daos, _) = try store(defaults: defaults, sweepEvery: .milliseconds(10))
        try await daos.messageDao.save(message: message(id: 1, sentAt: 1), myNodeNum: 99)

        store.start()
        let deleted = await eventually { try await daos.messageDao.find(id: 1) == nil }

        #expect(deleted)
    }

    @Test func viewModelDerivesNodeSummaries() {
        #expect(SettingsViewModel.nodeSummary(count: 0) == "Nobody heard yet")
        #expect(SettingsViewModel.nodeSummary(count: 3) == "3 heard on the mesh")
    }

    @Test func viewModelDerivesDeviceSummaries() {
        #expect(SettingsViewModel.deviceSummary(.disconnected) == "Not connected")
        #expect(SettingsViewModel.deviceSummary(.connecting(attempt: 1)) == "Connecting…")
    }

    @Test func viewModelDerivesMessageAlertsFromSnapshot() {
        var external = ModuleConfig.ExternalNotificationConfig()
        external.enabled = true
        external.alertMessage = true
        let snapshot = RadioSnapshot(moduleConfigs: [.with { $0.externalNotification = external }])

        #expect(SettingsViewModel.messageAlerts(snapshot: nil) == .phoneOnly)
        #expect(SettingsViewModel.messageAlerts(snapshot: snapshot) == .phoneAndNode)
    }

    @Test func personValidationRefusesEmptyNames() throws {
        let app = try AppContainer(configuration: .demo)

        #expect(throws: PersonStore.PersonError.emptyName) {
            try app.people.save(name: "   ", tag: "")
        }
    }

    @Test func personSaveDerivesTagFromInitials() throws {
        let app = try AppContainer(configuration: .demo)

        try app.people.save(name: "Maya Chen", tag: "")

        #expect(app.people.person?.name == "Maya Chen")
        #expect(app.people.person?.tag == "MC")
    }

    @Test func colourChoicePersists() throws {
        let app = try AppContainer(configuration: .demo)
        try app.people.save(name: "Maya Chen", tag: "")

        app.people.chooseColour(IdentityColors.choices[2])

        #expect(app.people.person?.colourSlot == IdentityColors.choices[2])
    }

    private func message(id: Int32, sentAt: Int64) -> ChatMessage {
        ChatMessage(
            id: id,
            channel: 1,
            fromNodeNum: 10,
            toNodeNum: broadcastNodeNum,
            text: "message \(id)",
            sentAt: sentAt,
            status: .received,
            isOutgoing: false
        )
    }

    private func node(_ id: Int32, positionTime: Int64) -> MeshNode {
        MeshNode(
            nodeNum: id,
            lastHeard: positionTime,
            latitudeI: id,
            longitudeI: id,
            positionTime: positionTime,
            positionPrecision: 32
        )
    }

    private func pin(id: Int32, expire: Int64) -> MapPin {
        MapPin(
            id: id,
            channel: 1,
            latitudeI: 1,
            longitudeI: 1,
            name: "Pin \(id)",
            expire: expire,
            createdBy: 99,
            receivedAt: 1
        )
    }

    private func card(_ nodeNum: Int32) -> PersonCardEntity {
        PersonCardEntity(nodeNum: nodeNum, name: "Person \(nodeNum)", tag: "P", colourSlot: nil, updatedAt: 1)
    }

    private func firstValue<T: Sendable>(_ stream: AsyncStream<T>) async throws -> T {
        var iterator = stream.makeAsyncIterator()
        guard let value = await iterator.next() else { throw TestError.noValue }
        return value
    }

    private func eventually(_ condition: () async throws -> Bool) async -> Bool {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(2))
        while clock.now < deadline {
            if (try? await condition()) == true { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return (try? await condition()) == true
    }
}

@MainActor
private final class Box {
    var left: [Int32]
    var tiles: Bool
    var tileCalls = 0

    init(left: [Int32], tiles: Bool) {
        self.left = left
        self.tiles = tiles
    }
}

private enum TestError: Error {
    case noValue
}

/// The retention store is the app's one instance, and only ever leaves rooms that are Firepit's to leave.
@Suite("Retention wiring")
@MainActor
struct RetentionWiringTests {
    @Test func settingsChangesReachTheAppWideSweep() throws {
        let app = try AppContainer(configuration: .demo)
        let model = SettingsViewModel(app: app)
        model.chooseRetention(.day)
        model.chooseRoomLifetime(.quarter)
        // The periodic sweep runs on app.retention; a separate copy here would leave it on the old window.
        #expect(app.retention.choice == .day)
        #expect(app.retention.roomLifetime == .quarter)
        #expect(model.state.retention == .day)
    }

    @Test func onlyFirepitRoomsCanAgeOut() {
        func slot(_ index: Int, _ kind: RoomKind, id: Int32) -> RoomChannel {
            RoomChannel(index: index, name: "R\(index)", role: .secondary, id: id, positionPrecision: 32, kind: kind)
        }
        let channels = [
            RoomChannel(index: 0, name: "", role: .primary, id: 0, positionPrecision: 0, kind: .meshtasticPublic),
            slot(1, .firepit, id: 11),
            slot(2, .meshtasticPrivate, id: 12),
            slot(3, .meshtasticPublic, id: 13),
            slot(4, .firepitKeyMissing, id: 14),
            slot(5, .firepitMovedOn, id: 15),
            slot(6, .unencrypted, id: 16),
            slot(7, .firepit, id: 0),
        ]
        #expect(RetentionStore.heldRooms(channels: channels).map(\.id) == [11, 14, 15])
    }
}
