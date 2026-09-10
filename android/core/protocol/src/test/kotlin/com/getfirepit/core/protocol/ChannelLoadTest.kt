package com.getfirepit.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelLoadTest {

    @Test
    fun `absent telemetry is not a reading`() {
        // Silence must not read as a clear channel, or we would reassure
        // someone whose radio has told us nothing at all.
        assertNull(ChannelLoad.of(null))
    }

    @Test
    fun `quiet channel is clear`() {
        assertEquals(ChannelLoad.CLEAR, ChannelLoad.of(0f))
        assertEquals(ChannelLoad.CLEAR, ChannelLoad.of(24.9f))
    }

    @Test
    fun `busy begins where the firmware throttles telemetry`() {
        assertEquals(ChannelLoad.BUSY, ChannelLoad.of(25f))
        assertEquals(ChannelLoad.BUSY, ChannelLoad.of(49.9f))
    }

    @Test
    fun `half the airtime is congested`() {
        assertEquals(ChannelLoad.CONGESTED, ChannelLoad.of(50f))
        assertEquals(ChannelLoad.CONGESTED, ChannelLoad.of(100f))
    }
}
