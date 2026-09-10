package com.getfirepit.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.getfirepit.core.model.ChatMessage
import com.getfirepit.core.model.MapPin
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.model.MessageStatus
import com.getfirepit.core.model.RoomMember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE channel = :channel ORDER BY sentAt ASC")
    fun observeChannelEntities(channel: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun findEntity(id: Int): MessageEntity?

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
}

fun MessageDao.observeChannel(channel: Int): Flow<List<ChatMessage>> =
    observeChannelEntities(channel).map { entities -> entities.map(MessageEntity::toDomain) }

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
        positionTime = :positionTime, positionPrecision = :positionPrecision
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
    )
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
}

/** Expired pins are filtered in SQL so a stale one never reaches the map. */
fun MapPinDao.observeLive(nowMillis: Long = System.currentTimeMillis()): Flow<List<MapPin>> =
    observeLiveEntities(nowMillis / 1000L).map { entities -> entities.map(MapPinEntity::toDomain) }

suspend fun MapPinDao.save(pin: MapPin) = upsert(pin.toEntity())

@Dao
interface ChannelStateDao {

    @Query("SELECT * FROM channel_state")
    fun observeAll(): Flow<List<ChannelStateEntity>>

    @Query("SELECT * FROM channel_state WHERE channel = :channel")
    suspend fun find(channel: Int): ChannelStateEntity?

    @Upsert
    suspend fun upsert(state: ChannelStateEntity)

    /**
     * Unread counts per channel. Channels with no row yet are absent, so a
     * conversation is only "unread" once it has been opened at least once or
     * has messages newer than its mark.
     */
    @Query(
        """
        SELECT m.channel AS channel, COUNT(*) AS count
        FROM messages m
        LEFT JOIN channel_state s ON s.channel = m.channel
        WHERE m.isOutgoing = 0 AND m.sentAt > COALESCE(s.lastReadAt, 0)
        GROUP BY m.channel
        """,
    )
    fun observeUnread(): Flow<List<UnreadCount>>
}

data class UnreadCount(val channel: Int, val count: Int)

suspend fun ChannelStateDao.markRead(channel: Int, now: Long) {
    val existing = find(channel)
    upsert(ChannelStateEntity(channel, lastReadAt = now, muted = existing?.muted == true))
}

suspend fun ChannelStateDao.setMuted(channel: Int, muted: Boolean) {
    val existing = find(channel)
    upsert(ChannelStateEntity(channel, lastReadAt = existing?.lastReadAt ?: 0L, muted = muted))
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
