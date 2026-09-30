import FirepitCrypto
import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation
import os

/// Pins on the map, sealed under the room's key like its words.
///
/// A pin says where somebody's tent is, or where to meet, so it is as private as
/// anything typed: the channel key would leave it readable by whoever holds a
/// member's radio, and would let them plant or move pins under any name. Sealed,
/// only members see a pin and only a member can change one. The lock then keeps
/// it to its owner. The cost is that stock Meshtastic apps no longer see pins.
///
/// There is no delete. Removing a pin means sending it again with an expiry in
/// the past, which every member then drops.
public final class WaypointRepository: Sendable {
    public let mesh: MeshRepository
    public let rooms: RoomRepository
    public let pinDao: MapPinDao

    private let tasks = Mutex<[Task<Void, Never>]>([])
    private let randomId: @Sendable () -> Int32
    private let nowMillis: @Sendable () -> Int64
    private let sleep: @Sendable (Duration) async -> Void
    private let log = Logger(subsystem: "com.getfirepit.app", category: "FirepitPins")

    public init(
        mesh: MeshRepository,
        rooms: RoomRepository,
        pinDao: MapPinDao,
        randomId: @escaping @Sendable () -> Int32 = defaultPinId,
        nowMillis: @escaping @Sendable () -> Int64 = currentEpochMillis,
        sleep: @escaping @Sendable (Duration) async -> Void = { duration in
            try? await Task.sleep(for: duration)
        }
    ) {
        self.mesh = mesh
        self.rooms = rooms
        self.pinDao = pinDao
        self.randomId = randomId
        self.nowMillis = nowMillis
        self.sleep = sleep
    }

    deinit {
        tasks.withLock { jobs in
            for job in jobs {
                job.cancel()
            }
            jobs.removeAll()
        }
    }

    public func observePins() -> AsyncStream<[MapPin]> {
        pinDao.observeLive(nowMillis: nowMillis())
    }

    public func start() {
        tasks.withLock { jobs in
            guard jobs.isEmpty else {
                return
            }
            jobs.append(
                Task { [weak self] in
                    guard let self else { return }
                    for await opened in rooms.openedInRooms.subscribe() {
                        guard case .pin = opened.control.payload else {
                            continue
                        }
                        await handlePin(opened: opened, waypoint: opened.control.pin)
                    }
                })
        }
    }

    public func drop(
        channel: Int,
        latitudeI: Int32,
        longitudeI: Int32,
        name: String,
        description: String = ""
    ) async throws {
        guard let myNodeNum = mesh.myNodeNum.value else {
            throw WaypointRepositoryError.notConnected
        }
        let roomId = try roomOf(channel: channel)
        let pin = MapPin(
            id: randomId(),
            channel: channel,
            latitudeI: latitudeI,
            longitudeI: longitudeI,
            name: Self.limitName(name),
            description: Self.limitDescription(description),
            lockedTo: myNodeNum,
            createdBy: myNodeNum,
            receivedAt: nowMillis(),
            roomId: roomId
        )
        try await send(waypoint: pin.toWaypoint(), roomId: roomId)
        try await pinDao.save(pin: pin)
        await rooms.noteActivity(roomId: roomId)
        log.info("dropped pin")
    }

    /**
     * Renames a pin everywhere by sending it again under the same id, which
     * every member treats as an edit.
     */
    public func rename(pin: MapPin, name: String) async throws {
        let myNodeNum = mesh.myNodeNum.value
        guard pin.canEdit(myNodeNum: myNodeNum) else {
            throw WaypointRepositoryError.locked
        }
        var renamed = pin
        renamed.name = Self.limitName(name)
        try await send(waypoint: renamed.toWaypoint(), roomId: try roomOf(pin: pin))
        try await pinDao.save(pin: renamed)
        log.info("renamed pin")
    }

    /**
     * Removes a pin everywhere by sending it again, already expired.
     *
     * Sent first: deleting locally and then failing to send would leave the
     * pin on every other phone with no copy left here to expire it again. Sent
     * more than once because nothing confirms a room broadcast arrived
     * everywhere, and this is the one message whose loss cannot be repaired.
     */
    public func remove(pin: MapPin) async throws {
        let myNodeNum = mesh.myNodeNum.value
        guard pin.canEdit(myNodeNum: myNodeNum) else {
            throw WaypointRepositoryError.locked
        }
        let roomId = try roomOf(pin: pin)
        var expired = pin.toWaypoint()
        expired.expire = UInt32(Self.expired)
        try await send(waypoint: expired, roomId: roomId)
        try await pinDao.delete(id: pin.id)
        try await pinDao.remember(deleted: DeletedPinEntity(id: pin.id, channel: pin.channel, deletedAt: nowMillis()))
        log.info("removed pin")
        let expiredCopy = expired
        let roomIdCopy = roomId
        tasks.withLock { jobs in
            jobs.append(
                Task { [weak self] in
                    guard let self else { return }
                    for _ in 0..<(Self.expiryRepeats - 1) {
                        await sleep(Self.expiryRepeatGap)
                        try? await send(waypoint: expiredCopy, roomId: roomIdCopy)
                    }
                })
        }
    }

    private func handlePin(opened: OpenedInRoom, waypoint: Waypoint) async {
        guard waypoint.hasLatitudeI, waypoint.hasLongitudeI else {
            return
        }
        let latitude = waypoint.latitudeI
        let longitude = waypoint.longitudeI
        if latitude == 0 && longitude == 0 {
            return
        }
        let sender = Int32(bitPattern: opened.packet.from)
        let existing = try? await pinDao.find(id: Int32(bitPattern: waypoint.id))
        let existingRoom = existing.map { pin in
            pin.roomId != 0 ? pin.roomId : mesh.roomIdForChannel(pin.channel) ?? 0
        }
        let allowed = TrustRules.pinUpdateAllowed(
            sealedInRoom: opened.sealedUnderCurrent && opened.onItsSlot ? opened.roomId : nil,
            sender: sender,
            claimedLock: Int32(bitPattern: waypoint.lockedTo),
            existingLock: existing?.lockedTo,
            existingRoom: existingRoom
        )
        if !allowed {
            log.warning("ignoring pin")
            return
        }
        let id = Int32(bitPattern: waypoint.id)
        if (try? await pinDao.wasDeleted(id: id)) == true {
            log.info("ignoring resurrected pin")
            let expire = Int64(waypoint.expire)
            if !(1..<nowMillis() / 1_000).contains(expire) {
                var expired = waypoint
                expired.expire = UInt32(Self.expired)
                let expiredCopy = expired
                let roomIdCopy = opened.roomId
                tasks.withLock { jobs in
                    jobs.append(
                        Task { [weak self] in
                            try? await self?.send(waypoint: expiredCopy, roomId: roomIdCopy)
                        })
                }
            }
            return
        }
        // An icon that is not a Unicode scalar makes Kotlin's Character.toChars throw, which drops the pin; so here.
        if waypoint.icon != 0 && UnicodeScalar(waypoint.icon) == nil {
            return
        }
        let pin = MapPin(
            id: id,
            channel: Int(opened.packet.channel),
            latitudeI: latitude,
            longitudeI: longitude,
            name: Self.limitName(waypoint.name),
            description: Self.limitDescription(waypoint.description_p),
            expire: Int64(waypoint.expire),
            lockedTo: Int32(bitPattern: waypoint.lockedTo),
            icon: UnicodeScalar(waypoint.icon).flatMap { waypoint.icon == 0 ? nil : String($0) },
            createdBy: existing?.createdBy ?? sender,
            receivedAt: nowMillis(),
            roomId: opened.roomId
        )
        if pin.isExpired(nowMillis: nowMillis()) {
            try? await pinDao.delete(id: pin.id)
            log.info("pin expired")
        } else {
            try? await pinDao.save(pin: pin)
            await rooms.noteActivity(roomId: opened.roomId)
        }
    }

    /** Sealed in the room, so it goes where the room is and nowhere else. */
    private func send(waypoint: Waypoint, roomId: Int32) async throws {
        var control = Meshchat_MeshChatControl()
        control.version = InviteCodec.version
        control.pin = waypoint
        let sent = await rooms.sendSealed(roomId: roomId, control: control, priority: .reliable)
        if !sent {
            throw WaypointRepositoryError.notARoom
        }
        log.info("sent pin")
    }

    /** The room a slot carries now. Pins only ever live in one. */
    private func roomOf(channel: Int) throws -> Int32 {
        guard let roomId = mesh.roomIdForChannel(channel) else {
            throw WaypointRepositoryError.notARoom
        }
        return roomId
    }

    /** A pin's room: the one it was filed under, or for an older pin, the one in its slot now. */
    private func roomOf(pin: MapPin) throws -> Int32 {
        if pin.roomId != 0 {
            return pin.roomId
        }
        return try roomOf(channel: pin.channel)
    }

    /** Non-zero so the firmware does not treat it as unset. */
    public static func defaultPinId() -> Int32 {
        var id: Int32 = 0
        while id == 0 {
            id = Int32.random(in: Int32.min...Int32.max)
        }
        return id
    }

    private static func limitName(_ name: String) -> String {
        MeshConstants.truncateToBytes(sanitizeMeshText(name), maxBytes: nameLimit)
    }

    private static func limitDescription(_ description: String) -> String {
        MeshConstants.truncateToBytes(sanitizeMeshText(description), maxBytes: descriptionLimit)
    }

    private enum WaypointRepositoryError: Error {
        case locked
        case notARoom
        case notConnected
    }

    /**
     * Bytes, not characters: the sealed pin has to fit one packet, and
     * ProtocolContractTest checks it does at exactly these limits.
     */
    private static let nameLimit = 30
    private static let descriptionLimit = 100

    /** Epoch second 1: comfortably in the past, and not zero, which means "never". */
    private static let expired: Int64 = 1

    /** Nothing confirms a room broadcast reached everyone, so the deletion is said again. */
    private static let expiryRepeats = 3
    private static let expiryRepeatGap: Duration = .seconds(10)
}

extension MapPin {
    fileprivate func toWaypoint() -> Waypoint {
        var waypoint = Waypoint()
        waypoint.id = UInt32(bitPattern: id)
        waypoint.latitudeI = latitudeI
        waypoint.longitudeI = longitudeI
        waypoint.expire = UInt32(expire)
        waypoint.lockedTo = UInt32(bitPattern: lockedTo)
        waypoint.name = name
        waypoint.description_p = description
        return waypoint
    }
}
