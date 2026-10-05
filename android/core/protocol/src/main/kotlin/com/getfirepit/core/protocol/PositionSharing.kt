package com.getfirepit.core.protocol

import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.model.RoomKind
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Config

/**
 * Where a position may go, and when the radio may answer as a safety net.
 *
 * Every position Firepit shares is sealed by the phone under a room's own key,
 * so the radios carrying it read nothing. The firmware's own broadcast would
 * undo that: it goes out under the channel key, which anyone holding a member's
 * radio has, and it carries on with the phone switched off or out of range.
 * Precision therefore stays 0 on every channel, room and primary alike unless
 * a person opts into the safety net for one live share.
 *
 * The firmware only broadcasts on a channel whose `position_precision` is
 * non-zero (`sendOurPosition` walks slots 0..7 for the first), and precision 0
 * also makes it refuse to answer a position request, so zero everywhere means
 * the radio never speaks for us about where we are. That is re-asserted on
 * every connection rather than trusted, because another app, another phone or
 * a factory reset can change it between sessions.
 */
object PositionSharing {

    /**
     * True when a room may receive our sealed position at all.
     *
     * Only a Firepit room whose key we hold: its key never reaches a radio, so
     * the audience is the people holding the phones we invited. A shared
     * Meshtastic channel reaches people the group never chose, and a room we
     * cannot seal for has no private way to carry it.
     */
    fun canShare(channel: RoomChannel): Boolean =
        channel.isRoom && channel.kind == RoomKind.FIREPIT

    /** Channels on which the radio itself would broadcast our position. */
    fun broadcastingChannels(channels: List<RoomChannel>): List<RoomChannel> =
        channels.filter { it.positionPrecision > PositionPrecision.DISABLED }

    /** True when the radio broadcasts our position nowhere. */
    fun isSilent(channels: List<RoomChannel>): Boolean = broadcastingChannels(channels).isEmpty()

    fun safetyNetSlot(
        channels: List<RoomChannel>,
        share: SafetyNetShare?,
        nowMillis: Long,
        connectedNodeNum: Int?,
        positionConfig: Config.PositionConfig?,
        heldRoomIds: Set<Int>,
        primaryKey: ByteString,
        licensedMode: Boolean = false,
    ): Int? {
        if (share == null ||
            !share.radioSafetyNet ||
            share.hasPassed(nowMillis) ||
            share.safetyNetNodeNum != connectedNodeNum ||
            licensedMode ||
            !radioSafetyNetConfigReady(positionConfig)
        ) return null

        val matches = channels.filter { channel ->
            val psk = channel.psk?.toByteString()
            channel.isRoom &&
                channel.id == share.roomId &&
                channel.kind == RoomKind.FIREPIT &&
                heldRoomIds.contains(channel.id) &&
                psk?.size == 32 &&
                psk != primaryKey
        }
        return matches.singleOrNull()?.index
    }

    /**
     * The writes that stop leftover precision everywhere, while keeping the
     * single active safety-net room on full precision when there is one.
     */
    fun writesToSilence(channels: List<RoomChannel>, keepSlot: Int? = null): List<PrecisionWrite> {
        val zeroes = channels.mapNotNull { channel ->
            val shouldKeepAtFull = keepSlot != null && channel.index == keepSlot && channel.positionPrecision == PositionPrecision.FULL
            if (channel.positionPrecision != PositionPrecision.DISABLED && !shouldKeepAtFull) {
                PrecisionWrite(channel.index, PositionPrecision.DISABLED)
            } else {
                null
            }
        }
        val setFull = channels.firstOrNull { it.index == keepSlot && it.positionPrecision != PositionPrecision.FULL }
            ?.let { PrecisionWrite(it.index, PositionPrecision.FULL) }
        return if (setFull == null) zeroes else zeroes + setFull
    }

    fun radioSafetyNetConfigReady(position: Config.PositionConfig?): Boolean =
        position?.gps_mode == Config.PositionConfig.GpsMode.ENABLED &&
            !position.fixed_position &&
            position.position_broadcast_secs == RADIO_SAFETY_NET_SECONDS &&
            !position.position_broadcast_smart_enabled &&
            (position.position_flags and Config.PositionConfig.PositionFlags.TIMESTAMP.value) != 0

    private const val RADIO_SAFETY_NET_SECONDS = 86_400
}

data class PrecisionWrite(val index: Int, val precision: Int)

data class SafetyNetShare(
    val roomId: Int,
    val endsAt: Long?,
    val radioSafetyNet: Boolean,
    val safetyNetNodeNum: Int?,
) {
    fun hasPassed(nowMillis: Long): Boolean = endsAt != null && nowMillis >= endsAt
}
