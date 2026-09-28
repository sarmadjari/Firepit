package com.getfirepit.core.protocol

import com.getfirepit.core.model.BROADCAST_NODE_NUM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privacy guarantee, stated as tests.
 *
 * If any refusal here starts returning a carriage, or any sealed case starts
 * returning an open one, a message that used to be private is going out
 * readable.
 */
class MessagePrivacyTest {

    private val peer = -1181562854
    private val roomId = 0x0BADF00D
    private val roomSlot = 3

    private fun carriage(
        to: Int = BROADCAST_NODE_NUM,
        channel: Int = roomSlot,
        isRoomSlot: Boolean = true,
        sealingRoomId: Int? = roomId,
        hasPeerKey: Boolean = true,
        channelKey: ChannelKey = ChannelKey.PRIVATE,
    ) = MessagePrivacy.carriageFor(to, channel, isRoomSlot, sealingRoomId, hasPeerKey, channelKey)

    @Test
    fun `a firepit room is sealed`() {
        assertEquals(Carriage.SealedRoom(roomId, roomSlot), carriage())
    }

    @Test
    fun `a direct message goes to that node's key`() {
        assertEquals(Carriage.ToOneNode(peer), carriage(to = peer))
    }

    /**
     * The gap this object exists to close. Before, an unknown peer key meant
     * the message rode the channel instead — and the channel for a direct
     * message is the primary, whose key is published in the firmware source.
     */
    @Test
    fun `a direct message without the peer's key is refused, never downgraded`() {
        assertEquals(
            Carriage.Refused(Carriage.Reason.NO_PEER_KEY),
            carriage(to = peer, hasPeerKey = false),
        )
    }

    /**
     * Slot 0 sets the radio's frequency and carries NodeInfo. Keeping
     * conversations off it means every conversation is one somebody
     * deliberately created, and so can be labelled for what it is.
     */
    @Test
    fun `the primary channel never carries words`() {
        assertEquals(
            Carriage.Refused(Carriage.Reason.NOT_A_ROOM),
            carriage(channel = PrimaryChannel.SLOT, isRoomSlot = false, sealingRoomId = null),
        )
    }

    @Test
    fun `a firepit key is preferred over the channel's own encryption`() {
        // Even on a channel with a perfectly good PSK the sealed path wins: the
        // radio does not hold the Firepit key, and it does hold the PSK.
        listOf(ChannelKey.PRIVATE, ChannelKey.DEFAULT, ChannelKey.NONE).forEach { key ->
            assertEquals(key.name, Carriage.SealedRoom(roomId, roomSlot), carriage(channelKey = key))
        }
    }

    @Test
    fun `a shared meshtastic channel sends in the open so other clients can read it`() {
        assertEquals(
            Carriage.OpenChannel(roomSlot, ChannelKey.PRIVATE),
            carriage(sealingRoomId = null, channelKey = ChannelKey.PRIVATE),
        )
    }

    @Test
    fun `a public meshtastic channel sends in the open too`() {
        assertEquals(
            Carriage.OpenChannel(roomSlot, ChannelKey.DEFAULT),
            carriage(sealingRoomId = null, channelKey = ChannelKey.DEFAULT),
        )
    }

    /** An interoperable channel is honest about not being private. */
    @Test
    fun `an open channel never claims to be private`() {
        listOf(ChannelKey.PRIVATE, ChannelKey.DEFAULT).forEach { key ->
            val result = carriage(sealingRoomId = null, channelKey = key)

            assertTrue(key.name, result is Carriage.OpenChannel)
            assertTrue(key.name, !result.isPrivate)
        }
    }

    @Test
    fun `a channel with no encryption at all is refused`() {
        assertEquals(
            Carriage.Refused(Carriage.Reason.NOT_ENCRYPTED),
            carriage(sealingRoomId = null, channelKey = ChannelKey.NONE),
        )
    }

    @Test
    fun `keys do not substitute for one another`() {
        assertEquals(
            Carriage.Refused(Carriage.Reason.NO_PEER_KEY),
            carriage(to = peer, hasPeerKey = false, sealingRoomId = roomId),
        )
        assertEquals(Carriage.ToOneNode(peer), carriage(to = peer, sealingRoomId = null))
        assertEquals(Carriage.SealedRoom(roomId, roomSlot), carriage(hasPeerKey = false))
    }

    /**
     * The property that matters: an open channel is only ever reached when the
     * conversation itself is an interoperable one. No combination of missing
     * keys turns a Firepit room or a direct message into a readable packet.
     */
    @Test
    fun `nothing downgrades into the open by accident`() {
        val targets = listOf(BROADCAST_NODE_NUM, peer)
        val channels = 0..ChannelSlotManager.LAST_ROOM_SLOT
        val sealing = listOf(null, roomId)
        val flags = listOf(false, true)

        targets.forEach { to ->
            channels.forEach { channel ->
                sealing.forEach { room ->
                    flags.forEach { isRoomSlot ->
                        flags.forEach { peerKey ->
                            ChannelKey.entries.forEach { key ->
                                val result = MessagePrivacy
                                    .carriageFor(to, channel, isRoomSlot, room, peerKey, key)
                                val label = "$to/$channel/$isRoomSlot/$room/$peerKey/$key -> $result"

                                when (result) {
                                    // Only ever a broadcast on a room slot we hold no seal for.
                                    is Carriage.OpenChannel -> assertTrue(
                                        label,
                                        to == BROADCAST_NODE_NUM && isRoomSlot && room == null,
                                    )

                                    is Carriage.SealedRoom -> assertTrue(
                                        label,
                                        to == BROADCAST_NODE_NUM && isRoomSlot && room != null,
                                    )

                                    is Carriage.ToOneNode ->
                                        assertTrue(label, to != BROADCAST_NODE_NUM && peerKey)

                                    is Carriage.Refused -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** A direct message is never affected by how weak the channel's key is. */
    @Test
    fun `a direct message ignores the channel entirely`() {
        ChannelKey.entries.forEach { key ->
            (0..ChannelSlotManager.LAST_ROOM_SLOT).forEach { channel ->
                assertEquals(
                    "$key on $channel",
                    Carriage.ToOneNode(peer),
                    carriage(to = peer, channel = channel, isRoomSlot = channel > 0, channelKey = key),
                )
            }
        }
    }

    @Test
    fun `sealing costs the composer bytes, and a refusal leaves no budget at all`() {
        assertEquals(
            MeshConstants.MAX_TEXT_BYTES - MessagePrivacy.SEALED_OVERHEAD,
            MessagePrivacy.textBudgetFor(Carriage.SealedRoom(roomId, roomSlot)),
        )
        assertEquals(
            MeshConstants.MAX_TEXT_BYTES,
            MessagePrivacy.textBudgetFor(Carriage.ToOneNode(peer)),
        )
        assertEquals(
            MeshConstants.MAX_TEXT_BYTES,
            MessagePrivacy.textBudgetFor(Carriage.OpenChannel(roomSlot, ChannelKey.PRIVATE)),
        )
        assertEquals(0, MessagePrivacy.textBudgetFor(Carriage.Refused(Carriage.Reason.NO_PEER_KEY)))
    }

    /** A sealed room message plus its envelope still has to fit one LoRa packet. */
    @Test
    fun `the sealed budget leaves room for the protobuf envelope`() {
        val budget = MessagePrivacy.textBudgetFor(Carriage.SealedRoom(roomId, roomSlot))

        assertTrue(
            "budget $budget plus overhead exceeds the payload limit",
            budget + MessagePrivacy.SEALED_OVERHEAD <= MeshConstants.DATA_PAYLOAD_LEN,
        )
    }
}
