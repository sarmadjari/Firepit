package com.getfirepit.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioClockTest {

    private val now = 1_757_700_000_000L

    @Test
    fun `a radio that was never told the time has nothing to say`() {
        assertNull(RadioClock.onPhoneClock(radioSeconds = 0, skewMillis = 0, now = now))
        assertNull(RadioClock.ifPlausible(claimSeconds = 0, now = now))
    }

    @Test
    fun `a radio running behind is read forward onto the phone's clock`() {
        // Two days behind: the stamp reads two days ago, the phone says now.
        val behind = -2 * 24 * 60 * 60 * 1_000L
        val stamped = ((now + behind) / 1_000).toInt()

        assertEquals(now, RadioClock.onPhoneClock(stamped, skewMillis = behind, now = now))
    }

    @Test
    fun `a radio running ahead is read back onto the phone's clock`() {
        val ahead = 90 * 60 * 1_000L
        val stamped = ((now + ahead) / 1_000).toInt()

        assertEquals(now, RadioClock.onPhoneClock(stamped, skewMillis = ahead, now = now))
    }

    /** Skew is measured from the first packet, so early stamps arrive without one. */
    @Test
    fun `an unmeasured skew leaves the stamp as it came`() {
        val heard = now - 60_000
        val stamped = (heard / 1_000).toInt()

        assertEquals(heard, RadioClock.onPhoneClock(stamped, skewMillis = null, now = now))
    }

    /** Correcting cannot invent a moment we had not yet asked about. */
    @Test
    fun `a corrected stamp never lands in the future`() {
        val stamped = ((now + 60_000) / 1_000).toInt()

        assertEquals(now, RadioClock.onPhoneClock(stamped, skewMillis = 0, now = now))
    }

    @Test
    fun `a sender's stamp is kept while it could be true`() {
        val taken = now - 10 * 60 * 1_000

        assertEquals(taken, RadioClock.ifPlausible((taken / 1_000).toInt(), now))
    }

    @Test
    fun `a sender claiming the future is refused`() {
        val ahead = now + RadioClock.CLAIM_AHEAD_MS + 60_000

        assertNull(RadioClock.ifPlausible((ahead / 1_000).toInt(), now))
    }

    @Test
    fun `a sender stuck near the epoch is refused`() {
        assertNull(RadioClock.ifPlausible(claimSeconds = 1, now = now))
    }

    @Test
    fun `a sender older than anything kept is refused`() {
        val ancient = now - RadioClock.CLAIM_STALE_MS - 60_000

        assertNull(RadioClock.ifPlausible((ancient / 1_000).toInt(), now))
    }

    /** Seconds on the wire, milliseconds everywhere above it. */
    @Test
    fun `wire seconds become millis`() {
        val seconds = (now / 1_000).toInt()

        assertEquals(seconds.toLong() * 1_000, RadioClock.ifPlausible(seconds, now))
    }
}
