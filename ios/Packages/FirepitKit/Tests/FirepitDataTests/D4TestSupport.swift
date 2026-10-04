import CoreLocation
import FirepitCrypto
import FirepitModel
import FirepitProtocol
import FirepitProtos
import FirepitTransport
import Foundation
import GRDB
import Testing

@testable import FirepitData

final class ScriptedLocationSource: PhoneLocationProviding {
    private let fixes = Broadcast<CLLocation>()

    func updates(interval: Duration = .seconds(30)) -> AsyncStream<CLLocation> {
        fixes.subscribe()
    }

    func hasAnyProvider() -> Bool {
        true
    }

    func push(_ location: CLLocation) {
        fixes.send(location)
    }
}

final class SynchronousReplyLink: RadioLinking {
    let state = CurrentValue<LinkState>(.disconnected)
    let inbound = Broadcast<FromRadio>()
    private let storage = Mutex<[ToRadio]>([])
    private let handler = Mutex<(@Sendable (ToRadio) -> Void)?>(nil)

    var sent: [ToRadio] {
        storage.withLock { $0 }
    }

    func setOnSend(_ onSend: (@Sendable (ToRadio) -> Void)?) {
        handler.withLock { $0 = onSend }
    }

    func send(_ message: ToRadio) async throws {
        storage.withLock { $0.append(message) }
        handler.withLock { $0 }?(message)
    }

    func push(_ message: FromRadio) {
        inbound.send(message)
    }
}

struct D4SyncHarness: Sendable {
    let db: DatabaseQueue
    let link: SynchronousReplyLink
    let mesh: MeshRepository
    let roomKeys: RoomKeyStore
    let phoneKeys: PhoneKeyStore
    let messageDao: MessageDao
    let nodeDao: NodeDao
    let peerKeyDao: PeerKeyDao
    let memberDao: RoomMemberDao
    let roomActivity: RoomActivityDao
    let personCardDao: PersonCardDao
    let pinDao: MapPinDao
    let admin: NodeAdminClient
    let sharing: SharingStore
    let repository: RoomRepository

    init(myNodeNum: Int32? = 111) throws {
        db = try FirepitDatabase.inMemory()
        link = SynchronousReplyLink()
        roomKeys = RoomKeyStore(store: InMemorySecretStore())
        phoneKeys = PhoneKeyStore(store: InMemorySecretStore(), source: SoftwarePhoneKeySource())
        messageDao = MessageDao(db)
        nodeDao = NodeDao(db)
        peerKeyDao = PeerKeyDao(db)
        memberDao = RoomMemberDao(db)
        roomActivity = RoomActivityDao(db)
        personCardDao = PersonCardDao(db)
        pinDao = MapPinDao(db)
        let defaults = UserDefaults(suiteName: "firepit-d4-sync-\(UUID().uuidString)")!
        let session = SessionStore(defaults: defaults)
        session.myNodeNum = myNodeNum
        mesh = MeshRepository(
            link: link,
            messageDao: messageDao,
            nodeDao: nodeDao,
            sessionStore: session,
            roomKeys: roomKeys,
            peerKeyDao: peerKeyDao,
            memberDao: memberDao,
            phoneKeys: phoneKeys,
            roomActivity: roomActivity,
            handovers: PendingHandoverDao(db),
            receiptDao: ReceiptDao(db)
        )
        admin = NodeAdminClient(link: link, repository: mesh)
        sharing = SharingStore(defaults: defaults)
        let receiptRepository = ReceiptRepository(
            link: link,
            mesh: mesh,
            roomKeys: roomKeys,
            receiptDao: ReceiptDao(db),
            messageDao: messageDao,
            memberDao: memberDao,
            phoneKeys: phoneKeys
        )
        let range = RangeRepository(
            mesh: mesh,
            admin: admin,
            backup: PrimaryBackup(store: InMemorySecretStore()),
            defaults: defaults
        )
        let history = RoomHistory(
            mesh: mesh,
            messageDao: messageDao,
            pinDao: pinDao,
            channelState: ChannelStateDao(db),
            roomActivity: roomActivity,
            sessionStore: session
        )
        repository = RoomRepository(
            link: link,
            mesh: mesh,
            admin: admin,
            memberDao: memberDao,
            messageDao: messageDao,
            receipts: receiptRepository,
            roomKeys: roomKeys,
            range: range,
            personCardDao: personCardDao,
            phoneKeys: phoneKeys,
            peerKeyDao: peerKeyDao,
            pinDao: pinDao,
            roomActivity: roomActivity,
            handovers: PendingHandoverDao(db),
            history: history,
            sharingStore: sharing
        )
        try seedRoom(roomId: 42, channel: 1)
        mesh.isConnected.set(myNodeNum != nil)
    }

    func seedRoom(roomId: Int32, channel: Int) throws {
        try roomKeys.generate(roomId: roomId)
        var settings = ChannelSettings()
        settings.name = "Camp"
        settings.id = UInt32(bitPattern: roomId)
        settings.psk = Data(repeating: 7, count: RoomCrypto.pskSize)
        settings.moduleSettings.positionPrecision = UInt32(PositionPrecision.disabled)
        var radioChannel = Channel()
        radioChannel.index = Int32(channel)
        radioChannel.role = .secondary
        radioChannel.settings = settings
        let room = RoomChannel(
            index: channel,
            name: "Camp",
            role: .secondary,
            id: roomId,
            positionPrecision: PositionPrecision.disabled,
            kind: .firepit
        )
        mesh.channels.set([room])
        mesh.applyChannelWrite(radioChannel)
    }
}

func location(
    lat: Double,
    lon: Double,
    altitude: Double = 0,
    speed: Double = -1,
    timeMillis: Int64 = 1_000
) -> CLLocation {
    CLLocation(
        coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon),
        altitude: altitude,
        horizontalAccuracy: 5,
        verticalAccuracy: altitude == 0 ? -1 : 5,
        course: -1,
        speed: speed,
        timestamp: Date(timeIntervalSince1970: Double(timeMillis) / 1_000)
    )
}

func d4Harness(myNodeNum: Int32? = 111) throws -> RoomRepositoryHarness {
    let harness = try RoomRepositoryHarness(myNodeNum: myNodeNum)
    try harness.seedRoom(roomId: 42, channel: 1)
    harness.base.mesh.isConnected.set(myNodeNum != nil)
    return harness
}

func rememberMembers(_ harness: RoomRepositoryHarness, nodes: [Int32]) async throws {
    for node in nodes {
        try await harness.base.memberDao.record(roomId: 42, nodeNum: node, now: 1, invitedBy: 111)
    }
}

func openedControl(
    from packet: MeshPacket,
    key: HourKey,
    roomId: Int32,
    sender: Int32
) throws -> Meshchat_MeshChatControl {
    let outer = try Meshchat_MeshChatControl(serializedBytes: packet.decoded.payload)
    let plain = try #require(openSealed(outer.sealedMessage, key: key, roomId: roomId, sender: sender))
    return try Meshchat_MeshChatControl(serializedBytes: plain)
}

func lastSentPacket(_ harness: RoomRepositoryHarness) throws -> MeshPacket {
    try #require(harness.base.link.sent.last?.packet)
}

func roomKey(_ harness: RoomRepositoryHarness, roomId: Int32 = 42) throws -> HourKey {
    try #require(harness.base.roomKeys.currentKey(roomId: roomId))
}

func waitUntil(_ condition: @escaping @Sendable () -> Bool) async -> Bool {
    for _ in 0..<20 {
        if condition() {
            return true
        }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return condition()
}

func waitUntilAsync(_ condition: @escaping @Sendable () async -> Bool) async -> Bool {
    for _ in 0..<20 {
        if await condition() {
            return true
        }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return await condition()
}

func positionPacket(from node: Int32, to: Int32, roomId: Int32 = 42, channel: Int = 1) -> OpenedInRoom {
    var position = Position()
    position.latitudeI = 123
    position.longitudeI = 456
    position.precisionBits = UInt32(PositionPrecision.full)
    var control = Meshchat_MeshChatControl()
    control.position = position
    var data = DataMessage()
    data.portnum = .privateApp
    var packet = MeshPacket()
    packet.from = UInt32(bitPattern: node)
    packet.to = UInt32(bitPattern: to)
    packet.channel = UInt32(channel)
    packet.decoded = data
    return OpenedInRoom(
        packet: packet,
        roomId: roomId,
        sealedUnderCurrent: true,
        onItsSlot: true,
        control: control
    )
}
