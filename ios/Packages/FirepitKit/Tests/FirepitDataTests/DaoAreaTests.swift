import FirepitData
import FirepitModel
import Foundation
import GRDB
import Testing

@Test func messageObserveChannelEmitsInsertedRows() async throws {
    let queue = try makeDatabase()
    let dao = MessageDao(queue)
    try await dao.save(message: sampleMessage(id: 11, channel: 2), myNodeNum: 99)
    let rows = try await firstValue(dao.observeChannel(channel: 2))
    #expect(rows.map(\.id) == [11])
}

@Test func messageObserveDirectUsesPeerNode() async throws {
    let queue = try makeDatabase()
    let dao = MessageDao(queue)
    try await dao.save(message: sampleMessage(id: 12, channel: 0, from: 7, to: 99), myNodeNum: 99)
    let rows = try await firstValue(dao.observeDirect(peer: 7))
    #expect(rows.map(\.id) == [12])
}

@Test func messageDirectLatestPicksNewestPerPeer() async throws {
    let queue = try makeDatabase()
    let dao = MessageDao(queue)
    try await dao.save(message: sampleMessage(id: 13, channel: 0, from: 7, to: 99, sentAt: 1), myNodeNum: 99)
    try await dao.save(message: sampleMessage(id: 14, channel: 0, from: 7, to: 99, sentAt: 2), myNodeNum: 99)
    let rows = try await firstValue(dao.directLatest())
    #expect(rows.map(\.id) == [14])
}

@Test func messageLatestPerChannelPicksNewestBroadcast() async throws {
    let queue = try makeDatabase()
    let dao = MessageDao(queue)
    try await dao.save(message: sampleMessage(id: 15, channel: 3, sentAt: 1), myNodeNum: 99)
    try await dao.save(message: sampleMessage(id: 16, channel: 3, sentAt: 3), myNodeNum: 99)
    let rows = try await firstValue(dao.latestPerChannel())
    #expect(rows.map(\.id) == [16])
}

@Test func reactionsAreNotPreviewsOrUnread() async throws {
    let queue = try makeDatabase()
    let dao = MessageDao(queue)
    let states = ChannelStateDao(queue)
    try await dao.save(message: sampleMessage(id: 30, channel: 3, sentAt: 10), myNodeNum: 99)
    try await dao.save(
        message: ChatMessage(
            id: 31, channel: 3, fromNodeNum: 11, toNodeNum: broadcastNodeNum, text: "👍", sentAt: 20,
            status: .received, replyId: 30, emoji: 1),
        myNodeNum: 99)
    try await dao.save(message: sampleMessage(id: 32, channel: 0, from: 7, to: 99, sentAt: 10), myNodeNum: 99)
    try await dao.save(
        message: ChatMessage(
            id: 33, channel: 0, fromNodeNum: 7, toNodeNum: 99, text: "❤️", sentAt: 20, status: .received,
            replyId: 32, emoji: 1),
        myNodeNum: 99)
    try await states.markRead(channel: 3, now: 5)

    #expect(try await firstValue(dao.latestPerChannel()).map(\.id) == [30])
    #expect(try await firstValue(dao.directLatest()).map(\.id) == [32])
    #expect(try await firstValue(states.observeUnread()).contains(UnreadCount(channel: 3, count: 1)))
}

@Test func messageDeleteOlderThanReturnsCount() async throws {
    let queue = try makeDatabase()
    let dao = MessageDao(queue)
    try await dao.save(message: sampleMessage(id: 17, sentAt: 1), myNodeNum: 99)
    try await dao.save(message: sampleMessage(id: 18, sentAt: 10), myNodeNum: 99)
    #expect(try await dao.deleteOlderThan(cutoff: 5) == 1)
    #expect(try await dao.find(id: 17) == nil)
}

@Test func messageUpdateStatusStoresAndroidName() async throws {
    let queue = try makeDatabase()
    let dao = MessageDao(queue)
    try await dao.save(message: sampleMessage(id: 19, status: .queued), myNodeNum: 99)
    try await dao.updateStatus(id: 19, status: .delivered, reason: nil)
    #expect(try await dao.find(id: 19)?.status == .delivered)
}

@Test func messagePendingOutgoingFiltersStatuses() async throws {
    let queue = try makeDatabase()
    let dao = MessageDao(queue)
    try await dao.save(message: sampleMessage(id: 20, to: 7, status: .queued, outgoing: true), myNodeNum: 99)
    try await dao.save(message: sampleMessage(id: 21, to: 7, status: .delivered, outgoing: true), myNodeNum: 99)
    let rows = try await dao.pendingOutgoing(pending: [.queued])
    #expect(rows.map(\.id) == [20])
}

@Test func messageStampSlotAssignsRoomId() async throws {
    let queue = try makeDatabase()
    let dao = MessageDao(queue)
    try await dao.save(message: sampleMessage(id: 22, channel: 4), myNodeNum: 99)
    try await dao.stampSlot(slot: 4, roomId: 44)
    #expect(try await dao.find(id: 22)?.roomId == 44)
}

@Test func messageParkOtherRoomsLeavesKeptRoom() async throws {
    let queue = try makeDatabase()
    let dao = MessageDao(queue)
    try await dao.save(message: sampleMessage(id: 23, channel: 5, roomId: 50), myNodeNum: 99)
    try await dao.save(message: sampleMessage(id: 24, channel: 5, roomId: 51), myNodeNum: 99)
    try await dao.parkOtherRooms(slot: 5, keep: 50)
    #expect(try await dao.find(id: 23)?.channel == 5)
    #expect(try await dao.find(id: 24)?.channel == PARKED_CHANNEL)
}

@Test func messageMoveUnfiledOnlyMovesRoomZero() async throws {
    let queue = try makeDatabase()
    let dao = MessageDao(queue)
    try await dao.save(message: sampleMessage(id: 25, channel: 6), myNodeNum: 99)
    try await dao.save(message: sampleMessage(id: 26, channel: 6, roomId: 60), myNodeNum: 99)
    try await dao.moveUnfiled(from: 6, to: 7)
    #expect(try await dao.find(id: 25)?.channel == 7)
    #expect(try await dao.find(id: 26)?.channel == 6)
}

@Test func messageDeleteUnfiledOnlyDeletesRoomZero() async throws {
    let queue = try makeDatabase()
    let dao = MessageDao(queue)
    try await dao.save(message: sampleMessage(id: 27, channel: 1), myNodeNum: 99)
    try await dao.save(message: sampleMessage(id: 28, channel: 1, roomId: 10), myNodeNum: 99)
    try await dao.deleteUnfiled(slot: 1)
    #expect(try await dao.find(id: 27) == nil)
    #expect(try await dao.find(id: 28) != nil)
}

@Test func messageDomainConversionPreservesOptionalFields() async throws {
    let message = ChatMessage(
        id: 29,
        channel: 1,
        fromNodeNum: 1,
        toNodeNum: broadcastNodeNum,
        text: "x",
        sentAt: 1,
        rxTime: 2,
        status: .received,
        isOutgoing: false,
        replyId: 3,
        emoji: 4,
        signed: true,
        roomId: 5
    )
    let entity = MessageEntity.fromDomain(message, myNodeNum: 9)
    #expect(entity.toDomain() == message)
}

@Test func nodeUpdateMetricsKeepsPreviousWhenNil() async throws {
    let queue = try makeDatabase()
    let dao = NodeDao(queue)
    try await dao.save(node: MeshNode(nodeNum: 1, batteryLevel: 50, isFavorite: false), now: 1)
    try await dao.updateMetrics(nodeNum: 1, batteryLevel: nil, voltage: 3.9, channelUtilization: nil, airUtilTx: nil)
    let node = try await dao.find(nodeNum: 1)
    #expect(node?.batteryLevel == 50)
    #expect(node?.voltage == 3.9)
}

@Test func nodeForgetPositionsClearsOldFixes() async throws {
    let queue = try makeDatabase()
    let dao = NodeDao(queue)
    try await dao.save(node: MeshNode(nodeNum: 2, isFavorite: false, latitudeI: 1, positionTime: 1), now: 1)
    #expect(try await dao.forgetPositionsBefore(cutoff: 2) == 1)
    #expect(try await dao.find(nodeNum: 2)?.latitudeI == nil)
}

@Test func nodeForgetStrangersKeepsRoomMembers() async throws {
    let queue = try makeDatabase()
    let nodes = NodeDao(queue)
    let members = RoomMemberDao(queue)
    try await nodes.save(node: MeshNode(nodeNum: 3, lastHeard: 1, isFavorite: false), now: 1)
    try await members.record(roomId: 9, nodeNum: 3, now: 1)
    #expect(try await nodes.forgetStrangersBefore(cutoff: 2, keep: 99) == 0)
}

@Test func mapPinTombstonesRoundTrip() async throws {
    let queue = try makeDatabase()
    let dao = MapPinDao(queue)
    try await dao.remember(deleted: DeletedPinEntity(id: 1, channel: 2, deletedAt: 10))
    #expect(try await dao.wasDeleted(id: 1))
    #expect(try await dao.recentlyDeleted(since: 5).map(\.id) == [1])
}

@Test func mapPinDeleteExpiredIgnoresNeverExpire() async throws {
    let queue = try makeDatabase()
    let dao = MapPinDao(queue)
    try await dao.save(
        pin: MapPin(
            id: 1,
            channel: 1,
            latitudeI: 1,
            longitudeI: 1,
            name: "a",
            expire: 0,
            createdBy: 1,
            receivedAt: 1
        ))
    try await dao.save(
        pin: MapPin(
            id: 2,
            channel: 1,
            latitudeI: 1,
            longitudeI: 1,
            name: "b",
            expire: 1,
            createdBy: 1,
            receivedAt: 1
        ))
    #expect(try await dao.deleteExpired(nowSeconds: 2) == 1)
    #expect(try await dao.find(id: 1) != nil)
}

@Test func channelStateMutedSetContainsMutedSlots() async throws {
    let queue = try makeDatabase()
    let dao = ChannelStateDao(queue)
    try await dao.setMuted(channel: 2, muted: true)
    #expect(try await firstValue(dao.observeMuted()) == [2])
}

@Test func channelStateFollowRoomResetsDifferentRoom() async throws {
    let queue = try makeDatabase()
    let dao = ChannelStateDao(queue)
    try await dao.markRead(channel: 2, now: 100, roomId: 1)
    try await dao.followRoom(channel: 2, roomId: 2, now: 200, roomMuted: true)
    #expect(try await dao.find(channel: 2) == ChannelStateEntity(channel: 2, lastReadAt: 200, muted: true, roomId: 2))
}

@Test func roomMemberNodeNumsAndAnyRoom() async throws {
    let queue = try makeDatabase()
    let dao = RoomMemberDao(queue)
    try await dao.record(roomId: 1, nodeNum: 2, now: 3)
    #expect(try await dao.nodeNumsIn(roomId: 1) == [2])
    #expect(try await dao.isInAnyRoom(nodeNum: 2))
}

@Test func roomMemberObserveAllNodeNumsDistinct() async throws {
    let queue = try makeDatabase()
    let dao = RoomMemberDao(queue)
    try await dao.record(roomId: 1, nodeNum: 2, now: 3)
    try await dao.record(roomId: 2, nodeNum: 2, now: 4)
    #expect(try await firstValue(dao.observeAllNodeNums()) == [2])
}

@Test func receiptBeforeMessageIsIgnored() async throws {
    let queue = try makeDatabase()
    let dao = ReceiptDao(queue)
    try await dao.recordReceived(messageId: 404, nodeNum: 1, at: 1)
    #expect(try await firstValue(dao.observe(messageId: 404)).isEmpty)
}

@Test func receiptReadIsStrongerThanReceived() async throws {
    let queue = try makeDatabase()
    let messages = MessageDao(queue)
    let receipts = ReceiptDao(queue)
    try await messages.save(message: sampleMessage(id: 30), myNodeNum: 99)
    try await receipts.recordReceived(messageId: 30, nodeNum: 1, at: 1)
    try await receipts.recordRead(messageId: 30, nodeNum: 1, at: 2)
    #expect(try await firstValue(receipts.observe(messageId: 30)).first?.state == .read)
}

@Test func personCardsForgetOutsideRooms() async throws {
    let queue = try makeDatabase()
    let cards = PersonCardDao(queue)
    try await cards.upsert(card: PersonCardEntity(nodeNum: 1, name: "A", tag: "AA", colourSlot: nil, updatedAt: 1))
    #expect(try await cards.forgetOutsideRooms() == 1)
}

@Test func personCardsObserveAllMapsByNode() async throws {
    let queue = try makeDatabase()
    let cards = PersonCardDao(queue)
    try await cards.upsert(card: PersonCardEntity(nodeNum: 1, name: "A", tag: "AA", colourSlot: 2, updatedAt: 1))
    #expect(try await firstValue(cards.observeAll())[1]?.tag == "AA")
}

@Test func peerKeyKnownObservationChangesWithInsert() async throws {
    let queue = try makeDatabase()
    let keys = PeerKeyDao(queue)
    try await keys.upsert(key: PeerKeyEntity(nodeNum: 1, phoneKey: "abc", learnedAt: 1))
    #expect(try await firstValue(keys.observeKnown(nodeNum: 1)))
}

@Test func peerKeyForgetOutsideRoomsRemovesStrangers() async throws {
    let queue = try makeDatabase()
    let keys = PeerKeyDao(queue)
    try await keys.upsert(key: PeerKeyEntity(nodeNum: 1, phoneKey: "abc", learnedAt: 1))
    #expect(try await keys.forgetOutsideRooms() == 1)
}

@Test func roomActivityJoinedOnlyStartsOnce() async throws {
    let queue = try makeDatabase()
    let dao = RoomActivityDao(queue)
    try await dao.joined(roomId: 1, now: 10)
    try await dao.joined(roomId: 1, now: 20)
    #expect(try await dao.all().first?.joinedAt == 10)
}

@Test func roomActivityTouchUsesMax() async throws {
    let queue = try makeDatabase()
    let dao = RoomActivityDao(queue)
    try await dao.recordActivity(roomId: 1, now: 10)
    try await dao.touch(roomId: 1, now: 5)
    #expect(try await dao.all().first?.lastActivityAt == 10)
}

@Test func pendingHandoverQueriesByNodeAndRoom() async throws {
    let queue = try makeDatabase()
    let dao = PendingHandoverDao(queue)
    let handover = PendingHandoverEntity(
        roomId: 1,
        nodeNum: 2,
        generation: 3,
        heldGeneration: 2,
        removed: "4",
        createdAt: 5,
        lastTriedAt: 6
    )
    try await dao.upsert(handover: handover)
    #expect(try await dao.forNode(nodeNum: 2) == [handover])
    #expect(try await dao.forRoom(roomId: 1) == [handover])
}

@Test func pendingHandoverDeleteRoomClearsAllRoomRows() async throws {
    let queue = try makeDatabase()
    let dao = PendingHandoverDao(queue)
    try await dao.upsert(
        handover: PendingHandoverEntity(
            roomId: 1,
            nodeNum: 2,
            generation: 3,
            heldGeneration: 2,
            removed: "",
            createdAt: 1,
            lastTriedAt: 1
        ))
    try await dao.deleteRoom(roomId: 1)
    #expect(try await dao.all().isEmpty)
}
