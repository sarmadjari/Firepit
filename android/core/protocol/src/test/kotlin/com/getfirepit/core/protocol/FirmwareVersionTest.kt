package com.getfirepit.core.protocol

import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.PortNum

class FirmwareVersionTest {

    @Test
    fun `parses a release version with a build suffix`() {
        val version = FirmwareVersion.parseOrNull("2.7.26.54e0d8d")

        assertEquals(FirmwareVersion(2, 7, 26, "2.7.26.54e0d8d"), version)
    }

    @Test
    fun `parses a development version`() {
        val version = FirmwareVersion.parseOrNull("2.8.1-dev")

        assertEquals(2, version?.major)
        assertEquals(8, version?.minor)
        assertEquals(1, version?.patch)
    }

    @Test
    fun `parses a two-part version`() {
        assertEquals(0, FirmwareVersion.parseOrNull("2.8")?.patch)
    }

    @Test
    fun `rejects junk rather than guessing`() {
        assertNull(FirmwareVersion.parseOrNull(""))
        assertNull(FirmwareVersion.parseOrNull("unknown"))
    }

    @Test
    fun `orders by major then minor then patch`() {
        val v2726 = FirmwareVersion.parseOrNull("2.7.26.54e0d8d")!!
        val v280 = FirmwareVersion.parseOrNull("2.8.0.47db0e3")!!

        assertTrue(v2726 < v280)
        assertTrue(v2726 >= RadioCapabilities.MINIMUM_FIRMWARE)
        assertTrue(FirmwareVersion.parseOrNull("2.6.9")!! < RadioCapabilities.MINIMUM_FIRMWARE)
    }
}

class MeshPacketBuilderTest {

    @Test
    fun `rejects a hop limit of zero, which is transmitted but never rebroadcast`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            MeshPacketBuilder.meshPacket(
                to = MeshConstants.BROADCAST_NODENUM,
                channel = 1,
                portNum = PortNum.TEXT_MESSAGE_APP,
                payload = "hi".encodeUtf8(),
                hopLimit = 0,
            )
        }

        assertTrue(error.message!!.contains("never rebroadcast"))
    }

    @Test
    fun `defaults to the firmware hop limit`() {
        val packet = MeshPacketBuilder.meshPacket(
            to = MeshConstants.BROADCAST_NODENUM,
            channel = 1,
            portNum = PortNum.TEXT_MESSAGE_APP,
            payload = "hi".encodeUtf8(),
        )

        assertEquals(MeshConstants.DEFAULT_HOP_LIMIT, packet.hop_limit)
        assertTrue("id must be non-zero to correlate ACKs", packet.id != 0)
    }

    @Test
    fun `rejects a payload over the firmware budget`() {
        assertThrows(IllegalArgumentException::class.java) {
            MeshPacketBuilder.meshPacket(
                to = MeshConstants.BROADCAST_NODENUM,
                channel = 1,
                portNum = PortNum.TEXT_MESSAGE_APP,
                payload = "x".repeat(MeshConstants.DATA_PAYLOAD_LEN + 1).encodeUtf8(),
            )
        }
    }

    @Test
    fun `pki packets carry a zero channel hash`() {
        val packet = MeshPacketBuilder.meshPacket(
            to = 0x1234,
            channel = 5,
            portNum = PortNum.TEXT_MESSAGE_APP,
            payload = "hi".encodeUtf8(),
            pkiEncrypted = true,
        )

        assertEquals(0, packet.channel)
    }

    @Test
    fun `local packets use hop limit zero so they never reach the air`() {
        val packet = MeshPacketBuilder.localPacket(
            myNodeNum = 0x1234,
            portNum = PortNum.POSITION_APP,
            payload = okio.ByteString.EMPTY,
        )

        assertEquals(0, packet.hop_limit)
        assertEquals(0x1234, packet.to)
    }

    @Test
    fun `formats node ids the way every meshtastic client shows them`() {
        assertEquals("!0000abcd", MeshConstants.formatNodeId(0xABCD))
        assertEquals("!ffffffff", MeshConstants.formatNodeId(MeshConstants.BROADCAST_NODENUM))
    }
}
