package com.getfirepit.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-channel read position and notification preference.
 *
 * Keyed by slot index rather than room id because the primary channel has no
 * room id, and unread state is about a conversation on screen. [roomId] says
 * which room the state was kept for, so a different radio carrying a different
 * room in the same slot starts afresh rather than inheriting it.
 */
@Entity(tableName = "channel_state")
data class ChannelStateEntity(
    @PrimaryKey val channel: Int,
    /** Messages at or before this instant have been seen. */
    val lastReadAt: Long,
    val muted: Boolean,
    @ColumnInfo(defaultValue = "0") val roomId: Int = 0,
)
