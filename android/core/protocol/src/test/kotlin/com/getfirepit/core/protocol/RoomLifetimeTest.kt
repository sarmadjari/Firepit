package com.getfirepit.core.protocol

import kotlin.time.Duration.Companion.days
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLifetimeTest {

    private val now = 1_800_000_000_000L

    private fun daysAgo(n: Int) = now - n.days.inWholeMilliseconds

    @Test
    fun `keeping rooms forever forgets nothing`() {
        val rooms = mapOf(1 to daysAgo(400), 2 to daysAgo(1000))

        assertTrue(RoomLifetime.silentRooms(rooms, RoomLifetime.FOREVER, now).isEmpty())
    }

    @Test
    fun `a room still being talked in is kept`() {
        val rooms = mapOf(1 to daysAgo(2))

        assertTrue(RoomLifetime.silentRooms(rooms, RoomLifetime.MONTH, now).isEmpty())
    }

    @Test
    fun `a room silent past the limit is forgotten`() {
        val rooms = mapOf(1 to daysAgo(31), 2 to daysAgo(2))

        assertEquals(listOf(1), RoomLifetime.silentRooms(rooms, RoomLifetime.MONTH, now))
    }

    @Test
    fun `the boundary itself is not past it`() {
        val rooms = mapOf(1 to daysAgo(30))

        assertTrue(RoomLifetime.silentRooms(rooms, RoomLifetime.MONTH, now).isEmpty())
    }

    /**
     * A radio with a wrong clock can stamp a message in the future. Deleting a
     * room on the strength of that would be irreversible.
     */
    @Test
    fun `a room whose last word is in the future is left alone`() {
        val rooms = mapOf(1 to now + 10.days.inWholeMilliseconds)

        assertTrue(RoomLifetime.silentRooms(rooms, RoomLifetime.MONTH, now).isEmpty())
    }

    @Test
    fun `a room with no recorded activity at all is left alone`() {
        val rooms = mapOf(1 to 0L)

        assertTrue(RoomLifetime.silentRooms(rooms, RoomLifetime.MONTH, now).isEmpty())
    }

    @Test
    fun `three months keeps what one month would not`() {
        val rooms = mapOf(1 to daysAgo(45))

        assertEquals(listOf(1), RoomLifetime.silentRooms(rooms, RoomLifetime.MONTH, now))
        assertTrue(RoomLifetime.silentRooms(rooms, RoomLifetime.QUARTER, now).isEmpty())
    }

    @Test
    fun `an unknown stored name falls back to keeping rooms`() {
        assertEquals(RoomLifetime.FOREVER, RoomLifetime.named("WEEKLY"))
        assertEquals(RoomLifetime.FOREVER, RoomLifetime.named(null))
        assertEquals(RoomLifetime.QUARTER, RoomLifetime.named("QUARTER"))
    }
}
