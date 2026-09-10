package com.getfirepit.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeaconRateTest {
    @Test
    fun `reads a rate the radio is already set to`() {
        assertEquals(BeaconRate.STEADY, BeaconRate.of(900))
        assertEquals(BeaconRate.HOURLY, BeaconRate.of(3_600))
    }

    @Test
    fun `zero means the firmware default, not never`() {
        assertEquals(BeaconRate.of(BeaconRate.FIRMWARE_DEFAULT_SECONDS), BeaconRate.of(0))
    }

    @Test
    fun `an interval Firepit does not offer is left alone rather than rounded`() {
        assertNull(BeaconRate.of(437))
    }

    @Test
    fun `a radio that has said nothing yet has no rate to show`() {
        assertNull(BeaconRate.of(null))
    }

    @Test
    fun `every rate is a distinct interval`() {
        val seconds = BeaconRate.entries.map { it.seconds }

        assertEquals(seconds.size, seconds.toSet().size)
    }
}
