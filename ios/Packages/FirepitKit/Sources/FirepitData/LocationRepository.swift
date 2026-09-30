import CoreLocation
import FirepitCrypto
import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation
import os

/// What came of asking somebody where they are.
public enum PositionAnswer: Sendable, Equatable {
    /// A position came back, and has already been stored.
    case answered

    /// The question went out; the answer will land on the map or not at all.
    case asked

    /// Nothing came back inside the window: they are out of range, their phone
    /// is away from their radio, or they are not sharing with the room we share.
    case silent

    /// Nothing was asked, because there is no radio to ask through.
    case notConnected

    /// Not somebody we can ask: the question only travels inside a room.
    case noSharedRoom
}

public protocol PhoneLocationProviding: Sendable {
    func updates(interval: Duration) -> AsyncStream<CLLocation>
    func hasAnyProvider() -> Bool
}

/// Where we are, told to one room and to nobody else.
///
/// The phone seals its own fix under the room's key and sends it at the beacon
/// rate, so the radios carrying it — and whoever holds one — read nothing. The
/// radio's own position broadcast is kept off on every channel, re-asserted on
/// every connection: it goes out under the channel key, and it would carry on
/// with the phone away, turning a share the phone ended into one that never
/// ends. The cost is that sharing pauses while the phone is away from its radio.
public final class LocationRepository: Sendable {
    public let mesh: MeshRepository
    public let admin: NodeAdminClient
    public let phoneLocation: any PhoneLocationProviding
    public let rooms: RoomRepository
    public let sharingStore: SharingStore

    /**
     * Set while the map is on screen.
     *
     * Seeing yourself on a map is a local question with no privacy consequence,
     * so it must not require opting into sharing. Sharing is the separate,
     * deliberate act below.
     */
    private let mapVisible = CurrentValue(false)

    /** The newest fix the phone has, for answering "where are you?" without waiting on GPS. */
    private let lastFix = Mutex<CLLocation?>(nil)
    private let lastShared = Mutex<CLLocation?>(nil)
    private let lastSharedAt = Mutex<Int64>(0)
    private let lastAnsweredAt = Mutex<Int64>(0)

    /** Members whose sealed position just arrived, for whoever asked them. */
    private let positionsHeard = Broadcast<Int32>()

    private let tasks = Mutex<[Task<Void, Never>]>([])
    private let nowMillis: @Sendable () -> Int64
    private let log = Logger(subsystem: "com.getfirepit.app", category: "FirepitLocation")

    public init(
        mesh: MeshRepository,
        admin: NodeAdminClient,
        phoneLocation: any PhoneLocationProviding,
        rooms: RoomRepository,
        sharingStore: SharingStore,
        nowMillis: @escaping @Sendable () -> Int64 = currentEpochMillis
    ) {
        self.mesh = mesh
        self.admin = admin
        self.phoneLocation = phoneLocation
        self.rooms = rooms
        self.sharingStore = sharingStore
        self.nowMillis = nowMillis
    }

    deinit {
        tasks.withLock { jobs in
            for job in jobs {
                job.cancel()
            }
            jobs.removeAll()
        }
    }

    public func setMapVisible(visible: Bool) {
        mapVisible.set(visible)
    }

    /**
     * The room we are sharing with through the radio connected now, or null —
     * when nothing is shared, or when that room is not on this radio, which
     * pauses sharing rather than ending it.
     */
    public func sharingRoomId() -> Int32? {
        activeSharingRoom()
    }

    /** When sharing stops on its own, or null when nothing is shared or nothing stops it. */
    public var sharingDeadline: CurrentValue<SharingDeadline?> {
        sharingStore.deadline
    }

    /** Nodes with a known fix, newest sighting first. */
    public func observePositions() -> AsyncStream<[MeshNode]> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let task = Task {
                for await nodes in mesh.observeNodes() {
                    continuation.yield(nodes.filter(\.hasPosition))
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    public func start() {
        tasks.withLock { jobs in
            guard jobs.isEmpty else {
                return
            }
            jobs.append(
                Task { [weak self] in
                    guard let self else { return }
                    for await channels in mesh.channels.subscribe() {
                        if channels.isEmpty {
                            continue
                        }
                        let writes = PositionSharing.writesToSilence(channels: channels)
                        if !writes.isEmpty {
                            log.warning("the radio broadcasts position, silencing it")
                            do {
                                try await silence(writes: writes)
                            } catch {
                                log.error("could not stop the radio broadcasting position")
                            }
                        }
                        enforceDeadline()
                    }
                })
            jobs.append(
                Task { [weak self] in
                    guard let self else { return }
                    while !Task.isCancelled {
                        enforceDeadline()
                        try? await Task.sleep(for: Self.deadlineCheck)
                    }
                })
            jobs.append(
                Task { [weak self] in
                    guard let self else { return }
                    await runPhoneLocationLoop()
                })
            jobs.append(
                Task { [weak self] in
                    guard let self else { return }
                    for await opened in rooms.openedInRooms.subscribe() {
                        do {
                            if case .position = opened.control.payload {
                                await receivePosition(opened: opened, position: opened.control.position)
                            }
                            if case .positionQuery = opened.control.payload {
                                await answerQuery(opened: opened)
                            }
                        }
                    }
                })
        }
    }

    private func runPhoneLocationLoop() async {
        var gpsTask: Task<Void, Never>?
        var running = false
        let desired = CurrentValue(mapVisible.value || sharingIsLive())
        let visibleTask = Task {
            for await visible in mapVisible.subscribe() {
                desired.set(visible || sharingIsLive())
            }
        }
        let sharingTask = Task {
            for await _ in sharingStore.deadline.subscribe() {
                desired.set(mapVisible.value || sharingIsLive())
            }
        }
        let connectedTask = Task {
            for await _ in mesh.isConnected.subscribe() {
                desired.set(mapVisible.value || sharingIsLive())
            }
        }
        defer {
            visibleTask.cancel()
            sharingTask.cancel()
            connectedTask.cancel()
            gpsTask?.cancel()
        }
        for await shouldRun in desired.subscribe() {
            if shouldRun == running {
                continue
            }
            running = shouldRun
            gpsTask?.cancel()
            gpsTask = nil
            if shouldRun {
                gpsTask = Task { [weak self] in
                    guard let self else { return }
                    for await location in phoneLocation.updates(interval: .seconds(30)) {
                        lastFix.withLock { $0 = location }
                        await storeOwnPosition(location: location)
                        if sharingIsLive() {
                            await shareIfDue(location: location)
                        }
                    }
                }
            }
        }
    }

    /**
     * Records the phone's fix as our own node's position.
     *
     * Written straight to local storage rather than waiting for the radio to
     * tell us where we are: we already know, and the radio may never say.
     */
    private func storeOwnPosition(location: CLLocation) async {
        guard let myNodeNum = mesh.myNodeNum.value else {
            log.warning("got a fix but no node number yet, connect the radio first")
            return
        }
        try? await mesh.setOwnPosition(
            nodeNum: myNodeNum,
            latitudeI: Int32(location.coordinate.latitude * 1e7),
            longitudeI: Int32(location.coordinate.longitude * 1e7),
            altitude: location.verticalAccuracy >= 0 ? Int(location.altitude) : nil,
            timeMillis: Int64(location.timestamp.timeIntervalSince1970 * 1_000)
        )
    }

    /**
     * Shares [location] when the beacon interval has passed, or sooner when
     * smart beaconing is on and we have moved far enough — the same rule the
     * radio would apply if it were allowed to broadcast for us.
     */
    private func shareIfDue(location: CLLocation) async {
        guard let roomId = activeSharingRoom() else {
            return
        }
        let now = nowMillis()
        let config = mesh.snapshot.value?.position
        let configured = config?.positionBroadcastSecs ?? 0
        let rawInterval = configured > 0 ? Int64(configured) : Self.firmwareDefaultSeconds
        let interval = max(rawInterval, Self.minimumBeaconSeconds) * 1_000
        let previousSent = lastSharedAt.withLock { $0 }
        let sinceLast = now - previousSent
        let smartDue =
            (config?.positionBroadcastSmartEnabled ?? false) && movedEnough(location: location, config: config)
            && sinceLast >= durationMillis(smartInterval(config: config))
        let due = previousSent == 0 || sinceLast >= interval || smartDue
        if due {
            await share(roomId: roomId, location: location)
        }
    }

    private func movedEnough(location: CLLocation, config: Config.PositionConfig?) -> Bool {
        guard let previous = lastShared.withLock({ $0 }) else {
            return true
        }
        let configured = config?.broadcastSmartMinimumDistance ?? 0
        let minimum = configured > 0 ? Double(configured) : Self.smartDistanceMetres
        return previous.distance(from: location) >= minimum
    }

    private func smartInterval(config: Config.PositionConfig?) -> Duration {
        let configured = config?.broadcastSmartMinimumIntervalSecs ?? 0
        return configured > 0 ? .seconds(Int64(configured)) : Self.smartMinimumInterval
    }

    /** Seals the fix under the room's key and sends it to the room. */
    private func share(roomId: Int32, location: CLLocation) async {
        var position = Position()
        position.latitudeI = Int32(location.coordinate.latitude * 1e7)
        position.longitudeI = Int32(location.coordinate.longitude * 1e7)
        if location.verticalAccuracy >= 0 {
            position.altitude = Int32(location.altitude)
        }
        position.time = UInt32(location.timestamp.timeIntervalSince1970)
        position.locationSource = .locExternal
        if location.speed >= 0 {
            position.groundSpeed = UInt32(location.speed)
        }
        position.precisionBits = UInt32(PositionPrecision.full)
        var control = Meshchat_MeshChatControl()
        control.version = InviteCodec.version
        control.position = position
        let sent = await rooms.sendSealed(roomId: roomId, control: control)
        if sent {
            lastShared.withLock { $0 = location }
            lastSharedAt.withLock { $0 = nowMillis() }
            await rooms.noteActivity(roomId: roomId)
        }
    }

    /**
     * A member's position, sealed by their phone. Believed only under the
     * room's current key, on the room's own slot.
     */
    private func receivePosition(opened: OpenedInRoom, position: Position) async {
        if !TrustRules.sealedPositionAcceptable(
            sealedUnderCurrent: opened.sealedUnderCurrent,
            onItsRoomSlot: opened.onItsSlot
        ) {
            log.warning("position not sealed under room current key")
            return
        }
        await mesh.storeSealedPosition(nodeNum: Int32(bitPattern: opened.packet.from), position: position)
        positionsHeard.send(Int32(bitPattern: opened.packet.from))
        await rooms.noteActivity(roomId: opened.roomId)
    }

    /**
     * "Where are you?" from a member. Answered with a sealed position to the
     * room, and only while we are sharing with that room: asking is not a way
     * round somebody's choice not to share.
     */
    private func answerQuery(opened: OpenedInRoom) async {
        guard let myNodeNum = mesh.myNodeNum.value else {
            return
        }
        let answerable = TrustRules.positionQueryAnswerable(
            sealedUnderCurrent: opened.sealedUnderCurrent && opened.onItsSlot,
            addressedToUs: Int32(bitPattern: opened.packet.to) == myNodeNum,
            queryRoom: opened.roomId,
            sharingWithRoom: activeSharingRoom()
        )
        if !answerable {
            return
        }
        let now = nowMillis()
        if now - lastAnsweredAt.withLock({ $0 }) < durationMillis(Self.answerGap) {
            return
        }
        guard let fix = lastFix.withLock({ $0 }) else {
            return
        }
        let fixTime = Int64(fix.timestamp.timeIntervalSince1970 * 1_000)
        if now - fixTime >= durationMillis(Self.freshFix) {
            return
        }
        lastAnsweredAt.withLock { $0 = now }
        await share(roomId: opened.roomId, location: fix)
    }

    /**
     * Writes precision 0 to every channel the radio would broadcast on.
     *
     * Thrown rather than skipped when a channel cannot be read: a channel we
     * could not read is one we could not change.
     */
    private func silence(writes: [PrecisionWrite]) async throws {
        for write in writes {
            guard var channel = await admin.getChannel(index: write.index) else {
                throw LocationRepositoryError.channelUnreadable
            }
            // Writing back a channel that came without settings would replace its name and key with defaults.
            guard channel.hasSettings else {
                throw LocationRepositoryError.channelUnreadable
            }
            channel.settings.moduleSettings.positionPrecision = UInt32(write.precision)
            try await admin.setChannel(channel)
        }
        log.info("the radio's own position broadcast is off on more channels")
    }

    /**
     * Shares with [roomId] for a chosen length of time, or stops when it is null.
     *
     * Only a Firepit room whose key this phone holds: nowhere else can a
     * position be sealed.
     */
    public func shareWith(roomId: Int32?, choice: ShareDuration) async throws {
        guard let roomId else {
            stopSharing()
            return
        }
        guard mesh.channels.value.contains(where: { $0.id == roomId && PositionSharing.canShare(channel: $0) }) else {
            throw LocationRepositoryError.notShareable
        }
        sharingStore.remember(roomId: roomId, choice: choice, nowMillis: nowMillis())
        lastSharedAt.withLock { $0 = 0 }
        if let fix = lastFix.withLock({ $0 }) {
            await shareIfDue(location: fix)
        }
    }

    /** Stops sharing if its time has run out. Safe to call as often as you like. */
    public func enforceDeadline() {
        guard let deadline = sharingStore.deadline.value else {
            return
        }
        if !deadline.hasPassed(nowMillis: nowMillis()) {
            return
        }
        log.info("sharing has run out, stopping")
        stopSharing()
    }

    /**
     * Stops sharing. The phone is what sends, so this takes effect at once, on
     * every radio, whether or not one is connected.
     */
    public func stopSharing() {
        sharingStore.clear()
        lastShared.withLock { $0 = nil }
        lastSharedAt.withLock { $0 = 0 }
    }

    /** The shared room, if it is a sealed room on the radio connected now. */
    public func activeSharingRoom() -> Int32? {
        guard let roomId = sharingStore.deadline.value?.roomId else {
            return nil
        }
        return mesh.channels.value.first { $0.id == roomId && PositionSharing.canShare(channel: $0) }?.id
    }

    /**
     * Asks a member where they are without waiting to hear back.
     *
     * For asking several people at once: the answers arrive through the
     * ordinary position path and move the pins as they land.
     */
    public func askForPosition(nodeNum: Int32) async -> PositionAnswer {
        if !mesh.isConnected.value {
            return .notConnected
        }
        guard let room = await rooms.sharedRoomWith(nodeNum: nodeNum) else {
            return .noSharedRoom
        }
        return await ask(roomId: room.id, nodeNum: nodeNum) ? .asked : .notConnected
    }

    /**
     * Asks a member where they are and waits for their phone to answer.
     *
     * Sealed in a room we share, both ways, so nobody outside it learns that
     * the question was asked or what came back. Their phone answers only while
     * it is sharing with that room and near its radio. Silence is the only
     * answer a mesh has for anything else, and it is reported as silence.
     */
    public func requestPosition(nodeNum: Int32, timeout: Duration = replyTimeout) async -> PositionAnswer {
        if !mesh.isConnected.value {
            return .notConnected
        }
        guard let room = await rooms.sharedRoomWith(nodeNum: nodeNum) else {
            return .noSharedRoom
        }
        // Listening starts before sending: the answer can arrive first.
        let answers = positionsHeard.subscribe()
        let sendFailed = Mutex(false)
        let heard = try? await withTimeoutOrNil(timeout) { [self] () async throws -> Int32? in
            if !(await ask(roomId: room.id, nodeNum: nodeNum)) {
                sendFailed.withLock { $0 = true }
                return nil
            }
            return await answers.first { $0 == nodeNum }
        }
        if sendFailed.withLock({ $0 }) {
            return .notConnected
        }
        return heard == nil ? .silent : .answered
    }

    private func ask(roomId: Int32, nodeNum: Int32) async -> Bool {
        var control = Meshchat_MeshChatControl()
        control.version = InviteCodec.version
        control.positionQuery = Meshchat_PositionQuery()
        return await rooms.sendSealed(roomId: roomId, control: control, to: nodeNum, priority: .reliable)
    }

    private func sharingIsLive() -> Bool {
        sharingStore.deadline.value != nil && mesh.isConnected.value
    }

    private func durationMillis(_ duration: Duration) -> Int64 {
        let components = duration.components
        return Int64(components.seconds) * 1_000 + Int64(components.attoseconds / 1_000_000_000_000_000)
    }

    private enum LocationRepositoryError: Error {
        case channelUnreadable
        case notShareable
    }

    private static let firmwareDefaultSeconds: Int64 = 900
    private static let minimumBeaconSeconds: Int64 = 30
    /// Generous: the question crosses the mesh, and so does the answer.
    public static let replyTimeout: Duration = .seconds(60)
    /// The firmware's own smart-beacon defaults, used when the radio leaves them at zero.
    private static let smartDistanceMetres: Double = 100
    private static let smartMinimumInterval: Duration = .seconds(30)
    /// One answer to many askers at once is enough.
    private static let answerGap: Duration = .seconds(60)
    private static let freshFix: Duration = .seconds(300)
    private static let deadlineCheck: Duration = .seconds(30)
}
