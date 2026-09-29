package com.getfirepit.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.getfirepit.core.model.ChatMessage
import com.getfirepit.core.model.MessageStatus

/**
 * Keyed by mesh packet id, which the firmware also uses to correlate
 * acknowledgements and to suppress duplicates. Re-inserting a redelivered
 * packet therefore replaces it rather than duplicating the conversation.
 */
@Entity(
    tableName = "messages",
    indices = [Index("channel", "sentAt"), Index("peerNodeNum", "sentAt"), Index("roomId")],
)
data class MessageEntity(
    @PrimaryKey val id: Int,
    val channel: Int,
    val fromNodeNum: Int,
    val toNodeNum: Int,
    /** The other party, so a direct conversation can be queried without branching. */
    val peerNodeNum: Int,
    val text: String,
    val sentAt: Long,
    val rxTime: Long?,
    val status: MessageStatus,
    val failureReason: String?,
    val isOutgoing: Boolean,
    val rxSnr: Float?,
    val rxRssi: Int?,
    val hopsAway: Int?,
    val replyId: Int?,
    val emoji: Int?,
    @ColumnInfo(defaultValue = "0") val signed: Boolean,
    /**
     * The room a room message belongs to, or 0. [channel] is only where that
     * room sits on the radio connected now; another radio can carry another
     * room in the same slot, so history is placed by this, not by the number.
     */
    @ColumnInfo(defaultValue = "0") val roomId: Int = 0,
)

internal fun MessageEntity.toDomain() = ChatMessage(
    id = id,
    channel = channel,
    fromNodeNum = fromNodeNum,
    toNodeNum = toNodeNum,
    text = text,
    sentAt = sentAt,
    rxTime = rxTime,
    status = status,
    failureReason = failureReason,
    isOutgoing = isOutgoing,
    rxSnr = rxSnr,
    rxRssi = rxRssi,
    hopsAway = hopsAway,
    replyId = replyId,
    emoji = emoji,
    signed = signed,
    roomId = roomId,
)

internal fun ChatMessage.toEntity(myNodeNum: Int) = MessageEntity(
    id = id,
    channel = channel,
    fromNodeNum = fromNodeNum,
    toNodeNum = toNodeNum,
    peerNodeNum = if (isOutgoing) toNodeNum else fromNodeNum.takeIf { it != myNodeNum } ?: toNodeNum,
    text = text,
    sentAt = sentAt,
    rxTime = rxTime,
    status = status,
    failureReason = failureReason,
    isOutgoing = isOutgoing,
    rxSnr = rxSnr,
    rxRssi = rxRssi,
    hopsAway = hopsAway,
    replyId = replyId,
    emoji = emoji,
    signed = signed,
    roomId = roomId,
)
