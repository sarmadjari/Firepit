import FirepitCrypto
import FirepitModel
import FirepitProtocol
import FirepitProtos
import FirepitTransport
import Foundation
import GRDB
import Testing

@testable import FirepitData

final class FakeRadioLink: RadioLinking, @unchecked Sendable {
    let state = CurrentValue<LinkState>(.disconnected)
    let inbound = Broadcast<FromRadio>()
    private let storage = Mutex<[ToRadio]>([])
    var sent: [ToRadio] { storage.withLock { $0 } }

    func send(_ message: ToRadio) async throws {
        storage.withLock { $0.append(message) }
        let packet = message.packet
        guard packet.decoded.portnum == .adminApp,
            let admin = try? AdminMessage(serializedBytes: packet.decoded.payload),
            admin.getConfigRequest == .sessionkeyConfig
        else {
            return
        }
        var replyAdmin = AdminMessage()
        replyAdmin.sessionPasskey = Data()
        var data = DataMessage()
        data.portnum = .adminApp
        data.requestID = packet.id
        data.payload = try replyAdmin.serializedData()
        var replyPacket = MeshPacket()
        replyPacket.from = packet.to
        replyPacket.decoded = data
        var from = FromRadio()
        from.packet = replyPacket
        inbound.send(from)
    }

    func push(_ message: FromRadio) {
        inbound.send(message)
    }

    func setReady(_ snapshot: RadioSnapshot) {
        state.set(.ready(snapshot))
    }
}

struct Harness: Sendable {
    let db: DatabaseQueue
    let link: FakeRadioLink
    let mesh: MeshRepository
    let roomKeys: RoomKeyStore
    let phoneKeys: PhoneKeyStore
    let messageDao: MessageDao
    let nodeDao: NodeDao
    let peerKeyDao: PeerKeyDao
    let memberDao: RoomMemberDao
    let roomActivity: RoomActivityDao
    let personCardDao: PersonCardDao

    init(myNodeNum: Int32? = 111) throws {
        db = try FirepitDatabase.inMemory()
        link = FakeRadioLink()
        roomKeys = RoomKeyStore(store: InMemorySecretStore())
        phoneKeys = PhoneKeyStore(store: InMemorySecretStore(), source: SoftwarePhoneKeySource())
        messageDao = MessageDao(db)
        nodeDao = NodeDao(db)
        peerKeyDao = PeerKeyDao(db)
        memberDao = RoomMemberDao(db)
        roomActivity = RoomActivityDao(db)
        personCardDao = PersonCardDao(db)
        let defaults = UserDefaults(suiteName: "firepit-tests-\(UUID().uuidString)")!
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
            roomActivity: roomActivity
        )
    }
}

extension Harness {
    /// Publishes the radio's configuration and waits until MeshRepository has applied it, instead of guessing how long
    /// that takes.
    func connect(_ snapshot: RadioSnapshot = makeSnapshot()) async {
        link.setReady(snapshot)
        let channels = snapshot.channels.count
        _ = await eventually {
            mesh.isConnected.value && mesh.myNodeNum.value == snapshot.myNodeNum
                && mesh.channels.value.count == channels
        }
    }
}

func makeSnapshot(myNodeNum: Int32 = 111, roomId: Int32 = 42, channel: Int = 1) -> RadioSnapshot {
    var my = MyNodeInfo()
    my.myNodeNum = UInt32(bitPattern: myNodeNum)
    var settings = ChannelSettings()
    settings.name = "Camp"
    settings.id = UInt32(bitPattern: roomId)
    settings.psk = Data(repeating: 7, count: RoomCrypto.pskSize)
    settings.moduleSettings.positionPrecision = 32
    var room = Channel()
    room.index = Int32(channel)
    room.role = .secondary
    room.settings = settings
    var user = User()
    user.id = MeshConstants.formatNodeId(myNodeNum)
    user.longName = "Me"
    user.shortName = "ME"
    var node = NodeInfo()
    node.num = UInt32(bitPattern: myNodeNum)
    node.user = user
    var lora = Config.LoRaConfig()
    lora.hopLimit = 4
    var config = Config()
    config.lora = lora
    return RadioSnapshot(myInfo: my, channels: [Int32(channel): room], configs: [config], nodes: [myNodeNum: node])
}

func fromRadio(packet: MeshPacket) -> FromRadio {
    var from = FromRadio()
    from.packet = packet
    return from
}

func routingPacket(id: Int32, from node: Int32, error: Routing.Error = .none) throws -> FromRadio {
    var routing = Routing()
    routing.errorReason = error
    var data = DataMessage()
    data.portnum = .routingApp
    data.requestID = UInt32(bitPattern: id)
    data.payload = try routing.serializedData()
    var packet = MeshPacket()
    packet.from = UInt32(bitPattern: node)
    packet.decoded = data
    return fromRadio(packet: packet)
}

func queueStatus(id: Int32, res: Int32) -> FromRadio {
    var status = QueueStatus()
    status.meshPacketID = UInt32(bitPattern: id)
    status.res = res
    var from = FromRadio()
    from.queueStatus = status
    return from
}

func channelTextPacket(id: Int32, from node: Int32, text: String, channel: Int = 1) -> MeshPacket {
    var data = DataMessage()
    data.portnum = .textMessageApp
    data.payload = Data(text.utf8)
    var packet = MeshPacket()
    packet.id = UInt32(bitPattern: id)
    packet.from = UInt32(bitPattern: node)
    packet.to = UInt32(bitPattern: broadcastNodeNum)
    packet.channel = UInt32(channel)
    packet.rxTime = UInt32(Date().timeIntervalSince1970)
    packet.decoded = data
    return packet
}

@Test func aRemovedMemberCannotReadAnythingSentAfterTheRotation() throws {
    let roomId: Int32 = 0x51DE51DE
    let sender: Int32 = -211096906
    let oldKey = RoomCipher.generateKey()
    let newKey = RoomCipher.generateKey()
    let after = sealRoomText(
        "we have moved the meeting",
        roomId: roomId,
        sender: sender,
        key: newKey,
        generation: 2
    )
    #expect(openRoomText(after, roomId: roomId, sender: sender, keys: [1: oldKey]) == nil)
    #expect(
        openRoomText(after, roomId: roomId, sender: sender, keys: [1: oldKey, 2: newKey]) == "we have moved the meeting"
    )
}

/// Whoever stays holds both for a while after the move, so something sealed just before it still opens when the mesh
/// delivers it late. What they had already read is kept opened on the phone, not under these keys.
@Test func aMessageSealedJustBeforeTheRotationStillOpensForThoseWhoStayed() throws {
    let roomId: Int32 = 0x51DE51DE
    let sender: Int32 = -211096906
    let oldKey = RoomCipher.generateKey()
    let newKey = RoomCipher.generateKey()
    let before = sealRoomText(
        "meet at the north gate",
        roomId: roomId,
        sender: sender,
        key: oldKey,
        generation: 1
    )
    #expect(
        openRoomText(before, roomId: roomId, sender: sender, keys: [1: oldKey, 2: newKey]) == "meet at the north gate"
    )
}

@Test func aRemovedMemberKeepsWhatTheyAlreadyHad() throws {
    let roomId: Int32 = 0x51DE51DE
    let sender: Int32 = -211096906
    let oldKey = RoomCipher.generateKey()
    let before = sealRoomText(
        "meet at the north gate",
        roomId: roomId,
        sender: sender,
        key: oldKey,
        generation: 1
    )
    #expect(openRoomText(before, roomId: roomId, sender: sender, keys: [1: oldKey]) == "meet at the north gate")
}

@Test func aMessageSaysWhichGenerationSealedIt() throws {
    let payload = sealRoomText("anything", roomId: 2, sender: 3, key: RoomCipher.generateKey(), generation: 2)
    let outer = try Meshchat_MeshChatControl(serializedBytes: payload)
    #expect(outer.sealedMessage.generation == 2)
}

@Test func theNewKeyIsNotDerivableFromTheOldOne() throws {
    let roomId: Int32 = 0x51DE51DE
    let sender: Int32 = -211096906
    let oldKey = RoomCipher.generateKey()
    let newKey = RoomCipher.generateKey()
    let after = sealRoomText("after", roomId: roomId, sender: sender, key: newKey, generation: 2)
    #expect(openRoomText(after, roomId: roomId, sender: sender, keys: [2: oldKey]) == nil)
}

@Test func aDeadlineInTheFutureHasNotPassed() {
    let now: Int64 = 1_757_000_000_000
    #expect(!SharingDeadline(roomId: 42, choice: .hour, endsAt: now + 1).hasPassed(nowMillis: now))
}

@Test func aDeadlineIsReachedOnTheInstantNotAfterIt() {
    let now: Int64 = 1_757_000_000_000
    #expect(SharingDeadline(roomId: 42, choice: .hour, endsAt: now).hasPassed(nowMillis: now))
}

@Test func aDeadlineFromAPreviousRunHasPassed() {
    let now: Int64 = 1_757_000_000_000
    #expect(SharingDeadline(roomId: 42, choice: .hour, endsAt: now - 1).hasPassed(nowMillis: now + 86_400_000))
}

@Test func noDeadlineNeverPasses() {
    #expect(!SharingDeadline(roomId: 42, choice: .hour, endsAt: nil).hasPassed(nowMillis: Int64.max))
}

@Test func aReceiptSurvivesSealingAndOpening() throws {
    let receipt = Meshchat_Receipt.with {
        $0.roomID = 0x12345678
        $0.delivered = [1, 2, 3]
        $0.read = [4, 5]
    }
    let out = try unwireReceipt(wireReceipt(receipt))
    #expect(out?.delivered == [1, 2, 3])
    #expect(out?.read == [4, 5])
}

@Test func theWrongKeyOpensNothing() throws {
    let payload = try wireReceipt(Meshchat_Receipt.with { $0.roomID = 0x12345678 })
    let outer = try Meshchat_MeshChatControl(serializedBytes: payload)
    let opened = RoomCipher.open(
        key: Data(repeating: 0, count: RoomCipher.keySize),
        sealed: outer.sealedMessage.ciphertext,
        context: SealedText.contextOf(roomId: 0x12345678, senderNodeNum: -1181562854)
    )
    #expect(opened == nil)
}

@Test func aReceiptNamingAnotherRoomWillNotOpen() throws {
    let payload = try wireReceipt(Meshchat_Receipt.with { $0.roomID = 0x12345678 })
    let outer = try Meshchat_MeshChatControl(serializedBytes: payload)
    let opened = RoomCipher.open(
        key: receiptKey,
        sealed: outer.sealedMessage.ciphertext,
        context: SealedText.contextOf(roomId: 0x12345679, senderNodeNum: -1181562854)
    )
    #expect(opened == nil)
}

@Test func aFullBatchStillFitsOnePacket() throws {
    let ids = (1...ReceiptRules.maxIdsPerPacket).map { UInt32(bitPattern: Int32($0 * 7919)) }
    var receipt = Meshchat_Receipt()
    receipt.roomID = 0x12345678
    receipt.delivered = ids
    let payload = try wireReceipt(receipt)
    #expect(payload.count <= MeshConstants.dataPayloadLen)
    #expect(try unwireReceipt(payload) != nil)
}

@Test func aFullBatchOfReadReceiptsAlsoFits() throws {
    let ids = (1...ReceiptRules.maxIdsPerPacket).map { UInt32(bitPattern: Int32($0 * -7919)) }
    var receipt = Meshchat_Receipt()
    receipt.roomID = 0x12345678
    receipt.read = ids
    let payload = try wireReceipt(receipt)
    #expect(payload.count <= MeshConstants.dataPayloadLen)
    #expect(try unwireReceipt(payload)?.read == ids)
}

@Test func aMemberReadsTheWordsBack() throws {
    let key = RoomCipher.generateKey()
    let payload = sealRoomText("meet at the north gate", roomId: 0x0BADF00D, sender: -1181562854, key: key, replyId: 77)
    let out = openRoom(payload, roomId: 0x0BADF00D, sender: -1181562854, key: key)
    #expect(out?.text == "meet at the north gate")
    #expect(out?.replyID == 77)
}

@Test func theWordsAreNowhereInThePacket() throws {
    let key = RoomCipher.generateKey()
    let payload = sealRoomText("meet at the north gate", roomId: 0x0BADF00D, sender: -1181562854, key: key)
    #expect(!payload.contains(Data("north gate".utf8)))
}

@Test func aRadioHoldingOnlyTheChannelKeyReadsNothing() throws {
    let payload = sealRoomText("on my way", roomId: 0x0BADF00D, sender: -1181562854, key: RoomCipher.generateKey())
    #expect(openRoom(payload, roomId: 0x0BADF00D, sender: -1181562854, key: RoomCipher.generateKey()) == nil)
}

@Test func aMessageCannotBeReattributedToAnotherSender() throws {
    let key = RoomCipher.generateKey()
    let payload = sealRoomText("on my way", roomId: 0x0BADF00D, sender: -1181562854, key: key)
    #expect(openRoom(payload, roomId: 0x0BADF00D, sender: -1181562853, key: key) == nil)
}

@Test func theLongestMessageTheComposerAllowsStillFits() throws {
    let key = RoomCipher.generateKey()
    let text = String(repeating: "x", count: SealedText.maxTextBytes)
    let payload = sealRoomText(text, roomId: 0x0BADF00D, sender: -1181562854, key: key)
    #expect(payload.count <= MeshConstants.dataPayloadLen)
    #expect(openRoom(payload, roomId: 0x0BADF00D, sender: -1181562854, key: key)?.text == text)
}

@Test func theSameWordsNeverLookTheSameTwice() throws {
    let key = RoomCipher.generateKey()
    let first = sealRoomText("same", roomId: 0x0BADF00D, sender: -1181562854, key: key)
    let second = sealRoomText("same", roomId: 0x0BADF00D, sender: -1181562854, key: key)
    #expect(first != second)
}

@Test func startConsumingAConfigDownloadUpdatesSnapshotChannelsAndNodes() async throws {
    let h = try Harness()
    h.mesh.start()
    await h.connect(makeSnapshot())
    #expect(await eventually { h.mesh.myNodeNum.value == 111 })
    #expect(await eventually { h.mesh.channels.value.first?.id == 42 })
    #expect(await eventually { h.mesh.hopLimitForSending() == 4 })
    #expect(try await eventually { try await h.nodeDao.find(nodeNum: 111)?.longName == "Me" })
}

@Test func incomingPlainChannelTextIsStoredWithRightFields() async throws {
    let h = try Harness()
    h.mesh.start()
    await h.connect(makeSnapshot(roomId: 0))
    h.link.push(fromRadio(packet: channelTextPacket(id: 10, from: 222, text: "hello")))
    #expect(try await eventually { try await h.messageDao.find(id: 10) != nil })
    let stored = try await h.messageDao.find(id: 10)
    #expect(stored?.text == "hello")
    #expect(stored?.fromNodeNum == 222)
    #expect(stored?.status == .received)
}

@Test func incomingPlainTextOnPrimaryIsDropped() async throws {
    let h = try Harness()
    h.mesh.start()
    await h.connect(makeSnapshot())
    h.link.push(fromRadio(packet: channelTextPacket(id: 11, from: 222, text: "public", channel: 0)))
    try await Task.sleep(for: .milliseconds(30))
    #expect(try await h.messageDao.find(id: 11) == nil)
}

@Test func saveSealedTextStampsRoomIdAndSanitises() async throws {
    let h = try Harness()
    await h.mesh.saveSealedText(
        packet: channelTextPacket(id: 12, from: 222, text: "ignored"),
        text: " \u{0}secret\n ",
        replyId: 77,
        roomId: 42
    )
    let stored = try await h.messageDao.find(id: 12)
    #expect(stored?.text == "secret")
    #expect(stored?.roomId == 42)
    #expect(stored?.replyId == 77)
}

@Test func saveSealedTextIsNotStoredTwice() async throws {
    let h = try Harness()
    let packet = channelTextPacket(id: 13, from: 222, text: "ignored")
    await h.mesh.saveSealedText(packet: packet, text: "first", replyId: nil, roomId: 42)
    await h.mesh.saveSealedText(packet: packet, text: "second", replyId: nil, roomId: 42)
    #expect(try await h.messageDao.find(id: 13)?.text == "first")
}

@Test func saveSealedDirectTextIsKeptAgainstThePeer() async throws {
    let h = try Harness()
    var packet = channelTextPacket(id: 14, from: 222, text: "ignored", channel: 0)
    packet.to = UInt32(bitPattern: Int32(111))
    await h.mesh.saveSealedDirectText(packet: packet, text: "direct", replyId: nil)
    let stored = try await h.messageDao.find(id: 14)
    #expect(stored?.text == "direct")
    #expect(stored?.toNodeNum == 111)
    #expect(stored?.roomId == 0)
}

@Test func privateAppPacketStoresNoDataInMeshRepository() async throws {
    let h = try Harness()
    h.mesh.start()
    await h.connect(makeSnapshot())
    let payload = sealRoomText("secret", roomId: 42, sender: 222, key: RoomCipher.generateKey())
    h.link.push(fromRadio(packet: privatePacket(id: 15, from: 222, payload: payload, channel: 1)))
    try await Task.sleep(for: .milliseconds(40))
    #expect(try await h.messageDao.find(id: 15) == nil)
    #expect(try await h.peerKeyDao.find(nodeNum: 222) == nil)
    #expect(try await firstValue(h.personCardDao.observeAll()).isEmpty)
}

@Test func sendTextForRoomUsesPrivateAppWantAckAndHopLimit() async throws {
    let h = try Harness()
    _ = try h.roomKeys.generate(roomId: 42)
    h.mesh.start()
    await h.connect(makeSnapshot())
    try await h.mesh.sendText(channel: 1, text: "hi")
    let packet = h.link.sent.last?.packet
    #expect(packet?.decoded.portnum == .privateApp)
    #expect(packet?.channel == 1)
    #expect(packet?.wantAck == true)
    #expect(packet?.hopLimit == 4)
}

@Test func sentRoomPayloadIsSealedMessage() async throws {
    let h = try Harness()
    _ = try h.roomKeys.generate(roomId: 42)
    h.mesh.start()
    await h.connect(makeSnapshot())
    try await h.mesh.sendText(channel: 1, text: "hi")
    let control = try Meshchat_MeshChatControl(serializedBytes: h.link.sent.last!.packet.decoded.payload)
    #expect(control.sealedMessage.roomID == 42)
}

@Test func sendTextForDmRequiresPeerKey() async throws {
    let h = try Harness()
    h.mesh.start()
    await h.connect(makeSnapshot())
    await #expect(throws: SendError.noPeerKey) {
        try await h.mesh.sendText(channel: 1, text: "hi", to: 222)
    }
}

@Test func sendTextForDmUsesTextMessagePkiWhenOnlyRadioKeyExists() async throws {
    let h = try Harness()
    let radioKey = Data(repeating: 3, count: 32).base64EncodedString()
    try await h.nodeDao.save(node: MeshNode(nodeNum: 222, publicKey: radioKey), now: 1)
    h.mesh.start()
    await h.connect(makeSnapshot())
    try await h.mesh.sendText(channel: 1, text: "hi", to: 222)
    let packet = h.link.sent.last!.packet
    #expect(packet.decoded.portnum == .textMessageApp)
    #expect(packet.pkiEncrypted)
    #expect(packet.to == UInt32(bitPattern: Int32(222)))
}

@Test func queueStatusSuccessMovesQueuedToSentToNode() async throws {
    let h = try Harness()
    _ = try h.roomKeys.generate(roomId: 42)
    h.mesh.start()
    await h.connect(makeSnapshot())
    try await h.mesh.sendText(channel: 1, text: "hi")
    let id = Int32(bitPattern: h.link.sent.last!.packet.id)
    h.link.push(queueStatus(id: id, res: 0))
    #expect(try await eventually { try await h.messageDao.find(id: id)?.status == .sentToNode })
}

@Test func queueStatusRefusedMovesMessageToFailed() async throws {
    let h = try Harness()
    _ = try h.roomKeys.generate(roomId: 42)
    h.mesh.start()
    await h.connect(makeSnapshot())
    try await h.mesh.sendText(channel: 1, text: "hi")
    let id = Int32(bitPattern: h.link.sent.last!.packet.id)
    h.link.push(queueStatus(id: id, res: 2))
    #expect(try await eventually { try await h.messageDao.find(id: id)?.status == .failed })
}

@Test func implicitAckFromOurNodeMovesMessageToReachedMesh() async throws {
    let h = try Harness()
    _ = try h.roomKeys.generate(roomId: 42)
    h.mesh.start()
    await h.connect(makeSnapshot())
    try await h.mesh.sendText(channel: 1, text: "hi")
    let id = Int32(bitPattern: h.link.sent.last!.packet.id)
    h.link.push(try routingPacket(id: id, from: 111))
    #expect(try await eventually { try await h.messageDao.find(id: id)?.status == .reachedMesh })
}

@Test func ackFromDmRecipientMovesMessageToDelivered() async throws {
    let h = try Harness()
    let radioKey = Data(repeating: 3, count: 32).base64EncodedString()
    try await h.nodeDao.save(node: MeshNode(nodeNum: 222, publicKey: radioKey), now: 1)
    h.mesh.start()
    await h.connect(makeSnapshot())
    try await h.mesh.sendText(channel: 1, text: "hi", to: 222)
    let id = Int32(bitPattern: h.link.sent.last!.packet.id)
    h.link.push(try routingPacket(id: id, from: 222))
    #expect(try await eventually { try await h.messageDao.find(id: id)?.status == .delivered })
}

@Test func routingErrorMovesMessageToFailed() async throws {
    let h = try Harness()
    let radioKey = Data(repeating: 3, count: 32).base64EncodedString()
    try await h.nodeDao.save(node: MeshNode(nodeNum: 222, publicKey: radioKey), now: 1)
    h.mesh.start()
    await h.connect(makeSnapshot())
    try await h.mesh.sendText(channel: 1, text: "hi", to: 222)
    let id = Int32(bitPattern: h.link.sent.last!.packet.id)
    h.link.push(try routingPacket(id: id, from: 222, error: .noInterface))
    #expect(try await eventually { try await h.messageDao.find(id: id)?.status == .failed })
}

@Test func sendAwaitingAckReturnsTrueOnRecipientAck() async throws {
    let h = try Harness()
    h.mesh.start()
    try await Task.sleep(for: .milliseconds(30))
    var packet = try MeshPacketBuilder.meshPacket(to: 222, channel: 1, portNum: .adminApp, payload: Data([1]))
    packet.id = 77
    _ = Task {
        try? await Task.sleep(for: .milliseconds(20))
        h.link.push(try! routingPacket(id: 77, from: 222))
    }
    #expect(await h.mesh.sendAwaitingAck(packet: packet, from: 222, timeout: .seconds(1)))
}

@Test func sendAwaitingAckReturnsFalseOnTimeout() async throws {
    let h = try Harness()
    h.mesh.start()
    let packet = try MeshPacketBuilder.meshPacket(to: 222, channel: 1, portNum: .adminApp, payload: Data([1]))
    #expect(!(await h.mesh.sendAwaitingAck(packet: packet, from: 222, timeout: .milliseconds(20))))
}

@Test func duplicatePacketsAreNotStoredTwice() async throws {
    let h = try Harness()
    h.mesh.start()
    await h.connect(makeSnapshot(roomId: 0))
    let packet = channelTextPacket(id: 21, from: 222, text: "hello")
    h.link.push(fromRadio(packet: packet))
    h.link.push(fromRadio(packet: packet))
    #expect(try await eventually { try await h.messageDao.find(id: 21)?.text == "hello" })
}

@Test func clockSkewTrackingRecordsRadioClockDifference() async throws {
    let h = try Harness()
    h.mesh.start()
    var packet = channelTextPacket(id: 22, from: 222, text: "hello")
    packet.rxTime = UInt32(Date().timeIntervalSince1970 - 600)
    await h.connect(makeSnapshot(roomId: 0))
    h.link.push(fromRadio(packet: packet))
    #expect(await eventually { h.mesh.clockSkewMillis.value != nil })
}

@Test func channelLoadTracksOurOwnNodeUtilization() async throws {
    let h = try Harness()
    h.mesh.start()
    try await h.nodeDao.save(node: MeshNode(nodeNum: 111, channelUtilization: 60), now: 1)
    #expect(await eventually { h.mesh.channelLoad.value == .congested })
}

@Test func roomIdForChannelMapsFirepitRoom() async throws {
    let h = try Harness()
    h.mesh.start()
    await h.connect(makeSnapshot())
    #expect(await eventually { h.mesh.roomIdForChannel(1) == 42 })
}

@Test func isRoomSlotIsTrueForSecondaryConversation() async throws {
    let h = try Harness()
    h.mesh.start()
    await h.connect(makeSnapshot())
    #expect(await eventually { h.mesh.isRoomSlot(1) })
}

@Test func channelKeyOfReadsSnapshotPsk() async throws {
    let h = try Harness()
    h.mesh.start()
    await h.connect(makeSnapshot())
    #expect(await eventually { h.mesh.channelKeyOf(1) == .private })
}

@Test func refreshRoomKindsReflectsRememberedAndSupersededRoomKey() async throws {
    let h = try Harness()
    h.mesh.start()
    await h.connect(makeSnapshot())
    #expect(await eventually { h.mesh.channels.value.first?.kind == .firepitKeyMissing })
    try h.roomKeys.generate(roomId: 42)
    h.mesh.refreshRoomKinds()
    #expect(h.mesh.channels.value.first?.kind == .firepit)
    try h.roomKeys.markSuperseded(roomId: 42, generation: 2)
    h.mesh.refreshRoomKinds()
    #expect(h.mesh.channels.value.first?.kind == .firepitMovedOn)
}

@Test func setOwnPositionStoresPhoneFix() async throws {
    let h = try Harness()
    try await h.nodeDao.save(node: MeshNode(nodeNum: 111), now: 1)
    try await h.mesh.setOwnPosition(nodeNum: 111, latitudeI: 1, longitudeI: 2, altitude: 3, timeMillis: 4)
    let node = try await h.nodeDao.find(nodeNum: 111)
    #expect(node?.latitudeI == 1)
    #expect(node?.longitudeI == 2)
}

@Test func storeSealedPositionUsesFullPrecisionAndReplacesImplausibleTime() async throws {
    let h = try Harness()
    try await h.nodeDao.save(node: MeshNode(nodeNum: 222), now: 1)
    let before = currentEpochMillis()
    var position = Position()
    position.latitudeI = 10
    position.longitudeI = 20
    position.time = 1
    await h.mesh.storeSealedPosition(nodeNum: 222, position: position)
    let node = try await h.nodeDao.find(nodeNum: 222)
    #expect(node?.latitudeI == 10)
    #expect(node?.positionPrecision == PositionPrecision.full)
    #expect((node?.positionTime ?? 0) >= before)
}

@Test func storeSealedPositionIgnoresZeroZeroFix() async throws {
    let h = try Harness()
    var position = Position()
    position.latitudeI = 0
    position.longitudeI = 0
    await h.mesh.storeSealedPosition(nodeNum: 222, position: position)
    #expect(try await h.nodeDao.find(nodeNum: 222)?.latitudeI == nil)
}

@Test func setOwnNameMirrorsRenameIntoDatabase() async throws {
    let h = try Harness()
    try await h.nodeDao.save(node: MeshNode(nodeNum: 111, longName: "Old"), now: 1)
    try await h.mesh.setOwnName(nodeNum: 111, longName: "New", shortName: "NW")
    #expect(try await h.nodeDao.find(nodeNum: 111)?.longName == "New")
}

@Test func publicKeyOfReadsDatabaseFallback() async throws {
    let h = try Harness()
    let key = Data(repeating: 3, count: 32)
    try await h.nodeDao.save(node: MeshNode(nodeNum: 222, publicKey: key.base64EncodedString()), now: 1)
    #expect(await h.mesh.publicKeyOf(nodeNum: 222) == key)
}

@Test func phoneKeyOfReadsPeerKeyDao() async throws {
    let h = try Harness()
    let key = try PhoneKeyStore(store: InMemorySecretStore(), source: SoftwarePhoneKeySource()).publicKey()
    try await h.peerKeyDao.upsert(key: PeerKeyEntity(nodeNum: 222, phoneKey: key.base64EncodedString(), learnedAt: 1))
    #expect(await h.mesh.phoneKeyOf(nodeNum: 222) == key)
}

@Test func contactForBuildsUserFromStoredNode() async throws {
    let h = try Harness()
    let key = Data(repeating: 3, count: 32)
    try await h.nodeDao.save(
        node: MeshNode(nodeNum: 222, longName: "Ada", publicKey: key.base64EncodedString()),
        now: 1
    )
    #expect(await h.mesh.contactFor(nodeNum: 222)?.longName == "Ada")
}

@Test func markNotOpenedFailsMatchingOutgoingDirect() async throws {
    let h = try Harness()
    try await h.messageDao.save(
        message: ChatMessage(
            id: 99, channel: 0, fromNodeNum: 111, toNodeNum: 222, text: "x", sentAt: 1,
            status: .delivered, isOutgoing: true),
        myNodeNum: 111
    )
    #expect(await h.mesh.markNotOpened(messageId: 99, by: 222))
    #expect(try await h.messageDao.find(id: 99)?.status == .failed)
}

@Test func markNotOpenedIgnoresNonMatchingMessage() async throws {
    let h = try Harness()
    #expect(!(await h.mesh.markNotOpened(messageId: 123, by: 222)))
}

@Test func alertClientBuzzesOwnNodeWithLocalTextPacket() async throws {
    let h = try Harness()
    let alert = AlertClient(link: h.link, mesh: h.mesh)
    #expect(await alert.buzz(nodeNum: 111, timeout: .milliseconds(20)) == .delivered)
    #expect(h.link.sent.last?.packet.decoded.portnum == .textMessageApp)
}

@Test func alertClientReturnsNoKeyForUnknownPeer() async throws {
    let h = try Harness()
    let alert = AlertClient(link: h.link, mesh: h.mesh)
    #expect(await alert.buzz(nodeNum: 222, timeout: .milliseconds(20)) == .noKey)
}

@Test func nodeClockOffersWhenSkewExceedsTolerance() async throws {
    let h = try Harness()
    let admin = NodeAdminClient(link: h.link, repository: h.mesh)
    let clock = NodeClock(link: h.link, mesh: h.mesh, admin: admin)
    clock.start()
    await h.connect(makeSnapshot())
    h.mesh.clockSkewMillis.set(-180_000)
    #expect(await eventually { clock.offer.value?.behind == true })
}

@Test func nodeClockDismissHidesOffer() async throws {
    let h = try Harness()
    let admin = NodeAdminClient(link: h.link, repository: h.mesh)
    let clock = NodeClock(link: h.link, mesh: h.mesh, admin: admin)
    clock.start()
    await h.connect(makeSnapshot())
    h.mesh.clockSkewMillis.set(180_000)
    try await Task.sleep(for: .milliseconds(30))
    clock.dismiss()
    h.mesh.clockSkewMillis.set(181_000)
    try await Task.sleep(for: .milliseconds(30))
    #expect(clock.offer.value == nil)
}

@Test func ownerRepositoryRenameSendsSetOwnerAndMirrorsName() async throws {
    let h = try Harness()
    try await h.nodeDao.save(node: MeshNode(nodeNum: 111, longName: "Old"), now: 1)
    let admin = NodeAdminClient(link: h.link, repository: h.mesh)
    let owner = OwnerRepository(mesh: h.mesh, admin: admin)
    try await owner.rename(longName: "Ada Lovelace", shortName: "AL")
    let packet = h.link.sent.last!.packet
    let adminMessage = try AdminMessage(serializedBytes: packet.decoded.payload)
    #expect(adminMessage.setOwner.longName == "Ada Lovelace")
    #expect(try await h.nodeDao.find(nodeNum: 111)?.shortName == "AL")
}

@Test func nodeAdminSetTimeSendsAdminAppPacket() async throws {
    let h = try Harness()
    let admin = NodeAdminClient(link: h.link, repository: h.mesh)
    try await admin.setTime(epochSeconds: 123)
    #expect(h.link.sent.last?.packet.decoded.portnum == .adminApp)
}

@Test func nodeAdminSetOwnerCarriesUser() async throws {
    let h = try Harness()
    let admin = NodeAdminClient(link: h.link, repository: h.mesh)
    var user = User()
    user.longName = "Ada"
    try await admin.setOwner(user)
    let adminMessage = try AdminMessage(serializedBytes: h.link.sent.last!.packet.decoded.payload)
    #expect(adminMessage.setOwner.longName == "Ada")
}

@Test func nodeAdminSetChannelMirrorsRepositoryChannels() async throws {
    let h = try Harness()
    let admin = NodeAdminClient(link: h.link, repository: h.mesh)
    h.mesh.start()
    await h.connect(makeSnapshot())
    var channel = Channel()
    channel.index = 2
    channel.role = .secondary
    channel.settings.id = 55
    channel.settings.name = "Side"
    try await admin.setChannel(channel)
    #expect(await eventually { h.mesh.roomIdForChannel(2) == 55 })
}

@Test func nodeAdminSetFavoriteSendsNodeNumber() async throws {
    let h = try Harness()
    let admin = NodeAdminClient(link: h.link, repository: h.mesh)
    try await admin.setFavorite(nodeNum: 222)
    let adminMessage = try AdminMessage(serializedBytes: h.link.sent.last!.packet.decoded.payload)
    #expect(adminMessage.setFavoriteNode == 222)
}

@Test func nodeAdminAddContactCarriesSharedContact() async throws {
    let h = try Harness()
    let admin = NodeAdminClient(link: h.link, repository: h.mesh)
    var user = User()
    user.longName = "Peer"
    try await admin.addContact(nodeNum: 222, user: user)
    let adminMessage = try AdminMessage(serializedBytes: h.link.sent.last!.packet.decoded.payload)
    #expect(adminMessage.addContact.nodeNum == 222)
}

private func positionPacket(from node: Int32, latitudeI: Int32, precisionBits: UInt32) throws -> MeshPacket {
    var position = Position()
    position.latitudeI = latitudeI
    position.longitudeI = 20
    position.precisionBits = precisionBits
    var data = DataMessage()
    data.portnum = .positionApp
    data.payload = try position.serializedData()
    var packet = MeshPacket()
    packet.id = 88
    packet.from = UInt32(bitPattern: node)
    packet.decoded = data
    return packet
}

/// Kotlin's handlePosition: an unsealed position is kept for a node outside our rooms, where it is all there is.
@Test func anUnsealedPositionFromOutsideOurRoomsIsKept() async throws {
    let h = try Harness()
    h.mesh.start()
    try await h.nodeDao.save(node: MeshNode(nodeNum: 222), now: 1)
    h.link.push(fromRadio(packet: try positionPacket(from: 222, latitudeI: 10, precisionBits: 13)))
    #expect(try await eventually { try await h.nodeDao.find(nodeNum: 222)?.latitudeI == 10 })
    #expect(try await h.nodeDao.find(nodeNum: 222)?.positionPrecision == 13)
}

/// Never for a member: their phones only ever send positions sealed, so an unsealed one naming a member was put on the
/// air by whoever holds a radio.
@Test func anUnsealedPositionFromARoomMemberIsDropped() async throws {
    let h = try Harness()
    try await h.memberDao.record(roomId: 42, nodeNum: 333, now: 1)
    h.mesh.start()
    try await h.nodeDao.save(node: MeshNode(nodeNum: 333), now: 1)
    h.link.push(fromRadio(packet: try positionPacket(from: 333, latitudeI: 10, precisionBits: 32)))
    // A packet from a non-member pushed after it proves the first one has been handled.
    try await h.nodeDao.save(node: MeshNode(nodeNum: 222), now: 1)
    h.link.push(fromRadio(packet: try positionPacket(from: 222, latitudeI: 11, precisionBits: 32)))
    #expect(try await eventually { try await h.nodeDao.find(nodeNum: 222)?.latitudeI == 11 })
    #expect(try await h.nodeDao.find(nodeNum: 333)?.latitudeI == nil)
}

@Test func incomingTelemetryUpdatesMetrics() async throws {
    let h = try Harness()
    h.mesh.start()
    try await h.nodeDao.save(node: MeshNode(nodeNum: 222), now: 1)
    var metrics = DeviceMetrics()
    metrics.batteryLevel = 77
    metrics.channelUtilization = 55
    var telemetry = Telemetry()
    telemetry.deviceMetrics = metrics
    var data = DataMessage()
    data.portnum = .telemetryApp
    data.payload = try telemetry.serializedData()
    var packet = MeshPacket()
    packet.from = 222
    packet.decoded = data
    h.link.push(fromRadio(packet: packet))
    #expect(try await eventually { try await h.nodeDao.find(nodeNum: 222)?.batteryLevel == 77 })
}

@Test func receivedMessageBroadcastsIncomingOnlyOnce() async throws {
    let h = try Harness()
    h.mesh.start()
    await h.connect(makeSnapshot(roomId: 0))
    h.link.push(fromRadio(packet: channelTextPacket(id: 120, from: 222, text: "hello")))
    #expect(try await eventually { try await h.messageDao.find(id: 120)?.text == "hello" })
}

@Test func applyChannelWriteChangesChannelListWithoutReconnect() async throws {
    let h = try Harness()
    h.mesh.start()
    await h.connect(makeSnapshot())
    var channel = Channel()
    channel.index = 3
    channel.role = .secondary
    channel.settings.id = 77
    channel.settings.name = "Aux"
    h.mesh.applyChannelWrite(channel)
    #expect(h.mesh.roomIdForChannel(3) == 77)
}

let receiptKey = Data((0..<RoomCipher.keySize).map { UInt8($0) })

/// The hour the envelope-level tests seal in. They exercise the envelope with a key used as it stands; moving keys
/// on is RoomRatchetTests' business.
let envelopeHour = 491_234

func sealRoomText(
    _ text: String,
    roomId: Int32,
    sender: Int32,
    key: Data,
    generation: Int = 1,
    replyId: Int32 = 0
) -> Data {
    var roomText = Meshchat_RoomText()
    roomText.text = text
    roomText.replyID = UInt32(bitPattern: replyId)
    var inner = Meshchat_MeshChatControl()
    inner.roomText = roomText
    let sealed = SealedText.seal(
        key: key,
        hour: envelopeHour,
        plaintext: try! inner.serializedData(),
        context: SealedText.contextOf(roomId: roomId, senderNodeNum: sender)
    )
    var message = Meshchat_SealedMessage()
    message.roomID = UInt32(bitPattern: roomId)
    message.ciphertext = sealed
    message.generation = UInt32(generation)
    var outer = Meshchat_MeshChatControl()
    outer.sealedMessage = message
    return try! outer.serializedData()
}

func openRoomText(_ payload: Data, roomId: Int32, sender: Int32, keys: [Int: Data]) -> String? {
    guard let outer = try? Meshchat_MeshChatControl(serializedBytes: payload) else {
        return nil
    }
    let generation = outer.sealedMessage.generation == 0 ? 1 : Int(outer.sealedMessage.generation)
    guard let key = keys[generation],
        let plain = SealedText.open(
            key: key,
            payload: outer.sealedMessage.ciphertext,
            context: SealedText.contextOf(roomId: roomId, senderNodeNum: sender)
        ),
        let inner = try? Meshchat_MeshChatControl(serializedBytes: plain)
    else {
        return nil
    }
    return inner.roomText.text
}

func openRoom(_ payload: Data, roomId: Int32, sender: Int32, key: Data) -> Meshchat_RoomText? {
    guard let outer = try? Meshchat_MeshChatControl(serializedBytes: payload),
        let plain = SealedText.open(
            key: key,
            payload: outer.sealedMessage.ciphertext,
            context: SealedText.contextOf(roomId: roomId, senderNodeNum: sender)
        ),
        let inner = try? Meshchat_MeshChatControl(serializedBytes: plain)
    else {
        return nil
    }
    return inner.roomText
}

func wireReceipt(_ receipt: Meshchat_Receipt) throws -> Data {
    var inner = Meshchat_MeshChatControl()
    inner.receipt = receipt
    let sealed = RoomCipher.seal(
        key: receiptKey,
        plaintext: try inner.serializedData(),
        context: SealedText.contextOf(roomId: 0x12345678, senderNodeNum: -1181562854)
    )
    var message = Meshchat_SealedMessage()
    message.roomID = 0x12345678
    message.ciphertext = sealed
    var outer = Meshchat_MeshChatControl()
    outer.sealedMessage = message
    return try outer.serializedData()
}

func unwireReceipt(_ payload: Data) throws -> Meshchat_Receipt? {
    let outer = try Meshchat_MeshChatControl(serializedBytes: payload)
    guard
        let plain = RoomCipher.open(
            key: receiptKey,
            sealed: outer.sealedMessage.ciphertext,
            context: SealedText.contextOf(roomId: 0x12345678, senderNodeNum: -1181562854)
        )
    else {
        return nil
    }
    return try Meshchat_MeshChatControl(serializedBytes: plain).receipt
}

func privatePacket(id: Int32, from node: Int32, payload: Data, channel: Int) -> MeshPacket {
    var data = DataMessage()
    data.portnum = .privateApp
    data.payload = payload
    var packet = MeshPacket()
    packet.id = UInt32(bitPattern: id)
    packet.from = UInt32(bitPattern: node)
    packet.to = UInt32(bitPattern: broadcastNodeNum)
    packet.channel = UInt32(channel)
    packet.decoded = data
    return packet
}
