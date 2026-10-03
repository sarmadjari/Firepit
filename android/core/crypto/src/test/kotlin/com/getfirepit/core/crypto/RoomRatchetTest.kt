package com.getfirepit.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomRatchetTest {
    private val key = ByteArray(32) { it.toByte() }
    private val room = 0x0BADF00D
    private val generation = 3
    private val hour = 491_234

    /** Worked out with nothing but HMAC-SHA256, from the construction as written down. */
    @Test
    fun `each step is the HKDF expand step the protocol describes`() {
        assertEquals(
            "90df36133102758829f442dbd61819a32e04e31ef09acb473aaf404fe309d34c",
            RoomRatchet.next(key, room, generation, hour).hex(),
        )
        assertEquals(
            "e27d1cb016f0efae819ccc3ba6895d1d8b05cf67f678dc73def65434c84f5a3c",
            RoomRatchet.forward(key, room, generation, hour, hour + 3)?.hex(),
        )
        assertEquals(
            "23df9036fc639fe2dada022b023458cc1c9554b134ceeb24ef66a950d4822a8b",
            RoomRatchet.senderKey(key, room, generation, hour, 42).hex(),
        )
    }

    @Test
    fun `moving on several hours at once lands where moving one at a time does`() {
        var stepped = key
        repeat(5) { stepped = RoomRatchet.next(stepped, room, generation, hour + it) }

        assertArrayEquals(stepped, RoomRatchet.forward(key, room, generation, hour, hour + 5))
        assertArrayEquals(key, RoomRatchet.forward(key, room, generation, hour, hour))
    }

    @Test
    fun `there is no way back to an earlier hour`() {
        assertNull(RoomRatchet.forward(key, room, generation, hour, hour - 1))
    }

    @Test
    fun `a clock decades out is refused rather than spun through`() {
        assertNull(RoomRatchet.forward(key, room, generation, hour, Int.MAX_VALUE))
    }

    @Test
    fun `moving on leaves the key it started from untouched`() {
        val original = key.copyOf()
        RoomRatchet.forward(key, room, generation, hour, hour + 2)

        assertArrayEquals(original, key)
    }

    @Test
    fun `every room, generation and hour has its own key`() {
        val base = RoomRatchet.next(key, room, generation, hour).toList()

        assertNotEquals(base, RoomRatchet.next(key, room + 1, generation, hour).toList())
        assertNotEquals(base, RoomRatchet.next(key, room, generation + 1, hour).toList())
        assertNotEquals(base, RoomRatchet.next(key, room, generation, hour + 1).toList())
    }

    @Test
    fun `no two senders seal under the same key`() {
        assertNotEquals(
            RoomRatchet.senderKey(key, room, generation, hour, 42).toList(),
            RoomRatchet.senderKey(key, room, generation, hour, 43).toList(),
        )
        assertArrayEquals(
            RoomRatchet.senderKey(key, room, generation, hour, 42),
            RoomRatchet.senderKey(key, room, generation, hour, 42),
        )
    }

    @Test
    fun `hours are counted in UTC from 1970`() {
        assertEquals(0, RoomRatchet.hourOf(0))
        assertEquals(0, RoomRatchet.hourOf(3_599_999))
        assertEquals(1, RoomRatchet.hourOf(3_600_000))
        assertEquals(-1, RoomRatchet.hourOf(-1))
        assertEquals(RoomRatchet.LEGACY_HOUR, RoomRatchet.hourOf(1_767_225_600_000))
    }

    @Test
    fun `the two bytes on the air are enough to find the hour`() {
        assertEquals(hour, RoomRatchet.hourNear(RoomRatchet.tagOf(hour), hour))
        assertEquals(hour - 1, RoomRatchet.hourNear(RoomRatchet.tagOf(hour - 1), hour))
        assertEquals(hour + 1, RoomRatchet.hourNear(RoomRatchet.tagOf(hour + 1), hour))
    }

    @Test
    fun `the hour is found across the point where the two bytes wrap`() {
        val justAfter = 8 * 65_536 + 1

        assertEquals(justAfter - 3, RoomRatchet.hourNear(RoomRatchet.tagOf(justAfter - 3), justAfter))
        assertEquals(justAfter - 2 + 3, RoomRatchet.hourNear(RoomRatchet.tagOf(justAfter + 1), justAfter - 2))
    }

    @Test
    fun `a member opens the hour gone, this one and the next`() {
        val held = hour - 1

        assertFalse(RoomRatchet.opens(held, hour, hour - 2))
        assertTrue(RoomRatchet.opens(held, hour, hour - 1))
        assertTrue(RoomRatchet.opens(held, hour, hour))
        assertTrue(RoomRatchet.opens(held, hour, hour + 1))
        assertFalse(RoomRatchet.opens(held, hour, hour + 2))
    }

    @Test
    fun `somebody who joined this hour reads nothing from the hour before`() {
        assertFalse(RoomRatchet.opens(hour, hour, hour - 1))
        assertTrue(RoomRatchet.opens(hour, hour, hour))
    }

    @Test
    fun `a clock set back neither reopens old hours nor seals under one`() {
        val held = hour - 1
        val setBack = hour - 5

        assertEquals(held, RoomRatchet.currentHour(held, setBack))
        assertFalse(RoomRatchet.opens(held, setBack, hour - 2))
        assertTrue(RoomRatchet.opens(held, setBack, held))
        assertEquals(held, RoomRatchet.keepFrom(held, setBack))
    }

    @Test
    fun `only the hour just gone is worth keeping`() {
        assertEquals(hour - 1, RoomRatchet.keepFrom(hour - 30, hour))
        assertEquals(hour, RoomRatchet.keepFrom(hour, hour))
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
