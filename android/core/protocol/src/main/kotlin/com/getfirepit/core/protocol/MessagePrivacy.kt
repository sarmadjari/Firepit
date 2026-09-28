package com.getfirepit.core.protocol

import com.getfirepit.core.model.BROADCAST_NODE_NUM

/**
 * The one rule about what may go on the air, kept as data rather than as
 * branches inside the sender.
 *
 * Meshtastic's channel encryption cannot make a conversation private. The
 * primary channel ships with a key that is published in the firmware source, a
 * room's key sits on hardware anyone can pick up, and the protocol's own
 * documentation notes that before 2.5 a direct message was "a channel message
 * with a `to` field set — anyone in the channel could read all your direct
 * messages". An app that quietly falls back to the channel key when something
 * better is unavailable rebuilds exactly that.
 *
 * So the rule is not "always encrypt", which would make talking to people
 * outside Firepit impossible. It is **never downgrade silently**: a message
 * travels the most private way its conversation allows, and a conversation that
 * cannot be private is one somebody deliberately created and which says so on
 * its face.
 */
sealed interface Carriage {

    /** True when nobody outside the intended audience can read the words. */
    val isPrivate: Boolean

    /**
     * Sealed by us under the room's key and carried on `PRIVATE_APP`. Relays,
     * non-members and the radio's own screen see an opaque blob.
     */
    data class SealedRoom(val roomId: Int, val channel: Int) : Carriage {
        override val isPrivate = true
    }

    /**
     * Handed to the firmware with the recipient's public key, which encrypts to
     * them and signs as us. Nobody else on the mesh can read it.
     */
    data class ToOneNode(val nodeNum: Int) : Carriage {
        override val isPrivate = true
    }

    /**
     * Ordinary `TEXT_MESSAGE_APP` on an ordinary channel, so a stock Meshtastic
     * client can read it — which is the entire point, and also the cost. The
     * radio holds the key, so anyone holding the radio has it; on a [key] of
     * `DEFAULT` the whole mesh has it.
     */
    data class OpenChannel(val channel: Int, val key: ChannelKey) : Carriage {
        /** Private from the mesh at large at best, never from whoever holds the key. */
        override val isPrivate = false
    }

    /** No way to carry this at all, so it is not sent. */
    data class Refused(val reason: Reason) : Carriage {
        override val isPrivate = true
    }

    enum class Reason {
        /** Their node has never published a public key we could encrypt to. */
        NO_PEER_KEY,

        /**
         * Not a conversation. Slot 0 sets the radio's frequency and carries
         * NodeInfo; Firepit never puts words on it, so that every conversation
         * is one somebody deliberately made.
         */
        NOT_A_ROOM,

        /** A channel with no key at all. Firepit has no reason to send in the clear. */
        NOT_ENCRYPTED,
    }
}

object MessagePrivacy {

    /**
     * How a message to [to] on [channel] may travel, or why it may not.
     *
     * [sealingRoomId] is the room this channel carries **and** whose Firepit key
     * we hold — null when either is missing, since a room we cannot seal for is
     * not one we can be private in. [isRoomSlot] separates a conversation from
     * slot 0. [channelKey] is how private the radio's own encryption on this
     * channel is, which is all an interoperable channel has.
     */
    fun carriageFor(
        to: Int,
        channel: Int,
        isRoomSlot: Boolean,
        sealingRoomId: Int?,
        hasPeerKey: Boolean,
        channelKey: ChannelKey,
    ): Carriage = when {
        // One person: their key or nothing. A direct message that falls back to
        // a channel is a direct message the channel can read.
        to != BROADCAST_NODE_NUM ->
            if (hasPeerKey) Carriage.ToOneNode(to) else Carriage.Refused(Carriage.Reason.NO_PEER_KEY)

        !isRoomSlot -> Carriage.Refused(Carriage.Reason.NOT_A_ROOM)

        // A Firepit room, sealed under a key the radio never holds. Always
        // preferred where it exists.
        sealingRoomId != null -> Carriage.SealedRoom(sealingRoomId, channel)

        // An interoperable channel: readable by other Meshtastic clients, which
        // is the only reason somebody would make one.
        channelKey.isPrivate || channelKey == ChannelKey.DEFAULT ->
            Carriage.OpenChannel(channel, channelKey)

        else -> Carriage.Refused(Carriage.Reason.NOT_ENCRYPTED)
    }

    /** What the composer may type, given how the message will travel. */
    fun textBudgetFor(carriage: Carriage): Int = when (carriage) {
        is Carriage.SealedRoom -> MeshConstants.MAX_TEXT_BYTES - SEALED_OVERHEAD
        is Carriage.ToOneNode -> MeshConstants.MAX_TEXT_BYTES
        is Carriage.OpenChannel -> MeshConstants.MAX_TEXT_BYTES
        is Carriage.Refused -> 0
    }

    /**
     * Sealing costs a version byte, a nonce and a tag. Stated here rather than
     * imported so `:core:protocol` keeps no dependency on `:core:crypto`;
     * SealedTextTest asserts the two agree.
     */
    const val SEALED_OVERHEAD: Int = 1 + 12 + 16
}
