package com.getfirepit.core.protocol

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.meshtastic.proto.PortNum

@OptIn(ExperimentalCoroutinesApi::class)
class OutboundPacerTest {

    @Test
    fun `first packet on a port goes immediately`() = runTest {
        val pacer = OutboundPacer { testScheduler.currentTime }

        pacer.awaitSlot(PortNum.TEXT_MESSAGE_APP)

        assertEquals(0, testScheduler.currentTime)
    }

    @Test
    fun `text messages are spaced two seconds apart`() = runTest {
        val pacer = OutboundPacer { testScheduler.currentTime }

        pacer.awaitSlot(PortNum.TEXT_MESSAGE_APP)
        pacer.awaitSlot(PortNum.TEXT_MESSAGE_APP)

        assertEquals(2_000, testScheduler.currentTime)
    }

    @Test
    fun `positions are spaced ten seconds apart`() = runTest {
        val pacer = OutboundPacer { testScheduler.currentTime }

        pacer.awaitSlot(PortNum.POSITION_APP)
        pacer.awaitSlot(PortNum.POSITION_APP)

        assertEquals(10_000, testScheduler.currentTime)
    }

    @Test
    fun `limits are per portnum, so a position never delays a text`() = runTest {
        val pacer = OutboundPacer { testScheduler.currentTime }

        pacer.awaitSlot(PortNum.POSITION_APP)
        pacer.awaitSlot(PortNum.TEXT_MESSAGE_APP)

        assertEquals(0, testScheduler.currentTime)
    }

    @Test
    fun `unlimited portnums never wait`() = runTest {
        val pacer = OutboundPacer { testScheduler.currentTime }

        repeat(5) { pacer.awaitSlot(PortNum.ADMIN_APP) }
        repeat(5) { pacer.awaitSlot(PortNum.NODEINFO_APP) }

        assertEquals(0, testScheduler.currentTime)
    }

    @Test
    fun `concurrent senders queue behind each other instead of firing together`() = runTest {
        val pacer = OutboundPacer { testScheduler.currentTime }
        val sentAt = mutableListOf<Long>()

        repeat(3) {
            launch {
                pacer.awaitSlot(PortNum.TEXT_MESSAGE_APP)
                sentAt += testScheduler.currentTime
            }
        }
        runCurrent()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(0L, 2_000L, 4_000L), sentAt)
    }

    @Test
    fun `traceroute is spaced thirty seconds apart`() = runTest {
        val pacer = OutboundPacer { testScheduler.currentTime }

        pacer.awaitSlot(PortNum.TRACEROUTE_APP)
        pacer.awaitSlot(PortNum.TRACEROUTE_APP)

        assertEquals(30_000, testScheduler.currentTime)
    }
}
