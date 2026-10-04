import FirepitModel
import Foundation
import GRDB
import os

private let daoLog = Logger(subsystem: "com.getfirepit.app", category: "FirepitData")
public let PARKED_CHANNEL = -1
private let UNFILED_PARKING_BASE = -100
public func unfiledParkingFor(slot: Int) -> Int { UNFILED_PARKING_BASE - slot }

private func stream<T: Sendable>(_ writer: any DatabaseWriter, _ fetch: @escaping @Sendable (Database) throws -> T)
    -> AsyncStream<T>
{
    AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
        let observation = ValueObservation.tracking(fetch)
        let cancellable = observation.start(in: writer, scheduling: .async(onQueue: DispatchQueue.global())) { error in
            daoLog.error("database observation ended: \(String(describing: error), privacy: .public)")
            continuation.finish()
        } onChange: { value in
            continuation.yield(value)
        }
        continuation.onTermination = { _ in cancellable.cancel() }
    }
}

public struct MessageDao: Sendable {
    private let writer: any DatabaseWriter
    public init(_ writer: any DatabaseWriter) { self.writer = writer }

    public func observeChannelEntities(channel: Int, broadcast: Int32) -> AsyncStream<[MessageEntity]> {
        stream(writer) { db in
            try MessageEntity.fetchAll(
                db, sql: "SELECT * FROM messages WHERE channel = ? AND toNodeNum = ? ORDER BY sentAt ASC",
                arguments: [channel, broadcast])
        }
    }
    public func observeDirectEntities(peer: Int32, broadcast: Int32) -> AsyncStream<[MessageEntity]> {
        stream(writer) { db in
            try MessageEntity.fetchAll(
                db, sql: "SELECT * FROM messages WHERE peerNodeNum = ? AND toNodeNum != ? ORDER BY sentAt ASC",
                arguments: [peer, broadcast])
        }
    }
    /// Newest message per person, for the Direct list. A reaction is not a message there (UX §5.4).
    public func observeDirectLatest(broadcast: Int32) -> AsyncStream<[MessageEntity]> {
        stream(writer) { db in
            try MessageEntity.fetchAll(
                db,
                sql: """
                    SELECT * FROM messages WHERE id IN (
                        SELECT id FROM messages WHERE toNodeNum != ?
                        AND (COALESCE(emoji, 0) = 0 OR replyId IS NULL)
                        GROUP BY peerNodeNum HAVING sentAt = MAX(sentAt)
                    )
                    """, arguments: [broadcast])
        }
    }
    public func findEntity(id: Int32) async throws -> MessageEntity? {
        try await writer.read { db in
            try MessageEntity.fetchOne(db, sql: "SELECT * FROM messages WHERE id = ?", arguments: [id])
        }
    }
    public func newestPerChannel(broadcast: Int32) async throws -> [ChannelActivity] {
        try await writer.read { db in
            try ChannelActivity.fetchAll(
                db, sql: "SELECT channel, MAX(sentAt) AS lastAt FROM messages WHERE toNodeNum = ? GROUP BY channel",
                arguments: [broadcast])
        }
    }
    @discardableResult public func deleteOlderThan(cutoff: Int64) async throws -> Int {
        try await executeCount("DELETE FROM messages WHERE sentAt < ?", [cutoff])
    }
    public func placements(broadcast: Int32) async throws -> [RoomPlacement] {
        try await writer.read { db in
            try RoomPlacement.fetchAll(
                db, sql: "SELECT DISTINCT channel, roomId FROM messages WHERE toNodeNum = ?", arguments: [broadcast])
        }
    }
    public func placeRoom(roomId: Int32, slot: Int, broadcast: Int32) async throws {
        try await execute(
            "UPDATE messages SET channel = ? WHERE roomId = ? AND toNodeNum = ? AND channel != ?",
            [slot, roomId, broadcast, slot])
    }
    public func stampSlot(slot: Int, roomId: Int32, broadcast: Int32) async throws {
        try await execute(
            "UPDATE messages SET roomId = ? WHERE channel = ? AND roomId = 0 AND toNodeNum = ?",
            [roomId, slot, broadcast])
    }
    public func parkOtherRooms(slot: Int, keep: Int32, parked: Int, broadcast: Int32) async throws {
        try await execute(
            "UPDATE messages SET channel = ? WHERE channel = ? AND roomId != 0 AND roomId != ? AND toNodeNum = ?",
            [parked, slot, keep, broadcast])
    }
    public func parkUnfiled(slot: Int, parking: Int, broadcast: Int32) async throws {
        try await execute(
            "UPDATE messages SET channel = ? WHERE channel = ? AND roomId = 0 AND toNodeNum = ?",
            [parking, slot, broadcast])
    }
    public func restoreUnfiled(slot: Int, parking: Int, broadcast: Int32) async throws {
        try await execute(
            "UPDATE messages SET channel = ? WHERE channel = ? AND roomId = 0 AND toNodeNum = ?",
            [slot, parking, broadcast])
    }
    public func deleteRoom(roomId: Int32, broadcast: Int32) async throws {
        try await execute("DELETE FROM messages WHERE roomId = ? AND toNodeNum = ?", [roomId, broadcast])
    }
    public func deleteAll() async throws { try await execute("DELETE FROM messages", []) }
    /// One message, when a new attempt to send it takes its place.
    public func deleteById(id: Int32) async throws { try await execute("DELETE FROM messages WHERE id = ?", [id]) }
    public func deleteUnfiled(slot: Int, broadcast: Int32) async throws {
        try await execute("DELETE FROM messages WHERE channel = ? AND roomId = 0 AND toNodeNum = ?", [slot, broadcast])
    }
    public func moveUnfiled(from: Int, to: Int, broadcast: Int32) async throws {
        try await execute(
            "UPDATE messages SET channel = ? WHERE channel = ? AND roomId = 0 AND toNodeNum = ?", [to, from, broadcast])
    }
    public func upsert(message: MessageEntity) async throws { try await writer.write { db in try message.save(db) } }
    public func insertIfNew(message: MessageEntity) async throws -> Int64 {
        try await writer.write { db in
            try db.execute(
                sql:
                    """
                    INSERT OR IGNORE INTO messages (id, channel, fromNodeNum, toNodeNum, peerNodeNum, text, sentAt,
                    rxTime, status, failureReason, isOutgoing, rxSnr, rxRssi, hopsAway, replyId, emoji, signed, roomId)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                arguments: [
                    message.id, message.channel, message.fromNodeNum, message.toNodeNum, message.peerNodeNum,
                    message.text, message.sentAt, message.rxTime, message.status.name, message.failureReason,
                    message.isOutgoing, message.rxSnr, message.rxRssi, message.hopsAway, message.replyId, message.emoji,
                    message.signed, message.roomId,
                ])
            return db.changesCount == 0 ? -1 : Int64(message.id)
        }
    }
    public func updateStatus(id: Int32, status: MessageStatus, reason: String?) async throws {
        try await execute("UPDATE messages SET status = ?, failureReason = ? WHERE id = ?", [status.name, reason, id])
    }
    public func pendingOutgoing(pending: [MessageStatus]) async throws -> [MessageEntity] {
        let names = pending.map(\.name)
        guard !names.isEmpty else { return [] }
        let placeholders = Array(repeating: "?", count: names.count).joined(separator: ",")
        return try await writer.read { db in
            try MessageEntity.fetchAll(
                db, sql: "SELECT * FROM messages WHERE isOutgoing = 1 AND status IN (\(placeholders))",
                arguments: StatementArguments(names))
        }
    }
    /// The newest message in each channel, for the list previews. A reaction is not a message there (UX §5.4).
    public func observeLatestPerChannel(broadcast: Int32) -> AsyncStream<[MessageEntity]> {
        stream(writer) { db in
            try MessageEntity.fetchAll(
                db,
                sql: """
                    SELECT * FROM messages WHERE id IN (
                        SELECT id FROM messages WHERE toNodeNum = ?
                        AND (COALESCE(emoji, 0) = 0 OR replyId IS NULL)
                        GROUP BY channel HAVING sentAt = MAX(sentAt)
                    )
                    """, arguments: [broadcast])
        }
    }

    public func observeChannel(channel: Int) -> AsyncStream<[ChatMessage]> {
        mapStream(observeChannelEntities(channel: channel, broadcast: broadcastNodeNum)) { $0.map { $0.toDomain() } }
    }
    public func observeDirect(peer: Int32) -> AsyncStream<[ChatMessage]> {
        mapStream(observeDirectEntities(peer: peer, broadcast: broadcastNodeNum)) { $0.map { $0.toDomain() } }
    }
    public func directLatest() -> AsyncStream<[ChatMessage]> {
        mapStream(observeDirectLatest(broadcast: broadcastNodeNum)) { $0.map { $0.toDomain() } }
    }
    public func latestPerChannel() -> AsyncStream<[ChatMessage]> {
        mapStream(observeLatestPerChannel(broadcast: broadcastNodeNum)) { $0.map { $0.toDomain() } }
    }
    public func save(message: ChatMessage, myNodeNum: Int32) async throws {
        try await upsert(message: MessageEntity.fromDomain(message, myNodeNum: myNodeNum))
    }
    public func saveIfNew(message: ChatMessage, myNodeNum: Int32) async throws -> Bool {
        try await insertIfNew(message: MessageEntity.fromDomain(message, myNodeNum: myNodeNum)) != -1
    }
    public func find(id: Int32) async throws -> ChatMessage? { try await findEntity(id: id)?.toDomain() }
    public func placements() async throws -> [RoomPlacement] { try await placements(broadcast: broadcastNodeNum) }
    public func placeRoom(roomId: Int32, slot: Int) async throws {
        try await placeRoom(roomId: roomId, slot: slot, broadcast: broadcastNodeNum)
    }
    public func stampSlot(slot: Int, roomId: Int32) async throws {
        try await stampSlot(slot: slot, roomId: roomId, broadcast: broadcastNodeNum)
    }
    public func parkOtherRooms(slot: Int, keep: Int32) async throws {
        try await parkOtherRooms(slot: slot, keep: keep, parked: PARKED_CHANNEL, broadcast: broadcastNodeNum)
    }
    public func parkUnfiled(slot: Int) async throws {
        try await parkUnfiled(slot: slot, parking: unfiledParkingFor(slot: slot), broadcast: broadcastNodeNum)
    }
    public func restoreUnfiled(slot: Int) async throws {
        try await restoreUnfiled(slot: slot, parking: unfiledParkingFor(slot: slot), broadcast: broadcastNodeNum)
    }
    public func deleteUnfiled(slot: Int) async throws {
        try await deleteUnfiled(slot: slot, broadcast: broadcastNodeNum)
    }
    public func moveUnfiled(from: Int, to: Int) async throws {
        try await moveUnfiled(from: from, to: to, broadcast: broadcastNodeNum)
    }
    public func deleteRoom(roomId: Int32) async throws {
        try await deleteRoom(roomId: roomId, broadcast: broadcastNodeNum)
    }
    public func newestPerChannel() async throws -> [Int: Int64] {
        Dictionary(
            uniqueKeysWithValues: try await newestPerChannel(broadcast: broadcastNodeNum).map {
                ($0.channel, $0.lastAt)
            })
    }
    private func execute(_ sql: String, _ arguments: StatementArguments) async throws {
        try await writer.write { db in try db.execute(sql: sql, arguments: arguments) }
    }
    private func executeCount(_ sql: String, _ arguments: StatementArguments) async throws -> Int {
        try await writer.write { db in
            try db.execute(sql: sql, arguments: arguments)
            return db.changesCount
        }
    }
}

public struct NodeDao: Sendable {
    private let writer: any DatabaseWriter
    public init(_ writer: any DatabaseWriter) { self.writer = writer }
    public func observeAllEntities() -> AsyncStream<[NodeEntity]> {
        stream(writer) { db in try NodeEntity.fetchAll(db, sql: "SELECT * FROM nodes ORDER BY lastHeard DESC") }
    }
    public func findEntity(nodeNum: Int32) async throws -> NodeEntity? {
        try await writer.read { db in
            try NodeEntity.fetchOne(db, sql: "SELECT * FROM nodes WHERE nodeNum = ?", arguments: [nodeNum])
        }
    }
    public func upsert(node: NodeEntity) async throws { try await writer.write { db in try node.save(db) } }
    public func updatePosition(
        nodeNum: Int32, latitudeI: Int32?, longitudeI: Int32?, altitude: Int?, positionTime: Int64?,
        positionPrecision: Int?, groundSpeed: Int?, groundTrack: Int?
    ) async throws {
        try await execute(
            """
            UPDATE nodes SET latitudeI = ?, longitudeI = ?, altitude = ?, positionTime = ?, positionPrecision = ?,
            groundSpeed = ?, groundTrack = ? WHERE nodeNum = ?
            """,
            [latitudeI, longitudeI, altitude, positionTime, positionPrecision, groundSpeed, groundTrack, nodeNum])
    }
    public func updateMetrics(
        nodeNum: Int32, batteryLevel: Int?, voltage: Float?, channelUtilization: Float?, airUtilTx: Float?
    ) async throws {
        try await execute(
            """
            UPDATE nodes SET batteryLevel = COALESCE(?, batteryLevel), voltage = COALESCE(?, voltage),
            channelUtilization = COALESCE(?, channelUtilization), airUtilTx = COALESCE(?, airUtilTx) WHERE nodeNum = ?
            """,
            [batteryLevel, voltage, channelUtilization, airUtilTx, nodeNum])
    }
    public func markHeard(nodeNum: Int32, heardAt: Int64, snr: Float?, rssi: Int?, hopsAway: Int?) async throws {
        try await execute(
            """
            UPDATE nodes SET lastHeard = ?, snr = COALESCE(?, snr), rssi = COALESCE(?, rssi), hopsAway = COALESCE(?,
            hopsAway) WHERE nodeNum = ?
            """,
            [heardAt, snr, rssi, hopsAway, nodeNum])
    }
    @discardableResult public func forgetPositionsBefore(cutoff: Int64) async throws -> Int {
        try await executeCount(
            """
            UPDATE nodes SET latitudeI = NULL, longitudeI = NULL, altitude = NULL, positionTime = NULL,
            positionPrecision = NULL, groundSpeed = NULL, groundTrack = NULL WHERE latitudeI IS NOT NULL AND
            (positionTime IS NULL OR positionTime < ?)
            """,
            [cutoff])
    }
    @discardableResult public func forgetStrangersBefore(cutoff: Int64, keep: Int32) async throws -> Int {
        try await executeCount(
            """
            DELETE FROM nodes WHERE nodeNum != ? AND (lastHeard IS NULL OR lastHeard < ?) AND nodeNum NOT IN (SELECT
            nodeNum FROM room_members)
            """,
            [keep, cutoff])
    }
    public func forgetAllPositions() async throws {
        try await execute(
            """
            UPDATE nodes SET latitudeI = NULL, longitudeI = NULL, altitude = NULL, positionTime = NULL,
            positionPrecision = NULL, groundSpeed = NULL, groundTrack = NULL
            """,
            [])
    }
    public func observeAll() -> AsyncStream<[MeshNode]> { mapStream(observeAllEntities()) { $0.map { $0.toDomain() } } }
    public func find(nodeNum: Int32) async throws -> MeshNode? { try await findEntity(nodeNum: nodeNum)?.toDomain() }
    public func save(node: MeshNode, now: Int64) async throws {
        let existing = try await findEntity(nodeNum: node.nodeNum)
        var merged = node
        merged.latitudeI = node.latitudeI ?? existing?.latitudeI
        merged.longitudeI = node.longitudeI ?? existing?.longitudeI
        merged.altitude = node.altitude ?? existing?.altitude
        merged.positionTime = node.positionTime ?? existing?.positionTime
        merged.positionPrecision = node.positionPrecision ?? existing?.positionPrecision
        try await upsert(node: NodeEntity.fromDomain(merged, firstSeen: existing?.firstSeen ?? now))
    }
    private func execute(_ sql: String, _ arguments: StatementArguments) async throws {
        try await writer.write { db in try db.execute(sql: sql, arguments: arguments) }
    }
    private func executeCount(_ sql: String, _ arguments: StatementArguments) async throws -> Int {
        try await writer.write { db in
            try db.execute(sql: sql, arguments: arguments)
            return db.changesCount
        }
    }
}

public struct MapPinDao: Sendable {
    private let writer: any DatabaseWriter
    public init(_ writer: any DatabaseWriter) { self.writer = writer }
    public func observeLiveEntities(nowSeconds: Int64) -> AsyncStream<[MapPinEntity]> {
        stream(writer) { db in
            try MapPinEntity.fetchAll(
                db, sql: "SELECT * FROM map_pins WHERE expire = 0 OR expire > ?", arguments: [nowSeconds])
        }
    }
    public func findEntity(id: Int32) async throws -> MapPinEntity? {
        try await writer.read { db in
            try MapPinEntity.fetchOne(db, sql: "SELECT * FROM map_pins WHERE id = ?", arguments: [id])
        }
    }
    public func upsert(pin: MapPinEntity) async throws { try await writer.write { db in try pin.save(db) } }
    public func delete(id: Int32) async throws { try await execute("DELETE FROM map_pins WHERE id = ?", [id]) }
    public func remember(deleted: DeletedPinEntity) async throws {
        try await writer.write { db in try deleted.save(db) }
    }
    public func wasDeleted(id: Int32) async throws -> Bool {
        try await writer.read { db in
            try Bool.fetchOne(db, sql: "SELECT EXISTS(SELECT 1 FROM deleted_pins WHERE id = ?)", arguments: [id])
                ?? false
        }
    }
    public func recentlyDeleted(since: Int64) async throws -> [DeletedPinEntity] {
        try await writer.read { db in
            try DeletedPinEntity.fetchAll(db, sql: "SELECT * FROM deleted_pins WHERE deletedAt > ?", arguments: [since])
        }
    }
    public func moveUnfiled(from: Int, to: Int) async throws {
        try await execute("UPDATE map_pins SET channel = ? WHERE channel = ? AND roomId = 0", [to, from])
    }
    public func deleteUnfiled(slot: Int) async throws {
        try await execute("DELETE FROM map_pins WHERE channel = ? AND roomId = 0", [slot])
    }
    public func placements() async throws -> [RoomPlacement] {
        try await writer.read { db in
            try RoomPlacement.fetchAll(db, sql: "SELECT DISTINCT channel, roomId FROM map_pins")
        }
    }
    public func placeRoom(roomId: Int32, slot: Int) async throws {
        try await execute("UPDATE map_pins SET channel = ? WHERE roomId = ? AND channel != ?", [slot, roomId, slot])
    }
    public func stampSlot(slot: Int, roomId: Int32) async throws {
        try await execute("UPDATE map_pins SET roomId = ? WHERE channel = ? AND roomId = 0", [roomId, slot])
    }
    public func parkOtherRooms(slot: Int, keep: Int32, parked: Int = PARKED_CHANNEL) async throws {
        try await execute(
            "UPDATE map_pins SET channel = ? WHERE channel = ? AND roomId != 0 AND roomId != ?", [parked, slot, keep])
    }
    public func parkUnfiled(slot: Int, parking: Int? = nil) async throws {
        try await execute(
            "UPDATE map_pins SET channel = ? WHERE channel = ? AND roomId = 0",
            [parking ?? unfiledParkingFor(slot: slot), slot])
    }
    public func restoreUnfiled(slot: Int, parking: Int? = nil) async throws {
        try await execute(
            "UPDATE map_pins SET channel = ? WHERE channel = ? AND roomId = 0",
            [slot, parking ?? unfiledParkingFor(slot: slot)])
    }
    public func deleteRoom(roomId: Int32) async throws {
        try await execute("DELETE FROM map_pins WHERE roomId = ?", [roomId])
    }
    @discardableResult public func deleteExpired(nowSeconds: Int64) async throws -> Int {
        try await executeCount("DELETE FROM map_pins WHERE expire != 0 AND expire < ?", [nowSeconds])
    }
    public func deleteAll() async throws { try await execute("DELETE FROM map_pins", []) }
    @discardableResult public func forgetDeletedBefore(cutoff: Int64) async throws -> Int {
        try await executeCount("DELETE FROM deleted_pins WHERE deletedAt < ?", [cutoff])
    }
    public func forgetAllDeleted() async throws { try await execute("DELETE FROM deleted_pins", []) }
    public func observeLive(nowMillis: Int64 = currentEpochMillis()) -> AsyncStream<[MapPin]> {
        mapStream(observeLiveEntities(nowSeconds: nowMillis / 1000)) { $0.map { $0.toDomain() } }
    }
    public func save(pin: MapPin) async throws { try await upsert(pin: MapPinEntity.fromDomain(pin)) }
    public func find(id: Int32) async throws -> MapPin? { try await findEntity(id: id)?.toDomain() }
    private func execute(_ sql: String, _ arguments: StatementArguments) async throws {
        try await writer.write { db in try db.execute(sql: sql, arguments: arguments) }
    }
    private func executeCount(_ sql: String, _ arguments: StatementArguments) async throws -> Int {
        try await writer.write { db in
            try db.execute(sql: sql, arguments: arguments)
            return db.changesCount
        }
    }
}

public struct ChannelStateDao: Sendable {
    private let writer: any DatabaseWriter
    public init(_ writer: any DatabaseWriter) { self.writer = writer }
    public func observeAll() -> AsyncStream<[ChannelStateEntity]> {
        stream(writer) { db in try ChannelStateEntity.fetchAll(db, sql: "SELECT * FROM channel_state") }
    }
    public func find(channel: Int) async throws -> ChannelStateEntity? {
        try await writer.read { db in
            try ChannelStateEntity.fetchOne(
                db, sql: "SELECT * FROM channel_state WHERE channel = ?", arguments: [channel])
        }
    }
    public func upsert(state: ChannelStateEntity) async throws { try await writer.write { db in try state.save(db) } }
    public func stampSlot(slot: Int, roomId: Int32) async throws {
        try await writer.write { db in
            try db.execute(
                sql: "UPDATE channel_state SET roomId = ? WHERE channel = ? AND roomId = 0", arguments: [roomId, slot])
        }
    }
    /// Unread counts per channel. Reactions are not counted: they do not notify either (UX §5.4).
    public func observeUnread() -> AsyncStream<[UnreadCount]> {
        stream(writer) { db in
            try UnreadCount.fetchAll(
                db,
                sql: """
                    SELECT m.channel AS channel, COUNT(*) AS count
                    FROM messages m
                    LEFT JOIN channel_state s ON s.channel = m.channel
                    WHERE m.isOutgoing = 0 AND m.sentAt > COALESCE(s.lastReadAt, 0)
                    AND (COALESCE(m.emoji, 0) = 0 OR m.replyId IS NULL)
                    GROUP BY m.channel
                    """)
        }
    }
    public func markRead(channel: Int, now: Int64, roomId: Int32 = 0) async throws {
        let existing = try await find(channel: channel)
        try await upsert(
            state: ChannelStateEntity(
                channel: channel, lastReadAt: now, muted: existing?.roomId == roomId && existing?.muted == true,
                roomId: roomId))
    }
    public func setMuted(channel: Int, muted: Bool, roomId: Int32 = 0) async throws {
        let existing = try await find(channel: channel)
        try await upsert(
            state: ChannelStateEntity(
                channel: channel, lastReadAt: existing?.roomId == roomId ? existing?.lastReadAt ?? 0 : 0, muted: muted,
                roomId: roomId))
    }
    public func followRoom(channel: Int, roomId: Int32, now: Int64, roomMuted: Bool) async throws {
        guard let existing = try await find(channel: channel), existing.roomId != roomId else { return }
        try await upsert(state: ChannelStateEntity(channel: channel, lastReadAt: now, muted: roomMuted, roomId: roomId))
    }
    public func observeMuted() -> AsyncStream<Set<Int>> {
        mapStream(observeAll()) { Set($0.filter(\.muted).map(\.channel)) }
    }
}

public struct RoomMemberDao: Sendable {
    private let writer: any DatabaseWriter
    public init(_ writer: any DatabaseWriter) { self.writer = writer }
    public func observeRoomEntities(roomId: Int32) -> AsyncStream<[RoomMemberEntity]> {
        stream(writer) { db in
            try RoomMemberEntity.fetchAll(
                db, sql: "SELECT * FROM room_members WHERE roomId = ? ORDER BY lastHeard DESC", arguments: [roomId])
        }
    }
    public func findEntity(roomId: Int32, nodeNum: Int32) async throws -> RoomMemberEntity? {
        try await writer.read { db in
            try RoomMemberEntity.fetchOne(
                db, sql: "SELECT * FROM room_members WHERE roomId = ? AND nodeNum = ?", arguments: [roomId, nodeNum])
        }
    }
    public func upsert(member: RoomMemberEntity) async throws { try await writer.write { db in try member.save(db) } }
    public func remove(roomId: Int32, nodeNum: Int32) async throws {
        try await writer.write { db in
            try db.execute(
                sql: "DELETE FROM room_members WHERE roomId = ? AND nodeNum = ?", arguments: [roomId, nodeNum])
        }
    }
    public func nodeNumsIn(roomId: Int32) async throws -> [Int32] {
        try await writer.read { db in
            try Int32.fetchAll(db, sql: "SELECT nodeNum FROM room_members WHERE roomId = ?", arguments: [roomId])
        }
    }
    public func openedGeneration(roomId: Int32, nodeNum: Int32, generation: Int) async throws -> Bool {
        try await writer.read { db in
            try Bool.fetchOne(
                db,
                sql: """
                     SELECT EXISTS(
                       SELECT 1 FROM room_members
                       WHERE roomId = ? AND nodeNum = ? AND lastOpenedGeneration = ?
                     )
                     """,
                arguments: [roomId, nodeNum, generation]
            ) ?? false
        }
    }
    public func recordOpenedGeneration(roomId: Int32, nodeNum: Int32, generation: Int) async throws {
        try await writer.write { db in
            try db.execute(
                sql: "UPDATE room_members SET lastOpenedGeneration = ? WHERE roomId = ? AND nodeNum = ?",
                arguments: [generation, roomId, nodeNum])
        }
    }
    public func clearOpenedGeneration(roomId: Int32, nodeNum: Int32, generation: Int) async throws {
        try await writer.write { db in
            try db.execute(
                sql: """
                     UPDATE room_members SET lastOpenedGeneration = NULL
                     WHERE roomId = ? AND nodeNum = ? AND lastOpenedGeneration = ?
                     """,
                arguments: [roomId, nodeNum, generation])
        }
    }
    public func observeAllNodeNums() -> AsyncStream<[Int32]> {
        stream(writer) { db in try Int32.fetchAll(db, sql: "SELECT DISTINCT nodeNum FROM room_members") }
    }
    public func isInAnyRoom(nodeNum: Int32) async throws -> Bool {
        try await writer.read { db in
            try Bool.fetchOne(
                db, sql: "SELECT EXISTS(SELECT 1 FROM room_members WHERE nodeNum = ?)", arguments: [nodeNum]) ?? false
        }
    }
    public func deleteRoom(roomId: Int32) async throws {
        try await writer.write { db in
            try db.execute(sql: "DELETE FROM room_members WHERE roomId = ?", arguments: [roomId])
        }
    }
    public func observeRoom(roomId: Int32) -> AsyncStream<[RoomMember]> {
        mapStream(observeRoomEntities(roomId: roomId)) { $0.map { $0.toDomain() } }
    }
    public func record(roomId: Int32, nodeNum: Int32, now: Int64, invitedBy: Int32? = nil) async throws {
        let existing = try await findEntity(roomId: roomId, nodeNum: nodeNum)
        try await upsert(
            member: RoomMemberEntity(
                roomId: roomId, nodeNum: nodeNum, invitedBy: invitedBy ?? existing?.invitedBy,
                firstSeen: existing?.firstSeen ?? now, lastHeard: max(now, existing?.lastHeard ?? now),
                lastOpenedGeneration: existing?.lastOpenedGeneration))
    }
    public func recordReported(roomId: Int32, nodeNum: Int32, now: Int64, invitedBy: Int32?) async throws {
        let existing = try await findEntity(roomId: roomId, nodeNum: nodeNum)
        try await upsert(
            member: RoomMemberEntity(
                roomId: roomId, nodeNum: nodeNum, invitedBy: invitedBy ?? existing?.invitedBy,
                firstSeen: existing?.firstSeen ?? now, lastHeard: existing?.lastHeard,
                lastOpenedGeneration: existing?.lastOpenedGeneration))
    }
}

public struct ReceiptDao: Sendable {
    private let writer: any DatabaseWriter
    public init(_ writer: any DatabaseWriter) { self.writer = writer }
    public func observeFor(messageId: Int32) -> AsyncStream<[ReceiptEntity]> {
        stream(writer) { db in
            try ReceiptEntity.fetchAll(
                db, sql: "SELECT * FROM receipts WHERE messageId = ? ORDER BY at ASC", arguments: [messageId])
        }
    }
    public func hasFrom(messageId: Int32, nodeNum: Int32) async throws -> Bool {
        try await writer.read { db in
            try Bool.fetchOne(
                db, sql: "SELECT EXISTS(SELECT 1 FROM receipts WHERE messageId = ? AND nodeNum = ?)",
                arguments: [messageId, nodeNum]) ?? false
        }
    }
    public func observeForAll(messageIds: [Int32]) -> AsyncStream<[ReceiptEntity]> {
        stream(writer) { db in
            guard !messageIds.isEmpty else { return [] }
            let placeholders = Array(repeating: "?", count: messageIds.count).joined(separator: ",")
            return try ReceiptEntity.fetchAll(
                db, sql: "SELECT * FROM receipts WHERE messageId IN (\(placeholders))",
                arguments: StatementArguments(messageIds))
        }
    }
    public func recordReceived(messageId: Int32, nodeNum: Int32, at: Int64) async throws {
        try await execute(
            """
            INSERT OR IGNORE INTO receipts (messageId, nodeNum, state, at) SELECT ?, ?, 'RECEIVED', ? WHERE EXISTS
            (SELECT 1 FROM messages WHERE id = ?)
            """,
            [messageId, nodeNum, at, messageId])
    }
    public func insertRead(messageId: Int32, nodeNum: Int32, at: Int64) async throws {
        try await execute(
            """
            INSERT OR IGNORE INTO receipts (messageId, nodeNum, state, at) SELECT ?, ?, 'READ', ? WHERE EXISTS (SELECT 1
            FROM messages WHERE id = ?)
            """,
            [messageId, nodeNum, at, messageId])
    }
    public func promoteToRead(messageId: Int32, nodeNum: Int32, at: Int64) async throws {
        try await execute(
            "UPDATE receipts SET state = 'READ', at = ? WHERE messageId = ? AND nodeNum = ? AND state != 'READ'",
            [at, messageId, nodeNum])
    }
    public func recordRead(messageId: Int32, nodeNum: Int32, at: Int64) async throws {
        try await insertRead(messageId: messageId, nodeNum: nodeNum, at: at)
        try await promoteToRead(messageId: messageId, nodeNum: nodeNum, at: at)
    }
    public func observe(messageId: Int32) -> AsyncStream<[Receipt]> {
        mapStream(observeFor(messageId: messageId)) {
            $0.map { Receipt(nodeNum: $0.nodeNum, state: $0.state, at: $0.at) }
        }
    }
    private func execute(_ sql: String, _ arguments: StatementArguments) async throws {
        try await writer.write { db in try db.execute(sql: sql, arguments: arguments) }
    }
}

public struct PersonCardDao: Sendable {
    private let writer: any DatabaseWriter
    public init(_ writer: any DatabaseWriter) { self.writer = writer }
    public func observeAllEntities() -> AsyncStream<[PersonCardEntity]> {
        stream(writer) { db in try PersonCardEntity.fetchAll(db, sql: "SELECT * FROM person_cards") }
    }
    public func upsert(card: PersonCardEntity) async throws { try await writer.write { db in try card.save(db) } }
    public func find(nodeNum: Int32) async throws -> PersonCardEntity? {
        try await writer.read { db in
            try PersonCardEntity.fetchOne(db, sql: "SELECT * FROM person_cards WHERE nodeNum = ?", arguments: [nodeNum])
        }
    }
    public func forget(nodeNum: Int32) async throws {
        try await writer.write { db in
            try db.execute(sql: "DELETE FROM person_cards WHERE nodeNum = ?", arguments: [nodeNum])
        }
    }
    @discardableResult public func forgetOutsideRooms() async throws -> Int {
        try await writer.write { db in
            try db.execute(sql: "DELETE FROM person_cards WHERE nodeNum NOT IN (SELECT nodeNum FROM room_members)")
            return db.changesCount
        }
    }
    public func deleteAll() async throws {
        try await writer.write { db in try db.execute(sql: "DELETE FROM person_cards") }
    }
    public func observeAll() -> AsyncStream<[Int32: PersonCard]> {
        mapStream(observeAllEntities()) { Dictionary(uniqueKeysWithValues: $0.map { ($0.nodeNum, $0.toDomain()) }) }
    }
}

public struct PeerKeyDao: Sendable {
    private let writer: any DatabaseWriter
    public init(_ writer: any DatabaseWriter) { self.writer = writer }
    public func find(nodeNum: Int32) async throws -> PeerKeyEntity? {
        try await writer.read { db in
            try PeerKeyEntity.fetchOne(db, sql: "SELECT * FROM peer_keys WHERE nodeNum = ?", arguments: [nodeNum])
        }
    }
    public func observeKnown(nodeNum: Int32) -> AsyncStream<Bool> {
        stream(writer) { db in
            try Bool.fetchOne(db, sql: "SELECT EXISTS(SELECT 1 FROM peer_keys WHERE nodeNum = ?)", arguments: [nodeNum])
                ?? false
        }
    }
    public func upsert(key: PeerKeyEntity) async throws { try await writer.write { db in try key.save(db) } }
    @discardableResult public func forgetOutsideRooms() async throws -> Int {
        try await writer.write { db in
            try db.execute(sql: "DELETE FROM peer_keys WHERE nodeNum NOT IN (SELECT nodeNum FROM room_members)")
            return db.changesCount
        }
    }
}

public struct RoomActivityDao: Sendable {
    private let writer: any DatabaseWriter
    public init(_ writer: any DatabaseWriter) { self.writer = writer }
    public func all() async throws -> [RoomActivityEntity] {
        try await writer.read { db in try RoomActivityEntity.fetchAll(db, sql: "SELECT * FROM room_activity") }
    }
    public func joined(roomId: Int32, now: Int64) async throws {
        try await execute(
            "INSERT OR IGNORE INTO room_activity (roomId, joinedAt, lastActivityAt) VALUES (?, ?, ?)",
            [roomId, now, now])
    }
    public func touch(roomId: Int32, now: Int64) async throws {
        try await execute(
            "UPDATE room_activity SET lastActivityAt = MAX(lastActivityAt, ?) WHERE roomId = ?", [now, roomId])
    }
    public func forget(roomId: Int32) async throws {
        try await execute("DELETE FROM room_activity WHERE roomId = ?", [roomId])
    }
    public func isMuted(roomId: Int32) async throws -> Bool {
        try await writer.read { db in
            try Bool.fetchOne(
                db, sql: "SELECT EXISTS(SELECT 1 FROM room_activity WHERE roomId = ? AND muted = 1)",
                arguments: [roomId]) ?? false
        }
    }
    public func updateMuted(roomId: Int32, muted: Bool) async throws {
        try await execute("UPDATE room_activity SET muted = ? WHERE roomId = ?", [muted, roomId])
    }
    public func setMuted(roomId: Int32, muted: Bool, now: Int64) async throws {
        try await joined(roomId: roomId, now: now)
        try await updateMuted(roomId: roomId, muted: muted)
    }
    public func recordActivity(roomId: Int32, now: Int64) async throws {
        try await joined(roomId: roomId, now: now)
        try await touch(roomId: roomId, now: now)
    }
    private func execute(_ sql: String, _ arguments: StatementArguments) async throws {
        try await writer.write { db in try db.execute(sql: sql, arguments: arguments) }
    }
}

public struct PendingHandoverDao: Sendable {
    private let writer: any DatabaseWriter
    public init(_ writer: any DatabaseWriter) { self.writer = writer }
    public func upsert(handover: PendingHandoverEntity) async throws {
        try await writer.write { db in try handover.save(db) }
    }
    public func forNode(nodeNum: Int32) async throws -> [PendingHandoverEntity] {
        try await writer.read { db in
            try PendingHandoverEntity.fetchAll(
                db, sql: "SELECT * FROM pending_handovers WHERE nodeNum = ?", arguments: [nodeNum])
        }
    }
    public func forRoom(roomId: Int32) async throws -> [PendingHandoverEntity] {
        try await writer.read { db in
            try PendingHandoverEntity.fetchAll(
                db, sql: "SELECT * FROM pending_handovers WHERE roomId = ?", arguments: [roomId])
        }
    }
    public func all() async throws -> [PendingHandoverEntity] {
        try await writer.read { db in try PendingHandoverEntity.fetchAll(db, sql: "SELECT * FROM pending_handovers") }
    }
    public func delete(roomId: Int32, nodeNum: Int32) async throws {
        try await writer.write { db in
            try db.execute(
                sql: "DELETE FROM pending_handovers WHERE roomId = ? AND nodeNum = ?", arguments: [roomId, nodeNum])
        }
    }
    public func deleteRoom(roomId: Int32) async throws {
        try await writer.write { db in
            try db.execute(sql: "DELETE FROM pending_handovers WHERE roomId = ?", arguments: [roomId])
        }
    }
    /// Clears what is owed to a member up to `generation`, and no further: a later rotation's record, written
    /// meanwhile, stays. How many it cleared.
    @discardableResult
    public func deleteUpTo(roomId: Int32, nodeNum: Int32, generation: Int) async throws -> Int {
        try await writer.write { db in
            try db.execute(
                sql: "DELETE FROM pending_handovers WHERE roomId = ? AND nodeNum = ? AND generation <= ?",
                arguments: [roomId, nodeNum, generation])
            return db.changesCount
        }
    }
    /// Notes another attempt, only while the record is still the one that was tried: one settled, or replaced by a
    /// later rotation, meanwhile stays as it is. How many records it touched.
    @discardableResult
    public func touch(roomId: Int32, nodeNum: Int32, generation: Int, at: Int64) async throws -> Int {
        try await writer.write { db in
            try db.execute(
                sql: "UPDATE pending_handovers SET lastTriedAt = ? WHERE roomId = ? AND nodeNum = ? AND generation = ?",
                arguments: [at, roomId, nodeNum, generation])
            return db.changesCount
        }
    }
}

private func mapStream<Input: Sendable, Output: Sendable>(
    _ source: AsyncStream<Input>, _ transform: @escaping @Sendable (Input) -> Output
) -> AsyncStream<Output> {
    AsyncStream { continuation in
        let task = Task {
            for await value in source { continuation.yield(transform(value)) }
            continuation.finish()
        }
        continuation.onTermination = { _ in task.cancel() }
    }
}
