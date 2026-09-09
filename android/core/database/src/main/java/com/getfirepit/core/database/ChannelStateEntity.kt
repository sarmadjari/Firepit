package com.getfirepit.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-channel read position and notification preference.
 *
 * Keyed by slot index rather than room id because the primary channel has no
 * room id, and unread state is about a conversation on screen, not about a
 * room's identity across re-indexing.
 */
@Entity(tableName = "channel_state")
data class ChannelStateEntity(
    @PrimaryKey val channel: Int,
    /** Messages at or before this instant have been seen. */
    val lastReadAt: Long,
    val muted: Boolean,
)
