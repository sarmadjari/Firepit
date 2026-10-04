package com.getfirepit.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnPositionTest {
    private val now = 1_000_000L
    private val phone = OwnPosition.Fix(1.0, 2.0, null, now - 30_000, OwnPosition.Source.PHONE)
    private val radio = OwnPosition.Fix(3.0, 4.0, null, now - 60_000, OwnPosition.Source.RADIO)

    @Test
    fun `phone fresh wins`() {
        assertEquals(phone, OwnPosition.choose(phone, radio, now, locationAllowed = true))
    }

    @Test
    fun `stale or missing phone falls back to fresh radio`() {
        val stalePhone = phone.copy(timeMillis = now - OwnPosition.PHONE_FRESH_MILLIS - 1)
        assertEquals(radio, OwnPosition.choose(stalePhone, radio, now, locationAllowed = true))
        assertEquals(radio, OwnPosition.choose(null, radio, now, locationAllowed = true))
    }

    @Test
    fun `both stale means none`() {
        val stalePhone = phone.copy(timeMillis = now - OwnPosition.PHONE_FRESH_MILLIS - 1)
        val staleRadio = radio.copy(timeMillis = now - OwnPosition.RADIO_FRESH_MILLIS - 1)
        assertNull(OwnPosition.choose(stalePhone, staleRadio, now, locationAllowed = true))
    }

    @Test
    fun `permission denied means none even with radio`() {
        assertNull(OwnPosition.choose(null, radio, now, locationAllowed = false))
    }

    @Test
    fun `a phone standing still keeps its fix rather than jumping to a radio fix a moment newer`() {
        val stillPhone = phone.copy(timeMillis = now - 5 * 60_000)
        val recentRadio = radio.copy(timeMillis = now - 4 * 60_000)
        assertEquals(stillPhone, OwnPosition.choose(stillPhone, recentRadio, now, locationAllowed = true))
        assertEquals(stillPhone, OwnPosition.choose(stillPhone, null, now, locationAllowed = true))
    }

    @Test
    fun `the radio takes over when the phone has gone quiet while the radio kept fixing`() {
        val quietPhone = phone.copy(timeMillis = now - 6 * 60_000)
        val freshRadio = radio.copy(timeMillis = now - 30_000)
        assertEquals(freshRadio, OwnPosition.choose(quietPhone, freshRadio, now, locationAllowed = true))
    }

    @Test
    fun `a fix stamped a little ahead of our clock still counts, one far ahead does not`() {
        val ahead = phone.copy(timeMillis = now + 60_000)
        assertEquals(ahead, OwnPosition.choose(ahead, null, now, locationAllowed = true))
        val farAhead = phone.copy(timeMillis = now + OwnPosition.CLOCK_SKEW_MILLIS + 1)
        assertNull(OwnPosition.choose(farAhead, null, now, locationAllowed = true))
    }
}
