package com.getfirepit.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.getfirepit.core.model.ChatMessage
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
}

fun NodeDao.observeAll(): Flow<List<MeshNode>> =
    observeAllEntities().map { entities -> entities.map(NodeEntity::toDomain) }

suspend fun NodeDao.find(nodeNum: Int): MeshNode? = findEntity(nodeNum)?.toDomain()

suspend fun NodeDao.save(node: MeshNode, now: Long) {
    val existing = findEntity(node.nodeNum)
    upsert(node.toEntity(firstSeen = existing?.firstSeen ?: now))
}

suspend fun MessageDao.save(message: ChatMessage, myNodeNum: Int) = upsert(message.toEntity(myNodeNum))

suspend fun MessageDao.saveIfNew(message: ChatMessage, myNodeNum: Int): Boolean =
    insertIfNew(message.toEntity(myNodeNum)) != -1L

suspend fun MessageDao.find(id: Int): ChatMessage? = findEntity(id)?.toDomain()

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
