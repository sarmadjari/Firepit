package com.getfirepit.core.protocol

import com.getfirepit.core.model.RoomChannel

/**
 * Where position sharing is allowed to happen.
 *
 * Firepit shares location on exactly one channel, ever, and never on the
 * primary. The radio broadcasts a position on every channel whose precision is
 * non-zero, so two enabled channels means two audiences receive it — and the
 * primary is the audience nobody chose, since the firmware sends its own
 * periodic broadcasts there and its key is one every Meshtastic radio has.
 * That is a privacy failure, not a preference, so the rule lives here as data
 * the repository can assert on every reconnect rather than as a UI convention.
 */
object PositionSharing {

    /** Channels currently configured to transmit our position. */
    fun sharingChannels(channels: List<RoomChannel>): List<RoomChannel> =
        channels.filter { it.positionPrecision > PositionPrecision.DISABLED }

    /** True when at most one room, and never the primary, transmits position. */
    fun isValid(channels: List<RoomChannel>): Boolean {
        val sharing = sharingChannels(channels)
        return sharing.size <= 1 && sharing.all { it.isRoom }
    }

    /**
     * The writes needed so only [roomId] shares position, or none at all when
     * it is null. Returns nothing when the radio already agrees, so a reconnect
     * check is silent in the normal case.
     */
    fun writesToShareOnly(
        channels: List<RoomChannel>,
        roomId: Int?,
        precision: Int,
    ): List<PrecisionWrite> = channels.mapNotNull { channel ->
        val wanted = if (roomId != null && channel.isRoom && channel.id == roomId) {
            precision
        } else {
            PositionPrecision.DISABLED
        }
        PrecisionWrite(channel.index, wanted).takeIf { channel.positionPrecision != wanted }
    }
}

data class PrecisionWrite(val index: Int, val precision: Int)
