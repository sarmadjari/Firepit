package com.getfirepit.core.data

import com.getfirepit.core.protocol.ShareDuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When sharing stops.
 *
 * The radio transmits whether or not this app is running, so the deadline is
 * the only thing standing between "for 1 hour" and "until someone notices".
 */
class SharingDeadlineTest {

    private val now = 1_757_000_000_000L

    private fun deadline(endsAt: Long?) =
        SharingDeadline(roomId = 42, choice = ShareDuration.HOUR, endsAt = endsAt)

    @Test
    fun `a deadline in the future has not passed`() {
        assertFalse(deadline(now + 1).hasPassed(now))
    }

    @Test
    fun `a deadline is reached on the instant, not after it`() {
        assertTrue(deadline(now).hasPassed(now))
    }

    @Test
    fun `a deadline from a previous run has passed`() {
        // The case that matters: the app was killed and started again later.
        assertTrue(deadline(now - 1).hasPassed(now + 86_400_000))
    }

    @Test
    fun `no deadline never passes`() {
        assertFalse(
            "until-off must not expire by accident",
            deadline(endsAt = null).hasPassed(Long.MAX_VALUE),
        )
    }
}
