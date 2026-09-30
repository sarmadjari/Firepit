import FirepitModel
import FirepitProtocol
import Foundation
import os

/// Keeps each room's history, pins, read position and mute with the room,
/// whichever radio carries it.
///
/// A slot number is only a place on one radio. Another radio can carry another
/// room in the same slot, and the screen shows history by slot, so without this
/// one room's messages would appear under another's name. Every stored message
/// and pin records its room, and whenever a radio reports its channels each
/// room's history is moved to the slot the room is in now. History of a room
/// this radio does not carry is set aside until one that does connects, and a
/// channel with no id of its own — a Meshtastic one — keeps its history per
/// slot, set aside while a room holds that slot.
public final class RoomHistory: Sendable {
    public let mesh: MeshRepository
    public let messageDao: MessageDao
    public let pinDao: MapPinDao
    public let channelState: ChannelStateDao
    public let roomActivity: RoomActivityDao
    public let sessionStore: SessionStore

    /**
     * Held while slots are being rewritten, so history is never placed against
     * a half-finished layout: leaving a room writes several slots in turn, and
     * each write is visible at once.
     */
    private let rearranging = AsyncMutex()
    private let tasks = Mutex<[Task<Void, Never>]>([])
    private let log = Logger(subsystem: "com.getfirepit.app", category: "FirepitHistory")

    public init(
        mesh: MeshRepository,
        messageDao: MessageDao,
        pinDao: MapPinDao,
        channelState: ChannelStateDao,
        roomActivity: RoomActivityDao,
        sessionStore: SessionStore
    ) {
        self.mesh = mesh
        self.messageDao = messageDao
        self.pinDao = pinDao
        self.channelState = channelState
        self.roomActivity = roomActivity
        self.sessionStore = sessionStore
    }

    deinit {
        tasks.withLock { jobs in
            for job in jobs {
                job.cancel()
            }
            jobs.removeAll()
        }
    }

    /// Runs `block` — a sequence of slot writes — with placement held off until it is done.
    public func whileRearranging<T: Sendable>(_ block: @Sendable () async throws -> T) async throws -> T {
        try await rearranging.withLock(block)
    }

    public func start() {
        tasks.withLock { jobs in
            guard jobs.isEmpty else {
                return
            }
            jobs.append(
                Task { [weak self] in
                    // No layout seen yet, as with Kotlin's distinctUntilChanged: the first one always places.
                    var last: [Int: Int32]?
                    guard let self else {
                        return
                    }
                    for await channels in self.mesh.channels.subscribe() {
                        if channels.isEmpty {
                            continue
                        }
                        let next = Dictionary(
                            uniqueKeysWithValues: ChannelSlotManager.rooms(channels: channels).map { ($0.index, $0.id) }
                        )
                        if next == last {
                            continue
                        }
                        last = next
                        do {
                            try await self.rearranging.withLock {
                                try await self.place()
                            }
                        } catch {
                            self.log.warning("could not place history")
                        }
                    }
                })
        }
    }

    /// Mutes or unmutes the conversation in `channel`, remembering it against its room too.
    public func setMuted(channel: Int, muted: Bool) async throws {
        let roomId = roomIn(channel: channel)
        try await channelState.setMuted(channel: channel, muted: muted, roomId: roomId)
        if roomId != 0 {
            try await roomActivity.setMuted(roomId: roomId, muted: muted, now: currentEpochMillis())
        }
    }

    /// Which room a slot holds now, or 0 for a channel with no id of its own.
    public func roomIn(channel: Int) -> Int32 {
        ChannelSlotManager.rooms(channels: mesh.channels.value).first { $0.index == channel }?.id ?? 0
    }

    private func place() async throws {
        let rooms = ChannelSlotManager.rooms(channels: mesh.channels.value)
        let now = currentEpochMillis()
        // Only once there are rooms to file under: a radio with none says
        // nothing about where older history belongs.
        let identified = rooms.filter { $0.id != 0 }
        if !sessionStore.historyFiledByRoom && !identified.isEmpty {
            for room in identified {
                try await messageDao.stampSlot(slot: room.index, roomId: room.id)
                try await pinDao.stampSlot(slot: room.index, roomId: room.id)
                if let state = try await channelState.find(channel: room.index) {
                    try await channelState.stampSlot(slot: room.index, roomId: room.id)
                    if state.muted {
                        try await roomActivity.setMuted(roomId: room.id, muted: true, now: now)
                    }
                }
            }
            sessionStore.historyFiledByRoom = true
        }

        for room in identified {
            try await messageDao.placeRoom(roomId: room.id, slot: room.index)
            try await pinDao.placeRoom(roomId: room.id, slot: room.index)
        }
        for slot in ChannelSlotManager.firstRoomSlot...ChannelSlotManager.lastRoomSlot {
            let roomId = rooms.first { $0.index == slot }?.id ?? 0
            try await messageDao.parkOtherRooms(slot: slot, keep: roomId)
            try await pinDao.parkOtherRooms(slot: slot, keep: roomId)
            if roomId != 0 {
                try await messageDao.parkUnfiled(slot: slot)
                try await pinDao.parkUnfiled(slot: slot)
            } else {
                try await messageDao.restoreUnfiled(slot: slot)
                try await pinDao.restoreUnfiled(slot: slot)
            }
            let muted: Bool
            if roomId != 0 {
                muted = try await roomActivity.isMuted(roomId: roomId)
            } else {
                muted = false
            }
            try await channelState.followRoom(channel: slot, roomId: roomId, now: now, roomMuted: muted)
        }
    }
}
