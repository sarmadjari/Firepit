import FirepitData
import FirepitModel
import FirepitProtocol
import Foundation
import Observation
import os

private nonisolated let retentionLog = Logger(subsystem: "com.getfirepit.app", category: "FirepitRetention")

/// How long this phone keeps what it learns.
///
/// Messages are the obvious part, but not the only one: where everybody was, who they said they were, their keys,
/// the pins, and the map tiles looked at are each a record of the trip. All of it ages out with the same window, and
/// none of it outlives the rooms it came from.
///
/// Only this phone. Everyone else holds their own copy and their own setting, and nothing on a mesh can reach across
/// to delete theirs.
@MainActor
@Observable
final class RetentionStore {
    private(set) var choice: MessageRetention
    private(set) var roomLifetime: RoomLifetime

    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let messageDao: MessageDao
    @ObservationIgnored private let nodeDao: NodeDao
    @ObservationIgnored private let pinDao: MapPinDao
    @ObservationIgnored private let personCardDao: PersonCardDao
    @ObservationIgnored private let peerKeyDao: PeerKeyDao
    @ObservationIgnored private let roomActivity: RoomActivityDao
    @ObservationIgnored private let myNodeNum: @MainActor () -> Int32?
    @ObservationIgnored private let heldRooms: @MainActor () -> [RoomChannel]
    @ObservationIgnored private let leaveRoom: @MainActor (Int32) async throws -> Void
    @ObservationIgnored private let forgetBrowsedTiles: @MainActor () async -> Bool
    @ObservationIgnored private let sweepEvery: Duration
    @ObservationIgnored private var sweepTask: Task<Void, Never>?

    init(
        defaults: UserDefaults = .standard,
        messageDao: MessageDao,
        nodeDao: NodeDao,
        pinDao: MapPinDao,
        personCardDao: PersonCardDao,
        peerKeyDao: PeerKeyDao,
        roomActivity: RoomActivityDao,
        myNodeNum: @escaping @MainActor () -> Int32?,
        heldRooms: @escaping @MainActor () -> [RoomChannel],
        leaveRoom: @escaping @MainActor (Int32) async throws -> Void,
        forgetBrowsedTiles: @escaping @MainActor () async -> Bool,
        sweepEvery: Duration = .seconds(6 * 60 * 60)
    ) {
        self.defaults = defaults
        self.messageDao = messageDao
        self.nodeDao = nodeDao
        self.pinDao = pinDao
        self.personCardDao = personCardDao
        self.peerKeyDao = peerKeyDao
        self.roomActivity = roomActivity
        self.myNodeNum = myNodeNum
        self.heldRooms = heldRooms
        self.leaveRoom = leaveRoom
        self.forgetBrowsedTiles = forgetBrowsedTiles
        self.sweepEvery = sweepEvery
        choice = MessageRetention.named(name: defaults.string(forKey: Self.key))
        roomLifetime = RoomLifetime.named(name: defaults.string(forKey: Self.roomKey))
    }

    /// The app's one store. There must only ever be one: each reads the choice once, so a second would keep sweeping
    /// with whatever window was chosen when it was made.
    convenience init(
        defaults: UserDefaults,
        daos: FirepitDaos,
        mesh: MeshRepository,
        rooms: RoomRepository,
        offlineMaps: OfflineMapRepository
    ) {
        self.init(
            defaults: defaults,
            messageDao: daos.messageDao,
            nodeDao: daos.nodeDao,
            pinDao: daos.mapPinDao,
            personCardDao: daos.personCardDao,
            peerKeyDao: daos.peerKeyDao,
            roomActivity: daos.roomActivityDao,
            myNodeNum: { mesh.myNodeNum.value },
            heldRooms: { Self.heldRooms(channels: mesh.channels.value) },
            leaveRoom: { roomId in try await rooms.leaveRoom(roomId: roomId, announce: false) },
            forgetBrowsedTiles: { await offlineMaps.forgetBrowsedTiles() }
        )
    }

    /// The rooms a lifetime can end: Firepit's own, including ones this phone can no longer seal for. A Meshtastic
    /// channel is somebody else's to keep or leave.
    nonisolated static func heldRooms(channels: [RoomChannel]) -> [RoomChannel] {
        ChannelSlotManager.rooms(channels: channels).filter { room in
            room.id != 0 && (room.kind == .firepit || room.kind.isStalledRoom)
        }
    }

    deinit {
        sweepTask?.cancel()
    }

    /// Sweeps now and then every few hours for as long as the app runs. The radio keeps the app alive in the
    /// background for days, and a sweep that only ran when a screen opened would never catch up with that.
    func start() {
        guard sweepTask == nil else { return }
        sweepTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                do {
                    try await self.sweep()
                } catch {
                    retentionLog.warning("retention sweep failed: \(error.localizedDescription, privacy: .public)")
                }
                try? await Task.sleep(for: self.sweepEvery)
            }
        }
    }

    func chooseRoomLifetime(_ lifetime: RoomLifetime) {
        defaults.set(lifetime.name, forKey: Self.roomKey)
        roomLifetime = lifetime
        Task { try? await sweep() }
    }

    func choose(_ retention: MessageRetention) {
        defaults.set(retention.name, forKey: Self.key)
        choice = retention
        Task { try? await sweep() }
    }

    @discardableResult
    func sweep(nowMillis: Int64 = currentEpochMillis()) async throws -> RetentionSweepResult {
        let cutoff = choice.cutoff(nowMillis: nowMillis)
        let messages = try await messageDao.deleteOlderThan(cutoff: cutoff)
        let positions = try await nodeDao.forgetPositionsBefore(cutoff: cutoff)
        let strangers: Int
        if let mine = myNodeNum() {
            strangers = try await nodeDao.forgetStrangersBefore(cutoff: cutoff, keep: mine)
        } else {
            strangers = 0
        }
        let pins = try await pinDao.deleteExpired(nowSeconds: nowMillis / 1_000)
        let tombstones = try await pinDao.forgetDeletedBefore(cutoff: nowMillis - Self.tombstoneLifetimeMillis)
        let cards = try await personCardDao.forgetOutsideRooms()
        let keys = try await peerKeyDao.forgetOutsideRooms()
        let tiles = await maybeForgetBrowsedTiles(nowMillis: nowMillis, cutoff: cutoff)
        let rooms = await forgetSilentRooms(nowMillis: nowMillis)
        return RetentionSweepResult(
            messages: messages,
            positions: positions,
            strangers: strangers,
            pins: pins,
            tombstones: tombstones,
            cards: cards,
            keys: keys,
            browsedTiles: tiles,
            rooms: rooms
        )
    }

    /// Everything this phone has kept about what was said and where anyone was, gone at once. Rooms and their keys
    /// stay: leaving a room is its own decision, and takes them with it.
    func eraseHistory() {
        Task {
            do {
                try await messageDao.deleteAll()
                try await pinDao.deleteAll()
                try await pinDao.forgetAllDeleted()
                try await nodeDao.forgetAllPositions()
                try await personCardDao.deleteAll()
                _ = await forgetBrowsedTiles()
                retentionLog.info("erased this phone's history")
            } catch {
                retentionLog.warning("could not erase history: \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    private func maybeForgetBrowsedTiles(nowMillis: Int64, cutoff: Int64) async -> Bool {
        let window = nowMillis - cutoff
        let sinceCleared = nowMillis - Int64(defaults.double(forKey: Self.keyTilesCleared))
        guard sinceCleared >= window else { return false }
        let cleared = await forgetBrowsedTiles()
        if cleared {
            defaults.set(Double(nowMillis), forKey: Self.keyTilesCleared)
        }
        return cleared
    }

    /// Leaves rooms nobody has spoken in for longer than the chosen lifetime.
    ///
    /// Judged on when each room last had anything said, pinned or shared in it, which is recorded as it happens: the
    /// messages themselves may already be gone to the retention window, which is never longer than a lifetime. A room
    /// with no record yet — held from before this was kept — starts its clock now rather than being judged on nothing.
    private func forgetSilentRooms(nowMillis: Int64) async -> [Int32] {
        guard roomLifetime.silence != nil else { return [] }
        let held = heldRooms()
        guard !held.isEmpty else { return [] }
        do {
            let known = try await roomActivity.all().reduce(into: [Int32: RoomActivityEntity]()) { result, activity in
                result[activity.roomId] = activity
            }
            for room in held where known[room.id] == nil {
                try await roomActivity.joined(roomId: room.id, now: nowMillis)
            }
            let lastActivity = held.reduce(into: [Int32: Int64]()) { result, room in
                if let activity = known[room.id] {
                    result[room.id] = max(activity.joinedAt, activity.lastActivityAt)
                } else {
                    result[room.id] = nowMillis
                }
            }
            let silent = RoomLifetime.silentRooms(
                lastActivity: lastActivity,
                lifetime: roomLifetime,
                nowMillis: nowMillis
            )
            for roomId in silent {
                do {
                    try await leaveRoom(roomId)
                } catch {
                    retentionLog.warning("could not leave silent room \(roomId): \(error.localizedDescription)")
                }
            }
            return silent
        } catch {
            retentionLog.warning("could not check silent rooms: \(error.localizedDescription, privacy: .public)")
            return []
        }
    }

    private static let key = "retention"
    private static let roomKey = "room_lifetime"
    private static let keyTilesCleared = "tiles_cleared_at"
    private static let tombstoneLifetimeMillis: Int64 = 30 * 24 * 60 * 60 * 1_000
}

struct RetentionSweepResult: Equatable {
    var messages: Int
    var positions: Int
    var strangers: Int
    var pins: Int
    var tombstones: Int
    var cards: Int
    var keys: Int
    var browsedTiles: Bool
    var rooms: [Int32]
}
