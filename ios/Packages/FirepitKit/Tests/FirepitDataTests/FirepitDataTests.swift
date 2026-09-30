import FirepitCrypto
import FirepitData
import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation
import GRDB
import SwiftProtobuf
import Testing

private func db() throws -> DatabaseQueue { try FirepitDatabase.inMemory() }

@Test func schemaMatchesAndroidV12Shape() throws {
    let queue = try db()
    let tables = try queue.read { db in
        try String.fetchAll(
            db, sql: "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'grdb_%' ORDER BY name")
    }
    #expect(
        tables.contains([
            "channel_state", "deleted_pins", "map_pins", "messages", "nodes", "peer_keys", "pending_handovers",
            "person_cards", "receipts", "room_activity", "room_master_table", "room_members",
        ]))
    let messageColumns = try queue.read { db in
        try Row.fetchAll(db, sql: "PRAGMA table_info(messages)").map { $0["name"] as String }
    }
    #expect(
        messageColumns == [
            "id", "channel", "fromNodeNum", "toNodeNum", "peerNodeNum", "text", "sentAt", "rxTime", "status",
            "failureReason", "isOutgoing", "rxSnr", "rxRssi", "hopsAway", "replyId", "emoji", "signed", "roomId",
        ])
    let receiptForeignKeys = try queue.read { db in try Row.fetchAll(db, sql: "PRAGMA foreign_key_list(receipts)") }
    #expect(receiptForeignKeys.count == 1)
    #expect(receiptForeignKeys[0]["on_delete"] as String == "CASCADE")
    let indices = try queue.read { db in
        try String.fetchAll(db, sql: "SELECT name FROM sqlite_master WHERE type = 'index' ORDER BY name")
    }
    #expect(indices.contains("index_messages_channel_sentAt"))
    #expect(indices.contains("index_messages_peerNodeNum_sentAt"))
    #expect(indices.contains("index_messages_roomId"))
    #expect(indices.contains("index_receipts_messageId"))
}

@Test func messageDaoQueriesSlotsUnreadAndReceiptsCascade() async throws {
    let queue = try db()
    let messages = MessageDao(queue)
    let states = ChannelStateDao(queue)
    let receipts = ReceiptDao(queue)
    let first = ChatMessage(
        id: 1, channel: 1, fromNodeNum: 42, toNodeNum: broadcastNodeNum, text: "hello", sentAt: 10, status: .received,
        roomId: 100)
    let direct = ChatMessage(
        id: 2, channel: 0, fromNodeNum: 7, toNodeNum: 9, text: "dm", sentAt: 12, status: .received, roomId: 0)
    #expect(try await messages.saveIfNew(message: first, myNodeNum: 9))
    #expect(!(try await messages.saveIfNew(message: first, myNodeNum: 9)))
    try await messages.save(message: direct, myNodeNum: 9)
    #expect(try await messages.find(id: 1)?.status == .received)
    #expect(try await messages.newestPerChannel() == [1: 10])
    try await states.markRead(channel: 1, now: 5, roomId: 100)
    let unread = try await firstValue(states.observeUnread())
    #expect(unread.contains(UnreadCount(channel: 1, count: 1)))
    try await receipts.recordReceived(messageId: 1, nodeNum: 42, at: 11)
    try await receipts.recordRead(messageId: 1, nodeNum: 42, at: 12)
    #expect(try await firstValue(receipts.observe(messageId: 1)) == [Receipt(nodeNum: 42, state: .read, at: 12)])
    try await messages.placeRoom(roomId: 100, slot: 3)
    #expect(try await messages.find(id: 1)?.channel == 3)
    try await messages.parkUnfiled(slot: 0)
    try await messages.restoreUnfiled(slot: 0)
    try await messages.deleteRoom(roomId: 100)
    #expect(try await firstValue(receipts.observe(messageId: 1)).isEmpty)
}

@Test func nodeMemberPinAndActivityDaosKeepAndroidSemantics() async throws {
    let queue = try db()
    let nodes = NodeDao(queue)
    let members = RoomMemberDao(queue)
    let pins = MapPinDao(queue)
    let activity = RoomActivityDao(queue)
    let node = MeshNode(nodeNum: 5, longName: "A", isUnmessagable: false, lastHeard: 1, isFavorite: false, latitudeI: 1)
    try await nodes.save(node: node, now: 100)
    try await nodes.save(
        node: MeshNode(nodeNum: 5, longName: "B", isUnmessagable: false, lastHeard: 2, isFavorite: true), now: 200)
    #expect(try await nodes.find(nodeNum: 5)?.latitudeI == 1)
    try await members.recordReported(roomId: 77, nodeNum: 5, now: 10, invitedBy: 9)
    #expect(try await members.findEntity(roomId: 77, nodeNum: 5)?.lastHeard == nil)
    try await members.record(roomId: 77, nodeNum: 5, now: 20)
    #expect(try await members.findEntity(roomId: 77, nodeNum: 5)?.invitedBy == 9)
    let pin = MapPin(
        id: 10, channel: 1, latitudeI: 11, longitudeI: 22, name: "P", createdBy: 5, receivedAt: 30, roomId: 77)
    try await pins.save(pin: pin)
    try await pins.placeRoom(roomId: 77, slot: 2)
    #expect(try await pins.find(id: 10)?.channel == 2)
    try await activity.recordActivity(roomId: 77, now: 40)
    try await activity.setMuted(roomId: 77, muted: true, now: 41)
    #expect(try await activity.isMuted(roomId: 77))
}

@Test func phoneKeyStoreSealsAndOpensWithFirepitCrypto() async throws {
    let alice = PhoneKeyStore(store: InMemorySecretStore(), source: SoftwarePhoneKeySource())
    let bob = PhoneKeyStore(store: InMemorySecretStore(), source: SoftwarePhoneKeySource())
    let alicePublic = try alice.publicKey()
    let bobPublic = try bob.publicKey()
    let context = DirectSeal.contextOf(senderNodeNum: 1, recipientNodeNum: 2)
    let sealed = try alice.sealDirect(peerPublic: bobPublic, plaintext: Data("secret".utf8), context: context)
    #expect(bob.openDirect(peerPublic: alicePublic, sealed: sealed, context: context) == Data("secret".utf8))
    let envelopeContext = KeyEnvelope.contextOf(roomId: 10, generation: 1, recipientNodeNum: 2)
    let roomKey = RoomCipher.generateKey()
    let envelope = try KeyEnvelope.seal(recipient: bobPublic, secret: roomKey, context: envelopeContext)
    #expect(bob.open(sealed: envelope, context: envelopeContext) == roomKey)
}

@Test func roomKeyStoreAndPrimaryBackupRoundTrip() async throws {
    let secrets = InMemorySecretStore()
    let rooms = RoomKeyStore(store: secrets)
    let key = try rooms.generate(roomId: 123, generation: 1)
    #expect(rooms.keyFor(roomId: 123) == key)
    try rooms.markSuperseded(roomId: 123, generation: 2)
    #expect(rooms.sealingKey(roomId: 123) == nil)
    let next = RoomCipher.generateKey()
    try rooms.remember(roomId: 123, key: next, generation: 2)
    #expect(rooms.sealingKey(roomId: 123) == next)
    let backup = PrimaryBackup(store: secrets)
    var channel = Channel()
    channel.settings.name = "old"
    channel.settings.psk = Data([1, 2, 3])
    try backup.remember(nodeNum: 1, channel: channel)
    #expect(backup.saved(nodeNum: 1)?.settings.name == "old")
    #expect(channel.carriesAKey())
}

@Test func sessionAndSharingStoresPersistDefaults() {
    let suite = "FirepitDataTests.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defer { defaults.removePersistentDomain(forName: suite) }
    let session = SessionStore(defaults: defaults)
    #expect(session.myNodeNum == nil)
    #expect(session.lastRadioId == nil)
    #expect(!session.historyFiledByRoom)
    session.myNodeNum = 99
    session.lastRadioId = "radio"
    session.historyFiledByRoom = true
    let sessionAgain = SessionStore(defaults: defaults)
    #expect(sessionAgain.myNodeNum == 99)
    #expect(sessionAgain.lastRadioId == "radio")
    #expect(sessionAgain.historyFiledByRoom)
    let sharing = SharingStore(defaults: defaults)
    #expect(sharing.deadline.value == nil)
    sharing.remember(roomId: 55, choice: .hour, nowMillis: 1_000)
    #expect(sharing.deadline.value == SharingDeadline(roomId: 55, choice: .hour, endsAt: 3_601_000))
    sharing.clear()
    #expect(sharing.deadline.value == nil)
}
