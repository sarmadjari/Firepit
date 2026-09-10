package com.getfirepit.app.chat

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTimestampTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 9, 10)

    @Test
    fun `today shows the time alone`() {
        assertEquals("14:05", format(LocalDateTime.of(2026, 9, 10, 14, 5)))
    }

    @Test
    fun `yesterday is named rather than dated`() {
        assertEquals("Yesterday 23:59", format(LocalDateTime.of(2026, 9, 9, 23, 59)))
    }

    @Test
    fun `earlier this year shows day and month without the year`() {
        assertEquals("3 Sep 09:07", format(LocalDateTime.of(2026, 9, 3, 9, 7)))
    }

    @Test
    fun `a previous year includes the year`() {
        assertEquals("31 Dec 2025 18:30", format(LocalDateTime.of(2025, 12, 31, 18, 30)))
    }

    @Test
    fun `a message minutes old but past midnight is not called today`() {
        // Twenty minutes before the day rolled over, read just after: still not
        // today, and saying otherwise would misdate it by a day.
        val result = format(LocalDateTime.of(2026, 9, 9, 23, 40))

        assertTrue("expected a dated label, got $result", result.startsWith("Yesterday"))
    }

    @Test
    fun `midnight itself belongs to its own day`() {
        assertEquals("00:00", format(LocalDateTime.of(2026, 9, 10, 0, 0)))
    }

    private fun format(at: LocalDateTime): String = MessageTimestamp.format(
        epochMillis = at.atZone(zone).toInstant().toEpochMilli(),
        today = today,
        zone = zone,
    )
}
