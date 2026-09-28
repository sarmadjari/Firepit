package com.getfirepit.core.protocol

import org.meshtastic.proto.MeshPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "you were standing in front of me" rule, stated as tests.
 *
 * These cover what a second radio on a bench cannot: the mesh has to be three
 * nodes deep before a relayed join hello exists at all, and a forged one needs
 * a sender that does not run our firmware. Both are packets, so both can be
 * written down.
 */
class PacketOriginTest {

    private fun packet(hopStart: Int, hopLimit: Int) =
        MeshPacket(from = 42, hop_start = hopStart, hop_limit = hopLimit)

    @Test
    fun `an untouched packet is direct`() {
        assertTrue(PacketOrigin.arrivedDirectly(packet(hopStart = 3, hopLimit = 3)))
    }

    @Test
    fun `a packet sent to a neighbour only is direct`() {
        assertTrue(
            "hop_start 0 is what the firmware writes when hop_limit is 0",
            PacketOrigin.arrivedDirectly(packet(hopStart = 0, hopLimit = 0)),
        )
    }

    @Test
    fun `one relay is enough to stop being direct`() {
        assertFalse(PacketOrigin.arrivedDirectly(packet(hopStart = 3, hopLimit = 2)))
        assertEquals(1, PacketOrigin.hopsTravelled(packet(hopStart = 3, hopLimit = 2)))
    }

    @Test
    fun `a packet from across the mesh is not direct`() {
        assertFalse(PacketOrigin.arrivedDirectly(packet(hopStart = 7, hopLimit = 0)))
        assertEquals(7, PacketOrigin.hopsTravelled(packet(hopStart = 7, hopLimit = 0)))
    }

    @Test
    fun `claiming zero hop_start while still being relayed is refused`() {
        // The cheap forgery: leave hop_start at 0 so the distance looks
        // unknowable, but set hop_limit high enough to be carried anyway.
        // Relays decrement, so it lands claiming a negative distance.
        val relayed = packet(hopStart = 0, hopLimit = 2)

        assertEquals(-2, PacketOrigin.hopsTravelled(relayed))
        assertFalse(
            "an impossible hop count must fail closed, not be treated as unknown",
            PacketOrigin.arrivedDirectly(relayed),
        )
    }

    @Test
    fun `every inconsistent pair is refused`() {
        (0..7).forEach { start ->
            (0..7).forEach { limit ->
                val direct = PacketOrigin.arrivedDirectly(packet(start, limit))
                assertEquals(
                    "hop_start=$start hop_limit=$limit",
                    start == limit,
                    direct,
                )
            }
        }
    }
}
