package com.getfirepit.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRetentionTest {
    private val now = 1_700_000_000_000L

    @Test
    fun `a day of history keeps yesterday and drops the day before`() {
        val cutoff = MessageRetention.DAY.cutoff(now)!!

        assertTrue(cutoff < now)
        assertEquals(24L * 60 * 60 * 1000, now - cutoff)
    }

    @Test
    fun `longer settings keep more`() {
        assertTrue(MessageRetention.WEEK.cutoff(now)!! < MessageRetention.DAY.cutoff(now)!!)
        assertTrue(MessageRetention.MONTH.cutoff(now)!! < MessageRetention.WEEK.cutoff(now)!!)
    }

    @Test
    fun `keeping everything has no cutoff at all`() {
        // Not zero: a cutoff of zero would read as "delete everything before
        // 1970", which is harmless, but one arithmetic slip from deleting all.
        assertNull(MessageRetention.FOREVER.cutoff(now))
    }

    @Test
    fun `an unknown or missing setting keeps everything rather than deleting`() {
        assertEquals(MessageRetention.FOREVER, MessageRetention.named(null))
        assertEquals(MessageRetention.FOREVER, MessageRetention.named("HOURLY"))
    }

    @Test
    fun `a stored setting is read back as itself`() {
        MessageRetention.entries.forEach {
            assertEquals(it, MessageRetention.named(it.name))
        }
    }
}
