package com.getfirepit.core.protocol

import kotlin.time.Duration.Companion.hours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The promise behind "for 4 hours".
 *
 * If any of these start returning a longer window than they claim, a location
 * carries on being broadcast after the person who shared it believes it stopped
 * — which is worse than never having offered the choice.
 */
class ShareDurationTest {

    private val now = 1_757_000_000_000L

    @Test
    fun `every timed choice ends after exactly what it says`() {
        ShareDuration.entries
            .mapNotNull { entry -> entry.duration?.let { entry to it } }
            .forEach { (entry, duration) ->
                assertEquals(
                    "${entry.name} must end when its label says",
                    now + duration.inWholeMilliseconds,
                    entry.endsAt(now),
                )
            }
    }

    @Test
    fun `until off never ends on its own`() {
        assertNull(ShareDuration.UNTIL_OFF.endsAt(now))
        assertNull(ShareDuration.UNTIL_OFF.duration)
    }

    @Test
    fun `the default is bounded`() {
        // A default of "forever" would put the one risk this exists to stop
        // behind a choice nobody makes.
        assertNotNull("the default must stop on its own", ShareDuration.DEFAULT.duration)
        assertTrue(ShareDuration.DEFAULT.duration!! <= 24.hours)
    }

    @Test
    fun `an unknown name falls back to the bounded default`() {
        assertEquals(ShareDuration.DEFAULT, ShareDuration.named(null))
        assertEquals(ShareDuration.DEFAULT, ShareDuration.named("FOREVER"))
        assertEquals(ShareDuration.DEFAULT, ShareDuration.named(""))
    }

    @Test
    fun `a stored name round-trips`() {
        ShareDuration.entries.forEach { entry ->
            assertEquals(entry, ShareDuration.named(entry.name))
        }
    }
}
