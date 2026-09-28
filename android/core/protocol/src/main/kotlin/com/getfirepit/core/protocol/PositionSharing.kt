package com.getfirepit.core.protocol

import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.model.RoomKind

/**
 * Where position sharing is allowed to happen.
 *
 * Firepit shares location on exactly one channel, ever, and never on the
 * primary, whose key every Meshtastic radio already has.
 *
 * The firmware picks the audience itself, and it picks only one: `sendOurPosition`
 * walks channels 0..7, transmits on the first whose `position_precision` is
 * non-zero, and returns. So the lowest-numbered enabled channel wins outright —
 * a shared Meshtastic channel sitting below the room would capture every
 * broadcast *instead of* it. Precision 0 is a real refusal rather than a
 * rounding: `allocPositionPacket` returns nothing at all, which is also why a
 * position request arriving on the primary goes unanswered.
 *
 * That is a privacy failure, not a preference, so the rule lives here as data
 * the repository can assert on every reconnect rather than as a UI convention.
 */
object PositionSharing {

    /**
     * True when a channel may carry our position at all.
     *
     * Only a Firepit room: its key never reaches a radio, so the audience is
     * the people holding the phones we invited and nobody else. A shared
     * Meshtastic channel reaches people the group never chose.
     */
    fun canShare(channel: RoomChannel): Boolean =
        channel.isRoom && channel.kind == RoomKind.FIREPIT

    /** Channels currently configured to transmit our position. */
    fun sharingChannels(channels: List<RoomChannel>): List<RoomChannel> =
        channels.filter { it.positionPrecision > PositionPrecision.DISABLED }

    /** True when at most one channel transmits position, and it is a Firepit room. */
    fun isValid(channels: List<RoomChannel>): Boolean {
        val sharing = sharingChannels(channels)
        return sharing.size <= 1 && sharing.all(::canShare)
    }

    /**
     * The writes needed so only [roomId] shares position, or none at all when
     * it is null. Returns nothing when the radio already agrees, so a reconnect
     * check is silent in the normal case.
     *
     * A room that may not share is treated as no room rather than refused: the
     * result is that sharing is turned off everywhere, which is the safe
     * reading of an instruction that cannot be honoured.
     */
    fun writesToShareOnly(
        channels: List<RoomChannel>,
        roomId: Int?,
        precision: Int,
    ): List<PrecisionWrite> = channels.mapNotNull { channel ->
        val wanted = if (roomId != null && canShare(channel) && channel.id == roomId) {
            precision
        } else {
            PositionPrecision.DISABLED
        }
        PrecisionWrite(channel.index, wanted).takeIf { channel.positionPrecision != wanted }
    }
}

data class PrecisionWrite(val index: Int, val precision: Int)
