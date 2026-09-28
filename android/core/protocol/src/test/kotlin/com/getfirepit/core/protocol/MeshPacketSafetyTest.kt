package com.getfirepit.core.protocol

import com.getfirepit.core.model.BROADCAST_NODE_NUM
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.PortNum

/**
 * The rules that stop a packet being built at all.
 *
 * Each of these was broken at least once by a feature written before the
 * privacy rules existed — a direct message on the channel key, a buzz on the
 * primary, a map pin carrying coordinates on the public channel. They live in
 * the builder rather than in each caller so the next such feature cannot repeat
 * them: the packet simply refuses to exist.
 */
class MeshPacketSafetyTest {

    private val peer = -1181562854
    private val key = ByteArray(32) { 7 }.toByteString()
    private val payload = "hello".encodeUtf8()

    private fun build(
        to: Int = BROADCAST_NODE_NUM,
        channel: Int = 1,
        portNum: PortNum = PortNum.TEXT_MESSAGE_APP,
        pkiEncrypted: Boolean = false,
        publicKey: ByteString = ByteString.EMPTY,
    ) = MeshPacketBuilder.meshPacket(
        to = to,
        channel = channel,
        portNum = portNum,
        payload = payload,
        pkiEncrypted = pkiEncrypted,
        publicKey = publicKey,
    )

    private fun refusal(block: () -> Unit): String {
        val failure = runCatching(block).exceptionOrNull()
        assertNotNull("expected this packet to be refused", failure)
        assertTrue(failure.toString(), failure is IllegalArgumentException)
        return failure!!.message.orEmpty()
    }

    // --- a direct message is always encrypted to its recipient --------------

    @Test
    fun `a directed text message without encryption is refused`() {
        val message = refusal { build(to = peer, portNum = PortNum.TEXT_MESSAGE_APP) }

        assertTrue(message, message.contains("encrypted to its recipient"))
    }

    @Test
    fun `a directed text message encrypted to the peer is allowed`() {
        val packet = build(to = peer, pkiEncrypted = true, publicKey = key)

        assertTrue(packet.pki_encrypted)
        assertEquals(peer, packet.to)
    }

    /** A room message is a broadcast, and is sealed by us before it gets here. */
    @Test
    fun `a broadcast text message needs no pki`() {
        assertNotNull(build(to = BROADCAST_NODE_NUM, portNum = PortNum.TEXT_MESSAGE_APP))
    }

    /** Traceroute is a routing probe that cannot be encrypted to anyone. */
    @Test
    fun `a directed packet on another port is not forced to use pki`() {
        assertNotNull(build(to = peer, channel = 1, portNum = PortNum.TRACEROUTE_APP))
        assertNotNull(build(to = peer, channel = 0, portNum = PortNum.PRIVATE_APP))
    }

    /**
     * Being unencryptable is why it has to ride a room: the route names every
     * node that carried it, and the primary's key is held by every Firepit
     * radio, so asking there publishes who is checking on whom.
     */
    @Test
    fun `a traceroute on the primary is refused`() {
        val message = refusal { build(to = peer, channel = 0, portNum = PortNum.TRACEROUTE_APP) }

        assertTrue(message, message.contains("every node that carried it"))
    }

    // --- pki needs a real key ----------------------------------------------

    @Test
    fun `claiming pki without a key is refused`() {
        val message = refusal { build(to = peer, pkiEncrypted = true) }

        assertTrue(message, message.contains("32-byte key"))
    }

    @Test
    fun `a key of the wrong length is refused`() {
        listOf(16, 31, 33, 64).forEach { size ->
            refusal { build(to = peer, pkiEncrypted = true, publicKey = ByteArray(size).toByteString()) }
        }
    }

    // --- positions never touch the primary ---------------------------------

    @Test
    fun `a waypoint on the primary channel is refused`() {
        val message = refusal {
            build(channel = PrimaryChannel.SLOT, portNum = PortNum.WAYPOINT_APP)
        }

        assertTrue(message, message.contains("must not go on the primary"))
    }

    @Test
    fun `a position on the primary channel is refused`() {
        refusal { build(channel = PrimaryChannel.SLOT, portNum = PortNum.POSITION_APP) }
    }

    @Test
    fun `a waypoint on a room is allowed`() {
        (ChannelSlotManager.FIRST_ROOM_SLOT..ChannelSlotManager.LAST_ROOM_SLOT).forEach { slot ->
            assertNotNull(build(channel = slot, portNum = PortNum.WAYPOINT_APP))
        }
    }

    // --- the rules the builder already had ---------------------------------

    @Test
    fun `a packet that would never be rebroadcast is refused`() {
        refusal {
            MeshPacketBuilder.meshPacket(
                to = BROADCAST_NODE_NUM,
                channel = 1,
                portNum = PortNum.TEXT_MESSAGE_APP,
                payload = payload,
                hopLimit = 0,
            )
        }
    }

    @Test
    fun `an oversized payload is refused`() {
        refusal {
            MeshPacketBuilder.meshPacket(
                to = BROADCAST_NODE_NUM,
                channel = 1,
                portNum = PortNum.TEXT_MESSAGE_APP,
                payload = ByteArray(MeshConstants.DATA_PAYLOAD_LEN + 1).toByteString(),
            )
        }
    }

    @Test
    fun `a channel outside the radio's eight slots is refused`() {
        refusal { build(channel = 8) }
        refusal { build(channel = -1) }
    }
}
