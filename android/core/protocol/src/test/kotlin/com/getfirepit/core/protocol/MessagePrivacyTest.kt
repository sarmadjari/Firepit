package com.getfirepit.core.protocol

import com.getfirepit.core.model.BROADCAST_NODE_NUM
import com.getfirepit.core.model.RoomKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        hasPeerPhoneKey: Boolean = false,
        roomKind: RoomKind? = null,
    ) = MessagePrivacy.carriageFor(
        to, channel, isRoomSlot, sealingRoomId, hasPeerKey, channelKey, hasPeerPhoneKey, roomKind,
    )

    @Test
    fun `a firepit room is sealed`() {
        assertEquals(Carriage.SealedRoom(roomId, roomSlot), carriage())
    }

    @Test
    fun `a direct message is sealed to their phone when we know its key`() {
        val result = carriage(to = peer, hasPeerPhoneKey = true)

        assertEquals(Carriage.SealedDirect(peer), result)
        assertTrue(result.isPrivate)
    }

    /**
     * Without their phone key the firmware's PKI is all there is, and a radio
     * hands its private key to any phone that connects. It is still sent — this
     * is how Firepit reaches people not running it — but never called private.
     */
    @Test
    fun `a direct message without their phone key rides the radios alone, and says so`() {
        val result = carriage(to = peer)

        assertEquals(Carriage.ToOneNode(peer), result)
        assertFalse(result.isPrivate)
    }

    @Test
    fun `a phone key without a radio key is no way to send`() {
        assertEquals(
            Carriage.Refused(Carriage.Reason.NO_PEER_KEY),
            carriage(to = peer, hasPeerKey = false, hasPeerPhoneKey = true),
        )
    }

    /**
     * A room still on the radio whose key this phone lost. The only way left
     * to send is the channel key, which every member's radio holds.
     */
    @Test
    fun `a firepit room whose key is missing is refused, never downgraded to the channel`() {
        assertEquals(
            Carriage.Refused(Carriage.Reason.ROOM_KEY_MISSING),
            carriage(sealingRoomId = null, roomKind = RoomKind.FIREPIT_KEY_MISSING),
        )
    }

    /** The old key is what a removed member still holds. */
    @Test
    fun `a room that moved on to a key we never got is refused`() {
        assertEquals(
            Carriage.Refused(Carriage.Reason.ROOM_MOVED_ON),
            carriage(sealingRoomId = null, roomKind = RoomKind.FIREPIT_MOVED_ON),
        )
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
        // A room key is no substitute for somebody's phone key.
        assertEquals(Carriage.ToOneNode(peer), carriage(to = peer, sealingRoomId = roomId, hasPeerPhoneKey = false))
    }

    /**
     * The property that matters: an open channel is only ever reached when the
     * conversation itself is an interoperable one. No combination of missing
     * keys turns a Firepit room or a direct message into a readable packet.
     */
    @Test
    fun `nothing downgrades into the open by accident`() {
        val flags = listOf(false, true)
        val cases = sequence {
            for (to in listOf(BROADCAST_NODE_NUM, peer)) {
                for (channel in 0..ChannelSlotManager.LAST_ROOM_SLOT) {
                    for (room in listOf(null, roomId)) {
                        for (isRoomSlot in flags) {
                            for (peerKey in flags) {
                                for (phoneKey in flags) {
                                    for (kind in listOf(null, RoomKind.FIREPIT_KEY_MISSING, RoomKind.FIREPIT_MOVED_ON)) {
                                        for (key in ChannelKey.entries) {
                                            yield(Case(to, channel, isRoomSlot, room, peerKey, phoneKey, kind, key))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        cases.forEach { case ->
            val result = with(case) {
                MessagePrivacy.carriageFor(to, channel, isRoomSlot, room, peerKey, key, phoneKey, kind)
            }
            val label = "$case -> $result"

            when (result) {
                // Only ever a broadcast on a room slot we hold no seal for, and
                // never one that is a Firepit room we lost the key to.
                is Carriage.OpenChannel -> assertTrue(
                    label,
                    case.to == BROADCAST_NODE_NUM && case.isRoomSlot && case.room == null && case.kind == null,
                )

                is Carriage.SealedRoom -> assertTrue(
                    label,
                    case.to == BROADCAST_NODE_NUM && case.isRoomSlot && case.room != null,
                )

                is Carriage.SealedDirect ->
                    assertTrue(label, case.to != BROADCAST_NODE_NUM && case.peerKey && case.phoneKey)

                is Carriage.ToOneNode ->
                    assertTrue(label, case.to != BROADCAST_NODE_NUM && case.peerKey && !case.phoneKey)

                is Carriage.Refused -> Unit
            }
        }
    }

    private data class Case(
        val to: Int,
        val channel: Int,
        val isRoomSlot: Boolean,
        val room: Int?,
        val peerKey: Boolean,
        val phoneKey: Boolean,
        val kind: RoomKind?,
        val key: ChannelKey,
    )

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
            MessagePrivacy.MAX_DIRECT_SEALED_TEXT_BYTES,
            MessagePrivacy.textBudgetFor(Carriage.SealedDirect(peer)),
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
