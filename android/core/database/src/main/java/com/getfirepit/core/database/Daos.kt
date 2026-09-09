package com.getfirepit.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.getfirepit.core.model.ChatMessage
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.model.MessageStatus
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
