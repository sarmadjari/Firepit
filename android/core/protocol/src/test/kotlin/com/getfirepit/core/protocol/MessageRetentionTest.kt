package com.getfirepit.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRetentionTest {
    private val now = 1_700_000_000_000L

    @Test
    fun `a day of history drops anything older than a day`() {
        assertEquals(24L * 60 * 60 * 1000, now - MessageRetention.DAY.cutoff(now))
    }

    @Test
    fun `longer settings keep more`() {
        assertTrue(MessageRetention.WEEK.cutoff(now) < MessageRetention.DAY.cutoff(now))
        assertTrue(MessageRetention.MONTH.cutoff(now) < MessageRetention.WEEK.cutoff(now))
    }

    @Test
    fun `every setting deletes something, so nothing is kept forever`() {
        MessageRetention.entries.forEach {
            assertTrue("${it.name} keeps everything", it.cutoff(now) < now)
        }
    }

    @Test
    fun `a week is what you get without choosing`() {
        assertEquals(MessageRetention.WEEK, MessageRetention.DEFAULT)
        assertEquals(MessageRetention.WEEK, MessageRetention.named(null))
    }

    @Test
    fun `a setting that no longer exists falls back to the default, not to keeping`() {
        assertEquals(MessageRetention.WEEK, MessageRetention.named("FOREVER"))
    }

    @Test
    fun `a stored setting is read back as itself`() {
        MessageRetention.entries.forEach {
            assertEquals(it, MessageRetention.named(it.name))
        }
    }
}
