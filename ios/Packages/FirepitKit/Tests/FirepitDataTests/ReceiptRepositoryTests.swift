import FirepitCrypto
import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation
import Testing

@testable import FirepitData

private func receiptRepo(_ h: Harness) -> ReceiptRepository {
    ReceiptRepository(
        link: h.link, mesh: h.mesh, roomKeys: h.roomKeys, receiptDao: ReceiptDao(h.db),
        messageDao: h.messageDao, memberDao: h.memberDao, phoneKeys: h.phoneKeys)
}

private func startRoomHarness(_ h: Harness, roomKey: HourKey = hourKey(receiptKey)) async throws {
    try h.roomKeys.remember(roomId: 42, key: roomKey)
    h.mesh.start()
    await h.connect(makeSnapshot())
}

private func openedRoomReceipt(_ packet: MeshPacket, key: HourKey, sender: Int32) throws -> Meshchat_Receipt {
    let outer = try Meshchat_MeshChatControl(serializedBytes: packet.decoded.payload)
    let plain = try #require(openSealed(outer.sealedMessage, key: key, roomId: 42, sender: sender))
    return try Meshchat_MeshChatControl(serializedBytes: plain).receipt
}

@Test func receivedOnTrackedRoomBatchesUntilFlush() async throws {
    let h = try Harness()
    try await startRoomHarness(h)
    let receipts = receiptRepo(h)
    await receipts.received(channel: 1, messageId: 10)
    try await Task.sleep(for: .milliseconds(30))
    #expect(h.link.sent.isEmpty)
    try await receipts.flush()
    #expect(h.link.sent.last?.packet.decoded.portnum == .privateApp)
}

@Test func readOnTrackedRoomFlushesReadIds() async throws {
    let h = try Harness()
    try await startRoomHarness(h)
    let receipts = receiptRepo(h)
    await receipts.read(channel: 1, messageIds: [20, 21])
    try await receipts.flush()
    let receipt = try openedRoomReceipt(h.link.sent.last!.packet, key: hourKey(receiptKey), sender: 111)
    #expect(receipt.read.map { Int32(bitPattern: $0) } == [20, 21])
}

@Test func deliveredAndReadDoNotDuplicateTheSameId() async throws {
    let h = try Harness()
    try await startRoomHarness(h)
    let receipts = receiptRepo(h)
    await receipts.received(channel: 1, messageId: 30)
    await receipts.read(channel: 1, messageIds: [30])
    try await receipts.flush()
    let receipt = try openedRoomReceipt(h.link.sent.last!.packet, key: hourKey(receiptKey), sender: 111)
    #expect(receipt.delivered.isEmpty)
    #expect(receipt.read == [30])
}

@Test func untrackedMeshtasticChannelSendsNoReceipts() async throws {
    let h = try Harness()
    h.mesh.start()
    await h.connect(makeSnapshot(roomId: 0))
    let receipts = receiptRepo(h)
    await receipts.received(channel: 1, messageId: 40)
    try await receipts.flush()
    #expect(h.link.sent.isEmpty)
}

@Test func roomReceiptPacketUsesWantAckHopLimitAndBackgroundPriority() async throws {
    let h = try Harness()
    try await startRoomHarness(h)
    let receipts = receiptRepo(h)
    await receipts.received(channel: 1, messageId: 50)
    try await receipts.flush()
    let packet = h.link.sent.last!.packet
    #expect(packet.wantAck)
    #expect(packet.hopLimit == 4)
    #expect(packet.priority == .background)
}

@Test func roomReceiptCarriesRoomIdAndGeneration() async throws {
    let h = try Harness()
    try h.roomKeys.remember(roomId: 42, key: hourKey(), generation: 2)
    h.mesh.start()
    await h.connect(makeSnapshot())
    let receipts = receiptRepo(h)
    await receipts.received(channel: 1, messageId: 60)
    try await receipts.flush()
    let outer = try Meshchat_MeshChatControl(serializedBytes: h.link.sent.last!.packet.decoded.payload)
    #expect(outer.sealedMessage.roomID == 42)
    #expect(outer.sealedMessage.generation == 2)
}

@Test func directReceiptIsSealedDirectAndPkiEncrypted() async throws {
    let h = try Harness()
    let peerPhone = PhoneKeyStore(store: InMemorySecretStore(), source: SoftwarePhoneKeySource())
    try await h.peerKeyDao.upsert(
        key: PeerKeyEntity(nodeNum: 222, phoneKey: try peerPhone.publicKey().base64EncodedString(), learnedAt: 1)
    )
    h.mesh.start()
    await h.connect(snapshotWithPeerRadioKey(peer: 222))
    let receipts = receiptRepo(h)
    await receipts.received(channel: 1, messageId: 70, peer: 222)
    try await receipts.flush()
    let packet = h.link.sent.last!.packet
    #expect(packet.pkiEncrypted)
    #expect(packet.to == 222)
    #expect(packet.decoded.portnum == .privateApp)
    let outer = try Meshchat_MeshChatControl(serializedBytes: packet.decoded.payload)
    #expect(!outer.sealedDirect.ciphertext.isEmpty)
}

@Test func directReceiptOpensWithPeerPhoneKey() async throws {
    let h = try Harness()
    let peerPhone = PhoneKeyStore(store: InMemorySecretStore(), source: SoftwarePhoneKeySource())
    try await h.peerKeyDao.upsert(
        key: PeerKeyEntity(nodeNum: 222, phoneKey: try peerPhone.publicKey().base64EncodedString(), learnedAt: 1)
    )
    h.mesh.start()
    await h.connect(snapshotWithPeerRadioKey(peer: 222))
    let receipts = receiptRepo(h)
    await receipts.read(channel: 1, messageIds: [80], peer: 222)
    try await receipts.flush()
    let outer = try Meshchat_MeshChatControl(serializedBytes: h.link.sent.last!.packet.decoded.payload)
    let plain = peerPhone.openDirect(
        peerPublic: try h.phoneKeys.publicKey(),
        sealed: outer.sealedDirect.ciphertext,
        context: DirectSeal.contextOf(senderNodeNum: 111, recipientNodeNum: 222)
    )
    #expect(try Meshchat_MeshChatControl(serializedBytes: plain!).receipt.read == [80])
}

@Test func directReceiptWithoutPhoneKeySendsNothingAndDropsPending() async throws {
    let h = try Harness()
    h.mesh.start()
    await h.connect(snapshotWithPeerRadioKey(peer: 222))
    let receipts = receiptRepo(h)
    await receipts.received(channel: 1, messageId: 90, peer: 222)
    try await receipts.flush()
    #expect(h.link.sent.isEmpty)
}

@Test func handleStoresRoomReceiptOnlyFromMember() async throws {
    let h = try Harness()
    try await startRoomHarness(h)
    try await h.memberDao.record(roomId: 42, nodeNum: 222, now: 1)
    try await h.messageDao.save(
        message: sampleMessage(
            id: 100, channel: 1, to: broadcastNodeNum,
            outgoing: true, roomId: 42), myNodeNum: 111)
    var receipt = Meshchat_Receipt()
    receipt.delivered = [100]
    await receiptRepo(h).handle(from: 222, receipt: receipt, at: 9)
    #expect(try await firstValue(ReceiptDao(h.db).observe(messageId: 100)).first?.nodeNum == 222)
}

@Test func handleRejectsRoomReceiptFromStranger() async throws {
    let h = try Harness()
    try await startRoomHarness(h)
    try await h.messageDao.save(
        message: sampleMessage(
            id: 110, channel: 1, to: broadcastNodeNum,
            outgoing: true, roomId: 42), myNodeNum: 111)
    var receipt = Meshchat_Receipt()
    receipt.read = [110]
    await receiptRepo(h).handle(from: 333, receipt: receipt, at: 9)
    #expect(try await firstValue(ReceiptDao(h.db).observe(messageId: 110)).isEmpty)
}

@Test func handleRejectsDirectReceiptFromNonRecipient() async throws {
    let h = try Harness()
    try await h.messageDao.save(
        message: sampleMessage(id: 120, channel: 1, to: 222, outgoing: true),
        myNodeNum: 111)
    var receipt = Meshchat_Receipt()
    receipt.read = [120]
    await receiptRepo(h).handle(from: 333, receipt: receipt, at: 9)
    #expect(try await firstValue(ReceiptDao(h.db).observe(messageId: 120)).isEmpty)
}

@Test func observeAllGroupsReceiptsByMessage() async throws {
    let h = try Harness()
    try await h.messageDao.save(message: sampleMessage(id: 130, outgoing: true), myNodeNum: 111)
    try await h.messageDao.save(message: sampleMessage(id: 131, outgoing: true), myNodeNum: 111)
    try await ReceiptDao(h.db).recordReceived(messageId: 130, nodeNum: 222, at: 1)
    try await ReceiptDao(h.db).recordRead(messageId: 131, nodeNum: 333, at: 2)
    let grouped = try await firstValue(receiptRepo(h).observeAll(messageIds: [130, 131]))
    #expect(grouped[130]?.first?.state == .received)
    #expect(grouped[131]?.first?.state == .read)
}

private func snapshotWithPeerRadioKey(peer: Int32) -> RadioSnapshot {
    var snapshot = makeSnapshot(roomId: 0)
    var user = User()
    user.id = MeshConstants.formatNodeId(peer)
    user.publicKey = Data(repeating: 4, count: TrustRules.radioKeySize)
    var node = NodeInfo()
    node.num = UInt32(bitPattern: peer)
    node.user = user
    snapshot.nodes[peer] = node
    return snapshot
}
