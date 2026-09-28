package com.getfirepit.core.protocol

import com.getfirepit.core.model.ChannelRole
import com.getfirepit.core.model.RoomChannel
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelSlotManagerTest {

    @Test
    fun `first room takes slot one, not the primary`() {
        val channels = listOf(primary())

        assertEquals(1, ChannelSlotManager.nextFreeSlot(channels))
    }

    @Test
    fun `rooms fill the lowest free slot`() {
        val channels = listOf(primary(), room(1, 100), room(2, 200))

        assertEquals(3, ChannelSlotManager.nextFreeSlot(channels))
    }

    @Test
    fun `a gap is reused before extending`() {
        val channels = listOf(primary(), room(1, 100), room(3, 300))

        assertEquals("slot 2 is free and must be used first", 2, ChannelSlotManager.nextFreeSlot(channels))
    }

    @Test
    fun `seven rooms is the ceiling`() {
        val channels = listOf(primary()) + (1..7).map { room(it, it * 100) }

        assertNull(ChannelSlotManager.nextFreeSlot(channels))
        assertTrue(ChannelSlotManager.isFull(channels))
    }

    @Test
    fun `leaving the last room only disables its slot`() {
        val channels = listOf(primary(), room(1, 100), room(2, 200))

        val writes = ChannelSlotManager.writesForLeaving(channels, roomId = 200)

        assertEquals(listOf(ChannelSlotManager.SlotWrite(2, null)), writes)
    }

    @Test
    fun `leaving a middle room shifts the later ones down`() {
        val channels = listOf(primary(), room(1, 100), room(2, 200), room(3, 300))

        val writes = ChannelSlotManager.writesForLeaving(channels, roomId = 200)

        assertEquals(2, writes.size)
        assertEquals(2, writes[0].index)
        assertEquals("room 300 moves into the freed slot", 300, writes[0].channel?.id)
        assertEquals("the tail slot is disabled", ChannelSlotManager.SlotWrite(3, null), writes[1])
    }

    @Test
    fun `leaving a room that is not present does nothing`() {
        val channels = listOf(primary(), room(1, 100))

        assertTrue(ChannelSlotManager.writesForLeaving(channels, roomId = 999).isEmpty())
    }

    @Test
    fun `the primary is never treated as a room`() {
        val channels = listOf(primary(), room(1, 100))

        assertEquals(listOf(100), ChannelSlotManager.rooms(channels).map { it.id })
        assertTrue(ChannelSlotManager.writesForLeaving(channels, roomId = PRIMARY_ID).isEmpty())
    }

    @Test
    fun `any sequence of leaves keeps slots consecutive and preserves rooms`() {
        val random = Random(seed = 20260909)

        repeat(200) { iteration ->
            val count = random.nextInt(1, ChannelSlotManager.MAX_ROOMS + 1)
            var channels = listOf(primary()) + (1..count).map { room(it, it * 1_000) }
            var expected = ChannelSlotManager.rooms(channels).map { it.id }

            while (expected.isNotEmpty()) {
                val leaving = expected.random(random)
                val layout = ChannelSlotManager.layoutAfterLeaving(channels, leaving)

                expected = expected.filterNot { it == leaving }

                assertEquals(
                    "iteration $iteration: slots must stay consecutive from 1",
                    (1..expected.size).toList(),
                    layout.map { it.index },
                )
                assertEquals(
                    "iteration $iteration: remaining rooms must survive, in order",
                    expected,
                    layout.map { it.id },
                )
                // Settings must travel with the room, never with the slot.
                layout.forEach { moved ->
                    assertEquals("room ${moved.id} kept its name", "room-${moved.id}", moved.name)
                    assertEquals("room ${moved.id} kept its precision", 32, moved.positionPrecision)
                }

                channels = listOf(primary()) + layout
            }
        }
    }

    @Test
    fun `history follows each room to the slot it moves into`() {
        val channels = listOf(room(1, 11), room(2, 22), room(3, 33))

        assertEquals(listOf(2 to 1, 3 to 2), ChannelSlotManager.slotMovesForLeaving(channels, 11))
    }

    @Test
    fun `leaving the last room moves nothing`() {
        val channels = listOf(room(1, 11), room(2, 22), room(3, 33))

        assertEquals(
            emptyList<Pair<Int, Int>>(),
            ChannelSlotManager.slotMovesForLeaving(channels, 33),
        )
    }

    @Test
    fun `a room that is not ours moves nothing`() {
        assertEquals(
            emptyList<Pair<Int, Int>>(),
            ChannelSlotManager.slotMovesForLeaving(listOf(room(1, 11)), 99),
        )
    }

    @Test
    fun `moves never write onto a slot still in use`() {
        val channels = listOf(room(1, 11), room(2, 22), room(3, 33), room(4, 44))
        val occupied = channels.map { it.index }.toMutableSet()
        occupied.remove(ChannelSlotManager.slotOf(channels, 22))

        ChannelSlotManager.slotMovesForLeaving(channels, 22).forEach { (from, to) ->
            assertTrue("slot $to was still taken", to !in occupied)
            occupied.remove(from)
            occupied.add(to)
        }
    }

    @Test
    fun `the slot a room sits in is reported for its history`() {
        val channels = listOf(room(1, 11), room(2, 22))

        assertEquals(2, ChannelSlotManager.slotOf(channels, 22))
        assertNull(ChannelSlotManager.slotOf(channels, 99))
    }

    /**
     * A write names the slot a room is moving *to*, so its key has to be read
     * from the slot it is leaving. Reading the destination instead gave every
     * room above the one left the key of the room below it.
     */
    @Test
    fun `every room that moves can be traced back to the slot it holds now`() {
        val channels = listOf(room(1, 11), room(2, 22), room(3, 33), room(4, 44))
        val from = ChannelSlotManager.slotMovesForLeaving(channels, 22).associate { (from, to) -> to to from }

        val moving = ChannelSlotManager.writesForLeaving(channels, 22).mapNotNull { write ->
            write.channel?.let { write.index to it }
        }

        assertEquals(2, moving.size)
        moving.forEach { (target, room) ->
            val source = from.getValue(target)
            assertEquals("slot $source holds a different room", room.id, channels.single { it.index == source }.id)
        }
    }

    private companion object {
        const val PRIMARY_ID = 0x4D455348

        fun primary() = RoomChannel(
            index = 0,
            name = "MeshChat",
            role = ChannelRole.PRIMARY,
            id = PRIMARY_ID,
            positionPrecision = 0,
        )

        fun room(index: Int, id: Int) = RoomChannel(
            index = index,
            name = "room-$id",
            role = ChannelRole.SECONDARY,
            id = id,
            positionPrecision = 32,
        )
    }
}
