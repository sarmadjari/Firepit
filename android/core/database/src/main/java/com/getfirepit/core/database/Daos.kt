package com.getfirepit.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.getfirepit.core.model.BROADCAST_NODE_NUM
import com.getfirepit.core.model.ChatMessage
import com.getfirepit.core.model.MapPin
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.model.PersonCard
import com.getfirepit.core.model.Receipt
import com.getfirepit.core.model.MessageStatus
import com.getfirepit.core.model.RoomMember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface MessageDao {

    @Query(
        "SELECT * FROM messages WHERE channel = :channel AND toNodeNum = :broadcast ORDER BY sentAt ASC",
    )
    fun observeChannelEntities(channel: Int, broadcast: Int): Flow<List<MessageEntity>>

    /** One conversation with one person, whichever channel carried it. */
    @Query(
        "SELECT * FROM messages WHERE peerNodeNum = :peer AND toNodeNum != :broadcast ORDER BY sentAt ASC",
    )
    fun observeDirectEntities(peer: Int, broadcast: Int): Flow<List<MessageEntity>>

    /** Newest message per person, for the Direct list. A reaction is not a message there (UX §5.4). */
    @Query(
        """
        SELECT * FROM messages WHERE id IN (
            SELECT id FROM messages WHERE toNodeNum != :broadcast
            AND (COALESCE(emoji, 0) = 0 OR replyId IS NULL)
            GROUP BY peerNodeNum HAVING sentAt = MAX(sentAt)
        )
        """,
    )
    fun observeDirectLatest(broadcast: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun findEntity(id: Int): MessageEntity?

    /** Enforces the retention setting on this phone's copy. */
    @Query(
        "SELECT channel, MAX(sentAt) AS lastAt FROM messages " +
            "WHERE toNodeNum = :broadcast GROUP BY channel",
    )
    suspend fun newestPerChannel(broadcast: Int): List<ChannelActivity>

    @Query("DELETE FROM messages WHERE sentAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /** Which room's history sits in which slot, for following rooms across radios. */
    @Query("SELECT DISTINCT channel, roomId FROM messages WHERE toNodeNum = :broadcast")
    suspend fun placements(broadcast: Int): List<RoomPlacement>

    /** Brings a room's history to the slot the room is in now. */
    @Query(
        "UPDATE messages SET channel = :slot WHERE roomId = :roomId AND toNodeNum = :broadcast AND channel != :slot",
    )
    suspend fun placeRoom(roomId: Int, slot: Int, broadcast: Int)

    /** Marks history kept before rooms were recorded as belonging to the room in its slot now. */
    @Query("UPDATE messages SET roomId = :roomId WHERE channel = :slot AND roomId = 0 AND toNodeNum = :broadcast")
    suspend fun stampSlot(slot: Int, roomId: Int, broadcast: Int)

    /** Sets aside other rooms' history sitting in [slot], off every slot the screen can show. */
    @Query(
        "UPDATE messages SET channel = :parked WHERE channel = :slot AND roomId != 0 AND roomId != :keep " +
            "AND toNodeNum = :broadcast",
    )
    suspend fun parkOtherRooms(slot: Int, keep: Int, parked: Int, broadcast: Int)

    /**
     * Sets aside history filed under no room — a Meshtastic channel's, which
     * has no id — while a room holds its slot. Kept per slot, so it comes back
     * when a channel without an id is in that slot again.
     */
    @Query("UPDATE messages SET channel = :parking WHERE channel = :slot AND roomId = 0 AND toNodeNum = :broadcast")
    suspend fun parkUnfiled(slot: Int, parking: Int, broadcast: Int)

    @Query("UPDATE messages SET channel = :slot WHERE channel = :parking AND roomId = 0 AND toNodeNum = :broadcast")
    suspend fun restoreUnfiled(slot: Int, parking: Int, broadcast: Int)

    @Query("DELETE FROM messages WHERE roomId = :roomId AND toNodeNum = :broadcast")
    suspend fun deleteRoom(roomId: Int, broadcast: Int)

    /** Receipts go with them, by the cascade. */
    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    /** One message, when a new attempt to send it takes its place. */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Int)

    /**
     * Leaving a channel that has no id takes its history with it. Only rows
     * filed under no room: a room's own history is deleted by its id.
     */
    @Query("DELETE FROM messages WHERE channel = :slot AND roomId = 0 AND toNodeNum = :broadcast")
    suspend fun deleteUnfiled(slot: Int, broadcast: Int)

    /**
     * Follows a channel that has no id to its new slot, when leaving another
     * shifts the rest down. A room's history is placed by its id instead.
     */
    @Query("UPDATE messages SET channel = :to WHERE channel = :from AND roomId = 0 AND toNodeNum = :broadcast")
    suspend fun moveUnfiled(from: Int, to: Int, broadcast: Int)

    @Upsert
    suspend fun upsert(message: MessageEntity)

    /**
     * Ignores redelivered packets. The firmware suppresses duplicates by
     * (from, id) but a packet can still reach the phone twice across a
     * reconnect, and a re-insert would otherwise clobber a status we have
     * already advanced.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(message: MessageEntity): Long

    @Query("UPDATE messages SET status = :status, failureReason = :reason WHERE id = :id")
    suspend fun updateStatus(id: Int, status: MessageStatus, reason: String?)

    @Query("SELECT * FROM messages WHERE isOutgoing = 1 AND status IN (:pending)")
    suspend fun pendingOutgoing(pending: List<MessageStatus>): List<MessageEntity>

    /** The newest message in each channel, for the list previews. A reaction is not a message there (UX §5.4). */
    @Query(
        """
        SELECT * FROM messages WHERE id IN (
            SELECT id FROM messages WHERE toNodeNum = :broadcast
            AND (COALESCE(emoji, 0) = 0 OR replyId IS NULL)
            GROUP BY channel HAVING sentAt = MAX(sentAt)
        )
        """,
    )
    fun observeLatestPerChannel(broadcast: Int): Flow<List<MessageEntity>>}

// A room shows what was said to the room. A message addressed to one person is
// not part of it, and rendering it there would leak a private word into a group.
fun MessageDao.observeChannel(channel: Int): Flow<List<ChatMessage>> =
    observeChannelEntities(channel, BROADCAST_NODE_NUM)
        .map { entities -> entities.map(MessageEntity::toDomain) }

fun MessageDao.observeDirect(peer: Int): Flow<List<ChatMessage>> =
    observeDirectEntities(peer, BROADCAST_NODE_NUM)
        .map { entities -> entities.map(MessageEntity::toDomain) }

fun MessageDao.directLatest(): Flow<List<ChatMessage>> =
    observeDirectLatest(BROADCAST_NODE_NUM)
        .map { entities -> entities.map(MessageEntity::toDomain) }

fun MessageDao.latestPerChannel(): Flow<List<ChatMessage>> =
    observeLatestPerChannel(BROADCAST_NODE_NUM).map { entities -> entities.map(MessageEntity::toDomain) }

@Dao
interface NodeDao {

    @Query("SELECT * FROM nodes ORDER BY lastHeard DESC")
    fun observeAllEntities(): Flow<List<NodeEntity>>

    @Query("SELECT * FROM nodes WHERE nodeNum = :nodeNum")
    suspend fun findEntity(nodeNum: Int): NodeEntity?

    @Upsert
    suspend fun upsert(node: NodeEntity)

    @Query(
        """
        UPDATE nodes SET latitudeI = :latitudeI, longitudeI = :longitudeI, altitude = :altitude,
        positionTime = :positionTime, positionPrecision = :positionPrecision,
        groundSpeed = :groundSpeed, groundTrack = :groundTrack
        WHERE nodeNum = :nodeNum
        """,
    )
    suspend fun updatePosition(
        nodeNum: Int,
        latitudeI: Int?,
        longitudeI: Int?,
        altitude: Int?,
        positionTime: Long?,
        positionPrecision: Int?,
        groundSpeed: Int?,
        groundTrack: Int?,
    )

    /**
     * Writes telemetry without touching identity.
     *
     * COALESCE keeps the previous reading when a packet omits a field, so a
     * battery-only report does not erase the channel figures beside it.
     */
    @Query(
        """
        UPDATE nodes SET
            batteryLevel = COALESCE(:batteryLevel, batteryLevel),
            voltage = COALESCE(:voltage, voltage),
            channelUtilization = COALESCE(:channelUtilization, channelUtilization),
            airUtilTx = COALESCE(:airUtilTx, airUtilTx)
        WHERE nodeNum = :nodeNum
        """,
    )
    suspend fun updateMetrics(
        nodeNum: Int,
        batteryLevel: Int?,
        voltage: Float?,
        channelUtilization: Float?,
        airUtilTx: Float?,
    )

    /** Signal figures come from the packet in hand, so a null means this hop did not measure it. */
    @Query(
        """
        UPDATE nodes SET
            lastHeard = :heardAt,
            snr = COALESCE(:snr, snr),
            rssi = COALESCE(:rssi, rssi),
            hopsAway = COALESCE(:hopsAway, hopsAway)
        WHERE nodeNum = :nodeNum
        """,
    )
    suspend fun markHeard(nodeNum: Int, heardAt: Long, snr: Float?, rssi: Int?, hopsAway: Int?)

    /** Forgets where nodes were, once that is older than the retention window. */
    @Query(
        """
        UPDATE nodes SET latitudeI = NULL, longitudeI = NULL, altitude = NULL, positionTime = NULL,
            positionPrecision = NULL, groundSpeed = NULL, groundTrack = NULL
        WHERE latitudeI IS NOT NULL AND (positionTime IS NULL OR positionTime < :cutoff)
        """,
    )
    suspend fun forgetPositionsBefore(cutoff: Long): Int

    /**
     * Forgets nodes not heard within the window who share no room with us,
     * along with their battery and signal. [keep] is our own node.
     */
    @Query(
        """
        DELETE FROM nodes WHERE nodeNum != :keep AND (lastHeard IS NULL OR lastHeard < :cutoff)
            AND nodeNum NOT IN (SELECT nodeNum FROM room_members)
        """,
    )
    suspend fun forgetStrangersBefore(cutoff: Long, keep: Int): Int

    @Query(
        """
        UPDATE nodes SET latitudeI = NULL, longitudeI = NULL, altitude = NULL, positionTime = NULL,
            positionPrecision = NULL, groundSpeed = NULL, groundTrack = NULL
        """,
    )
    suspend fun forgetAllPositions()
}

fun NodeDao.observeAll(): Flow<List<MeshNode>> =
    observeAllEntities().map { entities -> entities.map(NodeEntity::toDomain) }

suspend fun NodeDao.find(nodeNum: Int): MeshNode? = findEntity(nodeNum)?.toDomain()

suspend fun NodeDao.save(node: MeshNode, now: Long) {
    val existing = findEntity(node.nodeNum)
    // NodeInfo often arrives without a position. Writing the node wholesale
    // would then erase a fix learned from a position packet, so a known point
    // is carried forward unless the update actually replaces it.
    val merged = node.copy(
        latitudeI = node.latitudeI ?: existing?.latitudeI,
        longitudeI = node.longitudeI ?: existing?.longitudeI,
        altitude = node.altitude ?: existing?.altitude,
        positionTime = node.positionTime ?: existing?.positionTime,
        positionPrecision = node.positionPrecision ?: existing?.positionPrecision,
    )
    upsert(merged.toEntity(firstSeen = existing?.firstSeen ?: now))
}

suspend fun MessageDao.save(message: ChatMessage, myNodeNum: Int) = upsert(message.toEntity(myNodeNum))

suspend fun MessageDao.saveIfNew(message: ChatMessage, myNodeNum: Int): Boolean =
    insertIfNew(message.toEntity(myNodeNum)) != -1L

suspend fun MessageDao.find(id: Int): ChatMessage? = findEntity(id)?.toDomain()

@Dao
interface MapPinDao {

    @Query("SELECT * FROM map_pins WHERE expire = 0 OR expire > :nowSeconds")
    fun observeLiveEntities(nowSeconds: Long): Flow<List<MapPinEntity>>

    @Query("SELECT * FROM map_pins WHERE id = :id")
    suspend fun findEntity(id: Int): MapPinEntity?

    @Upsert
    suspend fun upsert(pin: MapPinEntity)

    @Query("DELETE FROM map_pins WHERE id = :id")
    suspend fun delete(id: Int)

    @Upsert
    suspend fun remember(deleted: DeletedPinEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM deleted_pins WHERE id = :id)")
    suspend fun wasDeleted(id: Int): Boolean

    @Query("SELECT * FROM deleted_pins WHERE deletedAt > :since")
    suspend fun recentlyDeleted(since: Long): List<DeletedPinEntity>

    /** Pins filed under no room follow their slot when the rooms shift, the way their messages do. */
    @Query("UPDATE map_pins SET channel = :to WHERE channel = :from AND roomId = 0")
    suspend fun moveUnfiled(from: Int, to: Int)

    @Query("DELETE FROM map_pins WHERE channel = :slot AND roomId = 0")
    suspend fun deleteUnfiled(slot: Int)

    @Query("SELECT DISTINCT channel, roomId FROM map_pins")
    suspend fun placements(): List<RoomPlacement>

    /** Brings a room's pins to the slot the room is in now. */
    @Query("UPDATE map_pins SET channel = :slot WHERE roomId = :roomId AND channel != :slot")
    suspend fun placeRoom(roomId: Int, slot: Int)

    @Query("UPDATE map_pins SET roomId = :roomId WHERE channel = :slot AND roomId = 0")
    suspend fun stampSlot(slot: Int, roomId: Int)

    @Query("UPDATE map_pins SET channel = :parked WHERE channel = :slot AND roomId != 0 AND roomId != :keep")
    suspend fun parkOtherRooms(slot: Int, keep: Int, parked: Int)

    @Query("UPDATE map_pins SET channel = :parking WHERE channel = :slot AND roomId = 0")
    suspend fun parkUnfiled(slot: Int, parking: Int)

    @Query("UPDATE map_pins SET channel = :slot WHERE channel = :parking AND roomId = 0")
    suspend fun restoreUnfiled(slot: Int, parking: Int)

    @Query("DELETE FROM map_pins WHERE roomId = :roomId")
    suspend fun deleteRoom(roomId: Int)

    /** Expired pins are hidden as they are read; this is what actually removes them. */
    @Query("DELETE FROM map_pins WHERE expire != 0 AND expire < :nowSeconds")
    suspend fun deleteExpired(nowSeconds: Long): Int

    @Query("DELETE FROM map_pins")
    suspend fun deleteAll()

    /** A deletion only needs remembering while somebody could still resend the pin. */
    @Query("DELETE FROM deleted_pins WHERE deletedAt < :cutoff")
    suspend fun forgetDeletedBefore(cutoff: Long): Int

    @Query("DELETE FROM deleted_pins")
    suspend fun forgetAllDeleted()
}

/** Expired pins are filtered in SQL so a stale one never reaches the map. */
fun MapPinDao.observeLive(nowMillis: Long = System.currentTimeMillis()): Flow<List<MapPin>> =
    observeLiveEntities(nowMillis / 1000L).map { entities -> entities.map(MapPinEntity::toDomain) }

suspend fun MapPinDao.save(pin: MapPin) = upsert(pin.toEntity())

suspend fun MapPinDao.find(id: Int): MapPin? = findEntity(id)?.toDomain()

@Dao
interface ChannelStateDao {

    @Query("SELECT * FROM channel_state")
    fun observeAll(): Flow<List<ChannelStateEntity>>

    @Query("SELECT * FROM channel_state WHERE channel = :channel")
    suspend fun find(channel: Int): ChannelStateEntity?

    @Upsert
    suspend fun upsert(state: ChannelStateEntity)

    /** Marks read state kept before rooms were recorded as belonging to the room in its slot now. */
    @Query("UPDATE channel_state SET roomId = :roomId WHERE channel = :slot AND roomId = 0")
    suspend fun stampSlot(slot: Int, roomId: Int)

    /**
     * Unread counts per channel. Channels with no row yet are absent, so a
     * conversation is only "unread" once it has been opened at least once or
     * has messages newer than its mark. Reactions are not counted: they do not
     * notify either (UX §5.4).
     */
    @Query(
        """
        SELECT m.channel AS channel, COUNT(*) AS count
        FROM messages m
        LEFT JOIN channel_state s ON s.channel = m.channel
        WHERE m.isOutgoing = 0 AND m.sentAt > COALESCE(s.lastReadAt, 0)
        AND (COALESCE(m.emoji, 0) = 0 OR m.replyId IS NULL)
        GROUP BY m.channel
        """,
    )
    fun observeUnread(): Flow<List<UnreadCount>>
}

data class UnreadCount(val channel: Int, val count: Int)

suspend fun ChannelStateDao.markRead(channel: Int, now: Long, roomId: Int = 0) {
    val existing = find(channel)?.takeIf { it.roomId == roomId }
    upsert(ChannelStateEntity(channel, lastReadAt = now, muted = existing?.muted == true, roomId = roomId))
}

suspend fun ChannelStateDao.setMuted(channel: Int, muted: Boolean, roomId: Int = 0) {
    val existing = find(channel)?.takeIf { it.roomId == roomId }
    upsert(ChannelStateEntity(channel, lastReadAt = existing?.lastReadAt ?: 0L, muted = muted, roomId = roomId))
}

/**
 * Starts a slot afresh when a different room is in it now, so a room carried by
 * another radio does not inherit the last room's read position or mute. Its own
 * mute, [roomMuted], comes with it.
 */
suspend fun ChannelStateDao.followRoom(channel: Int, roomId: Int, now: Long, roomMuted: Boolean) {
    val existing = find(channel) ?: return
    if (existing.roomId != roomId) upsert(ChannelStateEntity(channel, lastReadAt = now, muted = roomMuted, roomId = roomId))
}

fun ChannelStateDao.observeMuted(): Flow<Set<Int>> =
    observeAll().map { states -> states.filter { it.muted }.map { it.channel }.toSet() }

@Dao
interface RoomMemberDao {

    // NULLs sort last in SQLite, so members we have only been told about fall
    // below the ones we have actually heard.
    @Query("SELECT * FROM room_members WHERE roomId = :roomId ORDER BY lastHeard DESC")
    fun observeRoomEntities(roomId: Int): Flow<List<RoomMemberEntity>>

    @Query("SELECT * FROM room_members WHERE roomId = :roomId AND nodeNum = :nodeNum")
    suspend fun findEntity(roomId: Int, nodeNum: Int): RoomMemberEntity?

    @Upsert
    suspend fun upsert(member: RoomMemberEntity)

    @Query("DELETE FROM room_members WHERE roomId = :roomId AND nodeNum = :nodeNum")
    suspend fun remove(roomId: Int, nodeNum: Int)

    @Query("SELECT nodeNum FROM room_members WHERE roomId = :roomId")
    suspend fun nodeNumsIn(roomId: Int): List<Int>

    /** Everyone in any room this phone is in. Leaving a room deletes its rows, so this stays honest. */
    @Query("SELECT DISTINCT nodeNum FROM room_members")
    fun observeAllNodeNums(): Flow<List<Int>>

    /** Whether [nodeNum] is in any room this phone is in. */
    @Query("SELECT EXISTS(SELECT 1 FROM room_members WHERE nodeNum = :nodeNum)")
    suspend fun isInAnyRoom(nodeNum: Int): Boolean

    @Query("DELETE FROM room_members WHERE roomId = :roomId")
    suspend fun deleteRoom(roomId: Int)
}

fun RoomMemberDao.observeRoom(roomId: Int): Flow<List<RoomMember>> =
    observeRoomEntities(roomId).map { entities -> entities.map(RoomMemberEntity::toDomain) }

/**
 * Records having actually heard somebody. [invitedBy] only ever gets set, never
 * cleared, so hearing a vouched member speak does not downgrade them.
 */
suspend fun RoomMemberDao.record(roomId: Int, nodeNum: Int, now: Long, invitedBy: Int? = null) {
    val existing = findEntity(roomId, nodeNum)
    upsert(
        RoomMemberEntity(
            roomId = roomId,
            nodeNum = nodeNum,
            invitedBy = invitedBy ?: existing?.invitedBy,
            firstSeen = existing?.firstSeen ?: now,
            lastHeard = maxOf(now, existing?.lastHeard ?: now),
        ),
    )
}

/**
 * Records somebody another member told us about. Never sets [lastHeard]: we
 * have not heard them, and saying otherwise would dress up hearsay as a
 * sighting.
 */
suspend fun RoomMemberDao.recordReported(roomId: Int, nodeNum: Int, now: Long, invitedBy: Int?) {
    val existing = findEntity(roomId, nodeNum)
    upsert(
        RoomMemberEntity(
            roomId = roomId,
            nodeNum = nodeNum,
            invitedBy = invitedBy ?: existing?.invitedBy,
            firstSeen = existing?.firstSeen ?: now,
            lastHeard = existing?.lastHeard,
        ),
    )
}

@Dao
interface ReceiptDao {

    @Query("SELECT * FROM receipts WHERE messageId = :messageId ORDER BY at ASC")
    fun observeFor(messageId: Int): Flow<List<ReceiptEntity>>

    @Query("SELECT * FROM receipts WHERE messageId IN (:messageIds)")
    fun observeForAll(messageIds: List<Int>): Flow<List<ReceiptEntity>>

    /**
     * Inserts only for a message this phone actually has.
     *
     * The WHERE EXISTS is not belt and braces: OR IGNORE resolves uniqueness,
     * not foreign keys, so a receipt naming a message we never received would
     * otherwise throw. Arriving before the message it describes is ordinary on
     * a mesh, and is not an error.
     *
     * OR IGNORE then leaves an existing row alone, so a repeated delivery
     * receipt cannot walk a read one backwards.
     */
    @Query(
        "INSERT OR IGNORE INTO receipts (messageId, nodeNum, state, at) " +
            "SELECT :messageId, :nodeNum, 'RECEIVED', :at " +
            "WHERE EXISTS (SELECT 1 FROM messages WHERE id = :messageId)",
    )
    suspend fun recordReceived(messageId: Int, nodeNum: Int, at: Long)

    @Query(
        "INSERT OR IGNORE INTO receipts (messageId, nodeNum, state, at) " +
            "SELECT :messageId, :nodeNum, 'READ', :at " +
            "WHERE EXISTS (SELECT 1 FROM messages WHERE id = :messageId)",
    )
    suspend fun insertRead(messageId: Int, nodeNum: Int, at: Long)

    @Query(
        "UPDATE receipts SET state = 'READ', at = :at " +
            "WHERE messageId = :messageId AND nodeNum = :nodeNum AND state != 'READ'",
    )
    suspend fun promoteToRead(messageId: Int, nodeNum: Int, at: Long)
}

/** Reading is the stronger claim, so it is applied whether or not a row exists. */
suspend fun ReceiptDao.recordRead(messageId: Int, nodeNum: Int, at: Long) {
    insertRead(messageId, nodeNum, at)
    promoteToRead(messageId, nodeNum, at)
}

fun ReceiptDao.observe(messageId: Int): Flow<List<Receipt>> =
    observeFor(messageId).map { rows -> rows.map { Receipt(it.nodeNum, it.state, it.at) } }

@Dao
interface PersonCardDao {

    @Query("SELECT * FROM person_cards")
    fun observeAllEntities(): Flow<List<PersonCardEntity>>

    @Upsert
    suspend fun upsert(card: PersonCardEntity)

    @Query("DELETE FROM person_cards WHERE nodeNum = :nodeNum")
    suspend fun forget(nodeNum: Int)

    /** Cards of people we no longer share any room with. */
    @Query("DELETE FROM person_cards WHERE nodeNum NOT IN (SELECT nodeNum FROM room_members)")
    suspend fun forgetOutsideRooms(): Int

    @Query("DELETE FROM person_cards")
    suspend fun deleteAll()
}

fun PersonCardDao.observeAll(): Flow<Map<Int, PersonCard>> =
    observeAllEntities().map { rows -> rows.associate { it.nodeNum to it.toDomain() } }

@Dao
interface PeerKeyDao {

    @Query("SELECT * FROM peer_keys WHERE nodeNum = :nodeNum")
    suspend fun find(nodeNum: Int): PeerKeyEntity?

    /** Whether words to [nodeNum] can be sealed to their phone, as it changes. */
    @Query("SELECT EXISTS(SELECT 1 FROM peer_keys WHERE nodeNum = :nodeNum)")
    fun observeKnown(nodeNum: Int): Flow<Boolean>

    @Upsert
    suspend fun upsert(key: PeerKeyEntity)

    /** Keys of people we no longer share any room with; the next room teaches them again. */
    @Query("DELETE FROM peer_keys WHERE nodeNum NOT IN (SELECT nodeNum FROM room_members)")
    suspend fun forgetOutsideRooms(): Int
}

@Dao
interface RoomActivityDao {

    @Query("SELECT * FROM room_activity")
    suspend fun all(): List<RoomActivityEntity>

    /** Starts the clock on a room, once; later calls leave the first time alone. */
    @Query("INSERT OR IGNORE INTO room_activity (roomId, joinedAt, lastActivityAt) VALUES (:roomId, :now, :now)")
    suspend fun joined(roomId: Int, now: Long)

    @Query("UPDATE room_activity SET lastActivityAt = MAX(lastActivityAt, :now) WHERE roomId = :roomId")
    suspend fun touch(roomId: Int, now: Long)

    @Query("DELETE FROM room_activity WHERE roomId = :roomId")
    suspend fun forget(roomId: Int)

    /** Whether the room is muted, whichever radio or slot carries it. */
    @Query("SELECT EXISTS(SELECT 1 FROM room_activity WHERE roomId = :roomId AND muted = 1)")
    suspend fun isMuted(roomId: Int): Boolean

    @Query("UPDATE room_activity SET muted = :muted WHERE roomId = :roomId")
    suspend fun updateMuted(roomId: Int, muted: Boolean)
}

/** Remembers a room's mute against the room, so it survives the room moving slot or radio. */
suspend fun RoomActivityDao.setMuted(roomId: Int, muted: Boolean, now: Long) {
    joined(roomId, now)
    updateMuted(roomId, muted)
}

/** Something happened in [roomId]; a room first seen here counts as joined now. */
suspend fun RoomActivityDao.recordActivity(roomId: Int, now: Long) {
    joined(roomId, now)
    touch(roomId, now)
}

@Dao
interface PendingHandoverDao {

    @Upsert
    suspend fun upsert(handover: PendingHandoverEntity)

    @Query("SELECT * FROM pending_handovers WHERE nodeNum = :nodeNum")
    suspend fun forNode(nodeNum: Int): List<PendingHandoverEntity>

    @Query("SELECT * FROM pending_handovers WHERE roomId = :roomId")
    suspend fun forRoom(roomId: Int): List<PendingHandoverEntity>

    @Query("SELECT * FROM pending_handovers")
    suspend fun all(): List<PendingHandoverEntity>

    @Query("DELETE FROM pending_handovers WHERE roomId = :roomId AND nodeNum = :nodeNum")
    suspend fun delete(roomId: Int, nodeNum: Int)

    /**
     * Notes another attempt, only while the record is still the one that was
     * tried: one settled, or replaced by a later rotation, meanwhile stays as
     * it is. How many records it touched.
     */
    @Query(
        "UPDATE pending_handovers SET lastTriedAt = :at " +
            "WHERE roomId = :roomId AND nodeNum = :nodeNum AND generation = :generation",
    )
    suspend fun touch(roomId: Int, nodeNum: Int, generation: Int, at: Long): Int

    /**
     * Clears what is owed to a member up to [generation], and no further: a
     * later rotation's record, written meanwhile, stays. How many it cleared.
     */
    @Query(
        "DELETE FROM pending_handovers " +
            "WHERE roomId = :roomId AND nodeNum = :nodeNum AND generation <= :generation",
    )
    suspend fun deleteUpTo(roomId: Int, nodeNum: Int, generation: Int): Int

    @Query("DELETE FROM pending_handovers WHERE roomId = :roomId")
    suspend fun deleteRoom(roomId: Int)
}

/** When a channel last carried anything, for deciding whether a room has died. */
data class ChannelActivity(val channel: Int, val lastAt: Long)

/** A slot and the room whose history or pins are stored against it. */
data class RoomPlacement(val channel: Int, val roomId: Int)

/** Off every slot the screen can show, for history of a room not on this radio. */
const val PARKED_CHANNEL = -1

/**
 * Where a slot's unfiled history waits while a room holds the slot: one place
 * per slot, below every other channel number, so it goes back where it was.
 */
fun unfiledParkingFor(slot: Int): Int = UNFILED_PARKING_BASE - slot

private const val UNFILED_PARKING_BASE = -100

suspend fun MessageDao.placements(): List<RoomPlacement> = placements(BROADCAST_NODE_NUM)

suspend fun MessageDao.placeRoom(roomId: Int, slot: Int) = placeRoom(roomId, slot, BROADCAST_NODE_NUM)

suspend fun MessageDao.stampSlot(slot: Int, roomId: Int) = stampSlot(slot, roomId, BROADCAST_NODE_NUM)

suspend fun MessageDao.parkOtherRooms(slot: Int, keep: Int) = parkOtherRooms(slot, keep, PARKED_CHANNEL, BROADCAST_NODE_NUM)

suspend fun MessageDao.parkUnfiled(slot: Int) = parkUnfiled(slot, unfiledParkingFor(slot), BROADCAST_NODE_NUM)

suspend fun MessageDao.restoreUnfiled(slot: Int) = restoreUnfiled(slot, unfiledParkingFor(slot), BROADCAST_NODE_NUM)

suspend fun MessageDao.deleteUnfiled(slot: Int) = deleteUnfiled(slot, BROADCAST_NODE_NUM)

suspend fun MessageDao.moveUnfiled(from: Int, to: Int) = moveUnfiled(from, to, BROADCAST_NODE_NUM)

suspend fun MessageDao.deleteRoom(roomId: Int) = deleteRoom(roomId, BROADCAST_NODE_NUM)

suspend fun MapPinDao.parkOtherRooms(slot: Int, keep: Int) = parkOtherRooms(slot, keep, PARKED_CHANNEL)

suspend fun MapPinDao.parkUnfiled(slot: Int) = parkUnfiled(slot, unfiledParkingFor(slot))

suspend fun MapPinDao.restoreUnfiled(slot: Int) = restoreUnfiled(slot, unfiledParkingFor(slot))

suspend fun MessageDao.newestPerChannel(): Map<Int, Long> =
    newestPerChannel(BROADCAST_NODE_NUM).associate { it.channel to it.lastAt }
