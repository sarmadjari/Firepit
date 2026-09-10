package com.getfirepit.core.protocol

import com.getfirepit.core.model.ChannelRole
import com.getfirepit.core.model.RoomChannel

/**
 * Allocates and re-packs the radio's 8 channel slots.
 *
 * Slot 0 is the primary and is never a room. Rooms live in 1..7 and the docs
 * call for them to stay consecutive, so leaving a room shifts the later ones
 * down rather than leaving a hole.
 *
 * Everything here is keyed by [RoomChannel.id] — the stable room identifier —
 * never by slot index, which moves.
 */
object ChannelSlotManager {

    const val PRIMARY_SLOT = 0
    const val FIRST_ROOM_SLOT = 1
    const val LAST_ROOM_SLOT = 7
    const val MAX_ROOMS = LAST_ROOM_SLOT - FIRST_ROOM_SLOT + 1

    /** A slot that must be written to the radio to reach the desired layout. */
    data class SlotWrite(val index: Int, val channel: RoomChannel?)

    fun rooms(channels: List<RoomChannel>): List<RoomChannel> =
        channels.filter { it.index in FIRST_ROOM_SLOT..LAST_ROOM_SLOT && it.role == ChannelRole.SECONDARY }
            .sortedBy { it.index }

    /** Lowest free room slot, or null when all seven are taken. */
    fun nextFreeSlot(channels: List<RoomChannel>): Int? {
        val used = rooms(channels).map { it.index }.toSet()
        return (FIRST_ROOM_SLOT..LAST_ROOM_SLOT).firstOrNull { it !in used }
    }

    fun isFull(channels: List<RoomChannel>): Boolean = nextFreeSlot(channels) == null

    fun findByRoomId(channels: List<RoomChannel>, roomId: Int): RoomChannel? =
        rooms(channels).firstOrNull { it.id == roomId }

    /**
     * Writes needed to remove [roomId] and close the gap it leaves.
     *
     * Returns the shifted rooms followed by a disable for the slot that falls
     * off the end. Empty when the room is not present, so a repeated leave is
     * harmless.
     */
    fun writesForLeaving(channels: List<RoomChannel>, roomId: Int): List<SlotWrite> {
        val current = rooms(channels)
        val leaving = current.firstOrNull { it.id == roomId } ?: return emptyList()

        val remaining = current.filterNot { it.id == leaving.id }
        val writes = remaining.mapIndexedNotNull { position, room ->
            val target = FIRST_ROOM_SLOT + position
            // Only rewrite the ones that actually move.
            if (room.index == target) null else SlotWrite(target, room.copy(index = target))
        }

        val lastUsed = FIRST_ROOM_SLOT + remaining.size
        return writes + SlotWrite(lastUsed, null)
    }

    /**
     * Where each remaining room ends up, as oldSlot to newSlot.
     *
     * Ascending, and the leaving room's slot is freed first, so applying these
     * in order never writes onto a slot still holding something.
     */
    fun slotMovesForLeaving(channels: List<RoomChannel>, roomId: Int): List<Pair<Int, Int>> {
        val current = rooms(channels)
        if (current.none { it.id == roomId }) return emptyList()
        return current.filterNot { it.id == roomId }
            .mapIndexedNotNull { position, room ->
                val target = FIRST_ROOM_SLOT + position
                if (room.index == target) null else room.index to target
            }
    }

    /** The slot a room occupies, or null when it is not one of ours. */
    fun slotOf(channels: List<RoomChannel>, roomId: Int): Int? =
        rooms(channels).firstOrNull { it.id == roomId }?.index

    /** The layout [writesForLeaving] produces, used to assert the result. */
    fun layoutAfterLeaving(channels: List<RoomChannel>, roomId: Int): List<RoomChannel> =
        rooms(channels)
            .filterNot { it.id == roomId }
            .mapIndexed { position, room -> room.copy(index = FIRST_ROOM_SLOT + position) }
}
