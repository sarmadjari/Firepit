package com.getfirepit.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.getfirepit.core.model.ReceiptState

/**
 * Who has received a message and who has opened it.
 *
 * A child of the message rather than a table of its own: the cascade means a
 * receipt cannot outlive what it describes, so the retention sweep and leaving
 * a room both clear these without knowing they exist.
 *
 * Kept for every member's messages, not only our own. In a room the point is
 * that everyone can see whether everyone is informed.
 */
@Entity(
    tableName = "receipts",
    primaryKeys = ["messageId", "nodeNum"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class ReceiptEntity(
    val messageId: Int,
    val nodeNum: Int,
    val state: ReceiptState,
    val at: Long,
)
