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
