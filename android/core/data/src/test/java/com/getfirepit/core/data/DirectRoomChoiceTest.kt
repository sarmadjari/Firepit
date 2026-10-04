package com.getfirepit.core.data

import com.getfirepit.core.model.ChannelRole
import com.getfirepit.core.model.RoomChannel
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectRoomChoiceTest {

    @Test
    fun `pending handover rooms are skipped deterministically`() {
        val rooms = listOf(
            room(id = 40, slot = 2),
            room(id = 10, slot = 1),
            room(id = 20, slot = 3),
            room(id = 0, slot = 4),
        )

        val choices = directRoomChoices(rooms, pendingRoomIds = setOf(10)).map { it.id }

        assertEquals(listOf(20, 40), choices)
    }

    private fun room(id: Int, slot: Int) =
        RoomChannel(index = slot, name = "Room $id", role = ChannelRole.SECONDARY, id = id, positionPrecision = 32)
}
