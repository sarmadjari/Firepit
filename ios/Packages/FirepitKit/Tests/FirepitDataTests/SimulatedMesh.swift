import FirepitCrypto
import FirepitModel
import FirepitProtocol
import FirepitProtos
import FirepitTransport
import Foundation
import GRDB

@testable import FirepitData

final class SimulatedRadio: RadioLinking, @unchecked Sendable {
    let state = CurrentValue<LinkState>(.disconnected)
    let inbound = Broadcast<FromRadio>()
    let nodeNum: Int32
    let radioKey: Data
    weak var mesh: SimulatedMesh?
    var isOnline = true
    private let lock = Mutex(State())

    struct State {
        var channels: [Int: Channel] = [:]
        var sent: [ToRadio] = []
    }

    init(nodeNum: Int32, radioKey: Data) {
        self.nodeNum = nodeNum
        self.radioKey = radioKey
        var primary = PrimaryChannel.channelFor(mode: .groupOnly)
        primary.index = 0
        lock.withLock { state in state.channels[0] = primary }
    }

    var channels: [Int: Channel] {
        lock.withLock { $0.channels }
    }

    func setChannel(_ channel: Channel) {
        lock.withLock { state in state.channels[Int(channel.index)] = channel }
        publishReady()
    }

    func removeChannel(_ index: Int) {
        let _: Channel? = lock.withLock { state in state.channels.removeValue(forKey: index) }
        publishReady()
    }

    func send(_ message: ToRadio) async throws {
        guard isOnline else { throw RadioLinkError.notConnected }
        lock.withLock { $0.sent.append(message) }
        let packet = message.packet
        guard case .decoded(let data)? = packet.payloadVariant else { return }
        if data.portnum == .adminApp {
            try await handleAdmin(packet: packet, data: data)
        } else {
            await mesh?.transmit(from: self, packet: packet)
        }
    }

    func push(_ packet: MeshPacket) {
        guard isOnline else { return }
        var from = FromRadio()
        from.packet = packet
        inbound.send(from)
    }

    func publishReady() {
        guard isOnline else {
            state.set(.disconnected)
            return
        }
        var my = MyNodeInfo()
        my.myNodeNum = UInt32(bitPattern: nodeNum)
        var user = User()
        user.id = MeshConstants.formatNodeId(nodeNum)
        user.longName = "Node \(nodeNum)"
        user.shortName = "N\(abs(Int(nodeNum)) % 100)"
        user.publicKey = radioKey
        var node = NodeInfo()
        node.num = UInt32(bitPattern: nodeNum)
        node.user = user
        var lora = Config.LoRaConfig()
        lora.hopLimit = UInt32(MeshConstants.defaultHopLimit)
        var config = Config()
        config.lora = lora
        let snapshot = RadioSnapshot(
            myInfo: my,
            channels: Dictionary(uniqueKeysWithValues: channels.map { (Int32($0.key), $0.value) }),
            configs: [config],
            nodes: [nodeNum: node]
        )
        state.set(.ready(snapshot))
    }

    private func handleAdmin(packet: MeshPacket, data: DataMessage) async throws {
        let admin = try AdminMessage(serializedBytes: data.payload)
        if admin.getConfigRequest == .sessionkeyConfig {
            var reply = AdminMessage()
            reply.sessionPasskey = Data()
            sendAdminReply(to: packet, admin: reply)
            return
        }
        if case .setChannel(let channel)? = admin.payloadVariant {
            setChannel(channel)
            ack(requestId: packet.id)
            return
        }
        if admin.getChannelRequest != 0 {
            var reply = AdminMessage()
            if let channel = channels[Int(admin.getChannelRequest) - 1] {
                reply.getChannelResponse = channel
            }
            sendAdminReply(to: packet, admin: reply)
            return
        }
        ack(requestId: packet.id)
    }

    private func sendAdminReply(to packet: MeshPacket, admin: AdminMessage) {
        var data = DataMessage()
        data.portnum = .adminApp
        data.requestID = packet.id
        data.payload = (try? admin.serializedData()) ?? Data()
        var reply = MeshPacket()
        reply.from = UInt32(bitPattern: nodeNum)
        reply.to = UInt32(bitPattern: nodeNum)
        reply.id = MeshPacketBuilder.randomPacketId().asUInt32
        reply.rxTime = UInt32(currentEpochMillis() / 1000)
        reply.decoded = data
        push(reply)
    }

    private func ack(requestId: UInt32, from: Int32? = nil, error: Routing.Error = .none) {
        var routing = Routing()
        routing.errorReason = error
        var data = DataMessage()
        data.portnum = .routingApp
        data.requestID = requestId
        data.payload = (try? routing.serializedData()) ?? Data()
        var packet = MeshPacket()
        packet.from = UInt32(bitPattern: from ?? nodeNum)
        packet.to = UInt32(bitPattern: nodeNum)
        packet.id = MeshPacketBuilder.randomPacketId().asUInt32
        packet.rxTime = UInt32(currentEpochMillis() / 1000)
        packet.decoded = data
        push(packet)
    }

    func recipientAck(requestId: UInt32, from: Int32) {
        ack(requestId: requestId, from: from)
    }

    /// What the firmware reports when a direct packet could not reach its recipient after its retries.
    func deliveryFailed(requestId: UInt32) {
        ack(requestId: requestId, error: .maxRetransmit)
    }
}

final class SimulatedPhone: @unchecked Sendable {
    let db: DatabaseQueue
    let radio: SimulatedRadio
    let meshRepository: MeshRepository
    let admin: NodeAdminClient
    let roomHistory: RoomHistory
    let receipts: ReceiptRepository
    let range: RangeRepository
    let roomRepository: RoomRepository
    let roomKeys: RoomKeyStore
    let roomKeyMade: RoomKeyMadeStore
    let phoneKeys: PhoneKeyStore
    let messageDao: MessageDao
    let nodeDao: NodeDao
    let memberDao: RoomMemberDao
    let peerKeyDao: PeerKeyDao
    let personCardDao: PersonCardDao
    let pinDao: MapPinDao
    let receiptDao: ReceiptDao
    let handovers: PendingHandoverDao
    let sharing: SharingStore
    let keyChange = CurrentValue<RoomKeyChange>(.daily)

    /// How far this phone's own clock is from everyone else's, for scenarios where phones disagree on the time.
    let clockSkewMillis = Mutex<Int64>(0)

    init(nodeNum: Int32, mesh: SimulatedMesh) throws {
        db = try FirepitDatabase.inMemory()
        radio = SimulatedRadio(nodeNum: nodeNum, radioKey: SimulatedMesh.radioKey(nodeNum))
        radio.mesh = mesh
        // Room keys follow the phone's own clock: the mesh's, plus whatever this phone is out by.
        roomKeys = RoomKeyStore(store: InMemorySecretStore(), time: SimulatedKeyTime(mesh: mesh, skew: clockSkewMillis))
        let defaults = UserDefaults(suiteName: "firepit-sim-\(nodeNum)-\(UUID().uuidString)")!
        roomKeyMade = RoomKeyMadeStore(defaults: defaults)
        phoneKeys = PhoneKeyStore(store: InMemorySecretStore(), source: SoftwarePhoneKeySource())
        messageDao = MessageDao(db)
        nodeDao = NodeDao(db)
        memberDao = RoomMemberDao(db)
        peerKeyDao = PeerKeyDao(db)
        personCardDao = PersonCardDao(db)
        pinDao = MapPinDao(db)
        receiptDao = ReceiptDao(db)
        handovers = PendingHandoverDao(db)
        let session = SessionStore(defaults: defaults)
        session.myNodeNum = nodeNum
        let roomActivity = RoomActivityDao(db)
        meshRepository = MeshRepository(
            link: radio,
            messageDao: messageDao,
            nodeDao: nodeDao,
            sessionStore: session,
            roomKeys: roomKeys,
            peerKeyDao: peerKeyDao,
            memberDao: memberDao,
            phoneKeys: phoneKeys,
            roomActivity: roomActivity,
            handovers: handovers,
            receiptDao: receiptDao
        )
        admin = NodeAdminClient(link: radio, repository: meshRepository)
        range = RangeRepository(
            mesh: meshRepository,
            admin: admin,
            backup: PrimaryBackup(store: InMemorySecretStore()),
            defaults: defaults
        )
        roomHistory = RoomHistory(
            mesh: meshRepository,
            messageDao: messageDao,
            pinDao: pinDao,
            channelState: ChannelStateDao(db),
            roomActivity: roomActivity,
            sessionStore: session
        )
        receipts = ReceiptRepository(
            link: radio,
            mesh: meshRepository,
            roomKeys: roomKeys,
            receiptDao: receiptDao,
            messageDao: messageDao,
            memberDao: memberDao,
            phoneKeys: phoneKeys
        )
        sharing = SharingStore(defaults: defaults)
        roomRepository = RoomRepository(
            link: radio,
            mesh: meshRepository,
            admin: admin,
            memberDao: memberDao,
            messageDao: messageDao,
            receipts: receipts,
            roomKeys: roomKeys,
            range: range,
            personCardDao: personCardDao,
            phoneKeys: phoneKeys,
            peerKeyDao: peerKeyDao,
            pinDao: pinDao,
            roomActivity: roomActivity,
            roomKeyMade: roomKeyMade,
            handovers: handovers,
            history: roomHistory,
            sharingStore: sharing,
            keyChangeSetting: { [keyChange] in keyChange.value },
            clock: { [weak mesh] in currentEpochMillis() + (mesh?.clockOffsetMillis ?? 0) }
        )
    }

    func start() {
        meshRepository.start()
        range.start()
        roomHistory.start()
        roomRepository.start()
        radio.publishReady()
    }

    func channel(roomId: Int32) -> Channel? {
        radio.channels.values.first { Int32(bitPattern: $0.settings.id) == roomId }
    }
}

/// A simulated phone's clock, taken as right however far out it is: these scenarios are about clocks disagreeing
/// between phones. A clock that jumps on one phone is `KeyClock`'s business, and its tests'.
private final class SimulatedKeyTime: KeyTime, @unchecked Sendable {
    private weak var mesh: SimulatedMesh?
    private let skew: Mutex<Int64>

    init(mesh: SimulatedMesh, skew: Mutex<Int64>) {
        self.mesh = mesh
        self.skew = skew
    }

    func wallMillis() -> Int64 { currentEpochMillis() + (mesh?.clockOffsetMillis ?? 0) + skew.withLock { $0 } }

    func eraseMillis() -> Int64 { wallMillis() }
}

struct AirPacket: Sendable, Equatable {
    var id: UInt32
    var from: Int32
    var to: Int32
    var channel: Int
    var portNum: PortNum
    var payload: Data
    var pkiEncrypted: Bool
}

final class SimulatedMesh: @unchecked Sendable {
    private let lock = Mutex(State())

    struct State {
        var phones: [Int32: SimulatedPhone] = [:]
        var air: [AirPacket] = []
        var directAcks = true
        var clockOffsetMillis: Int64 = 0
        var lostAtApp: Set<Int32> = []
        var silenced: Set<Int32> = []
    }

    init() {}

    /// How far the phones' room clocks run ahead of the wall clock, so a test can let retry intervals pass.
    var clockOffsetMillis: Int64 {
        lock.withLock { $0.clockOffsetMillis }
    }

    func advanceClock(byMillis millis: Int64) {
        lock.withLock { $0.clockOffsetMillis += millis }
    }

    /// Phones whose radio acknowledges direct packets that their app then never gets: an app that crashed, or could
    /// not store what arrived, at that moment.
    func loseAtApp(_ nodeNum: Int32, _ lost: Bool = true) {
        lock.withLock { state in
            if lost {
                state.lostAtApp.insert(nodeNum)
            } else {
                state.lostAtApp.remove(nodeNum)
            }
        }
    }

    /// Phones that still receive, and whose radio still acknowledges, but whose own packets reach nobody.
    func silence(_ nodeNum: Int32, _ silent: Bool = true) {
        lock.withLock { state in
            if silent {
                state.silenced.insert(nodeNum)
            } else {
                state.silenced.remove(nodeNum)
            }
        }
    }

    /// Whether a recipient's radio acknowledges direct packets. Off models an ack lost on the air.
    var directAcks: Bool {
        get { lock.withLock { $0.directAcks } }
        set { lock.withLock { $0.directAcks = newValue } }
    }

    @discardableResult func addPhone(_ nodeNum: Int32) throws -> SimulatedPhone {
        let phone = try SimulatedPhone(nodeNum: nodeNum, mesh: self)
        lock.withLock { $0.phones[nodeNum] = phone }
        phone.start()
        return phone
    }

    var airPackets: [AirPacket] {
        lock.withLock { $0.air }
    }

    func phone(_ nodeNum: Int32) -> SimulatedPhone? {
        lock.withLock { $0.phones[nodeNum] }
    }

    func takeOffline(_ nodeNum: Int32) {
        phone(nodeNum)?.radio.isOnline = false
        phone(nodeNum)?.radio.state.set(.disconnected)
    }

    func bringOnline(_ nodeNum: Int32) {
        guard let phone = phone(nodeNum) else { return }
        phone.radio.isOnline = true
        phone.radio.publishReady()
    }

    func inject(packet: MeshPacket, to nodeNum: Int32) {
        phone(nodeNum)?.radio.push(packet)
    }

    func transmit(from sender: SimulatedRadio, packet original: MeshPacket) async {
        guard case .decoded(let data)? = original.payloadVariant else { return }
        var packet = original
        packet.from = UInt32(bitPattern: sender.nodeNum)
        packet.rxTime = UInt32(currentEpochMillis() / 1000)
        lock.withLock { state in
            state.air.append(
                AirPacket(
                    id: packet.id,
                    from: sender.nodeNum,
                    to: Int32(bitPattern: packet.to),
                    channel: Int(original.channel),
                    portNum: data.portnum,
                    payload: data.payload,
                    pkiEncrypted: packet.pkiEncrypted
                )
            )
        }
        if lock.withLock({ $0.silenced.contains(sender.nodeNum) }) {
            return
        }
        let delivered: Bool
        if packet.pkiEncrypted, Int32(bitPattern: packet.to) != broadcastNodeNum {
            delivered = deliverDirect(sender: sender, packet: packet)
        } else {
            deliverBroadcast(sender: sender, packet: packet)
            delivered = true
        }
        let isDirect = Int32(bitPattern: packet.to) != broadcastNodeNum
        if packet.wantAck && isDirect && !delivered {
            sender.deliveryFailed(requestId: packet.id)
        }
        if packet.wantAck && delivered && (!isDirect || directAcks) {
            let ackFrom =
                Int32(bitPattern: packet.to) == broadcastNodeNum
                ? sender.nodeNum
                : Int32(bitPattern: packet.to)
            sender.recipientAck(requestId: packet.id, from: ackFrom)
        }
    }

    private func deliverDirect(sender: SimulatedRadio, packet: MeshPacket) -> Bool {
        let to = Int32(bitPattern: packet.to)
        guard let recipient = phone(to), recipient.radio.isOnline else { return false }
        if lock.withLock({ $0.lostAtApp.contains(to) }) {
            return true
        }
        var delivered = packet
        delivered.channel = 0
        delivered.hopStart = delivered.hopLimit
        delivered.publicKey = sender.radioKey
        recipient.radio.push(delivered)
        return true
    }

    private func deliverBroadcast(sender: SimulatedRadio, packet: MeshPacket) {
        let senderChannel = sender.channels[Int(packet.channel)]
        guard let senderSettings = senderChannel?.settings else { return }
        let recipients = lock.withLock { Array($0.phones.values) }
        for phone in recipients where phone.radio.nodeNum != sender.nodeNum && phone.radio.isOnline {
            guard
                let slot = phone.radio.channels.first(where: { _, channel in
                    channel.role == .secondary
                        && channel.settings.name == senderSettings.name
                        && channel.settings.psk == senderSettings.psk
                })?.key
            else {
                continue
            }
            var delivered = packet
            delivered.channel = UInt32(slot)
            delivered.hopStart = delivered.hopLimit
            phone.radio.push(delivered)
        }
    }

    static func radioKey(_ nodeNum: Int32) -> Data {
        Data((0..<32).map { UInt8(truncatingIfNeeded: Int(nodeNum) + $0) })
    }
}

extension Int32 {
    fileprivate var asUInt32: UInt32 { UInt32(bitPattern: self) }
}
