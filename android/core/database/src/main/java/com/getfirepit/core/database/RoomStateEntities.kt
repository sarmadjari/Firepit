package com.getfirepit.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * When a room was joined and when it last carried anything.
 *
 * Kept apart from the messages because the retention sweep deletes those, and
 * "a month of silence" has to be judged on what happened, not on what is left.
 */
@Entity(tableName = "room_activity")
data class RoomActivityEntity(
    @PrimaryKey val roomId: Int,
    val joinedAt: Long,
    val lastActivityAt: Long,
    /** Kept against the room rather than its slot, which another radio may give another room. */
    @ColumnInfo(defaultValue = "0") val muted: Boolean = false,
)

/**
 * A member a new room key has not reached yet.
 *
 * Kept on disk so a member who was out of range is handed the key when they are
 * next heard, even after a restart. Nothing secret is stored: the keys
 * themselves are read back from the radio and the key store when it is resent.
 */
@Entity(tableName = "pending_handovers", primaryKeys = ["roomId", "nodeNum"])
data class PendingHandoverEntity(
    val roomId: Int,
    val nodeNum: Int,
    /** The generation they have not received. */
    val generation: Int,
    /**
     * The generation they do hold, which the handover is sealed under. Not
     * always one less: a member who missed one rotation and then another
     * still holds only the key from before the first.
     */
    val heldGeneration: Int,
    /** Node numbers removed by that rotation, comma separated. */
    val removed: String,
    val createdAt: Long,
    val lastTriedAt: Long,
)
