package com.getfirepit.core.protocol

import com.getfirepit.core.model.RoomChannel

/**
 * Where position sharing is allowed to happen.
 *
 * Firepit shares location on exactly one channel, ever. The radio broadcasts a
 * position on every channel whose precision is non-zero, so two enabled
 * channels means two audiences receive it — including one the user never chose.
 * That is a privacy failure, not a preference, so the rule lives here as data
 * the repository can assert on every reconnect rather than as a UI convention.
 */
object PositionSharing {

    /** Channels currently configured to transmit our position. */
    fun sharingChannels(channels: List<RoomChannel>): List<RoomChannel> =
        channels.filter { it.positionPrecision > PositionPrecision.DISABLED }

    /** True when no channel, or exactly one, transmits position. */
    fun isValid(channels: List<RoomChannel>): Boolean = sharingChannels(channels).size <= 1

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
        val wanted = if (roomId != null && channel.id == roomId && channel.id != 0) {
            precision
        } else {
            PositionPrecision.DISABLED
        }
        PrecisionWrite(channel.index, wanted).takeIf { channel.positionPrecision != wanted }
    }
}

data class PrecisionWrite(val index: Int, val precision: Int)
