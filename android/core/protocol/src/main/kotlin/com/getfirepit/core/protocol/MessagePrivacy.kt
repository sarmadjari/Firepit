package com.getfirepit.core.protocol

import com.getfirepit.core.model.BROADCAST_NODE_NUM
import com.getfirepit.core.model.RoomKind

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
     * Sealed by us to the recipient's phone key, then handed to the firmware
     * with their radio key as well. Relays read nothing, and neither does
     * whoever holds either radio: the radios' own keys open only the outer layer.
     */
    data class SealedDirect(val nodeNum: Int) : Carriage {
        override val isPrivate = true
    }

    /**
     * Handed to the firmware with the recipient's radio key alone, for somebody
     * whose phone key we have never learned — typically a person not running
     * Firepit. The mesh cannot read it, but a radio gives its private key to any
     * phone that connects, so whoever holds either radio can. The conversation
     * says so on its face.
     */
    data class ToOneNode(val nodeNum: Int) : Carriage {
        /** Private from the mesh, never from whoever holds either radio. */
        override val isPrivate = false
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

        /**
         * A Firepit room whose key is not on this phone. Words sent here could
         * only travel under the channel key, which every member's radio holds.
         */
        ROOM_KEY_MISSING,

        /**
         * A Firepit room that moved to a new key which never reached us. The
         * old key is what a removed member still holds, so words sealed under
         * it would reach them and nobody else.
         */
        ROOM_MOVED_ON,
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
     * channel is, which is all an interoperable channel has. [hasPeerKey] is
     * their radio key, without which the firmware will not encrypt to them;
     * [hasPeerPhoneKey] is their phone key, which is what makes a direct
     * message private from the radios too. [roomKind] names a Firepit room this
     * phone may not send in.
     */
    fun carriageFor(
        to: Int,
        channel: Int,
        isRoomSlot: Boolean,
        sealingRoomId: Int?,
        hasPeerKey: Boolean,
        channelKey: ChannelKey,
        hasPeerPhoneKey: Boolean = false,
        roomKind: RoomKind? = null,
    ): Carriage = when {
        // One person: their key or nothing. A direct message that falls back to
        // a channel is a direct message the channel can read.
        to != BROADCAST_NODE_NUM -> when {
            !hasPeerKey -> Carriage.Refused(Carriage.Reason.NO_PEER_KEY)
            hasPeerPhoneKey -> Carriage.SealedDirect(to)
            else -> Carriage.ToOneNode(to)
        }

        !isRoomSlot -> Carriage.Refused(Carriage.Reason.NOT_A_ROOM)

        // Still a Firepit room on the radio, but not one this phone can seal
        // for. Falling back to the channel key here is exactly the downgrade
        // the rest of this file exists to prevent.
        roomKind == RoomKind.FIREPIT_KEY_MISSING -> Carriage.Refused(Carriage.Reason.ROOM_KEY_MISSING)
        roomKind == RoomKind.FIREPIT_MOVED_ON -> Carriage.Refused(Carriage.Reason.ROOM_MOVED_ON)

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
        is Carriage.SealedDirect -> MAX_DIRECT_SEALED_TEXT_BYTES
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

    /** A sealed direct message costs the same version byte, nonce and tag. DirectSealTest checks it. */
    const val DIRECT_SEALED_OVERHEAD: Int = 1 + 12 + 16

    /**
     * What a sealed direct message leaves for words. It rides inside PKI, which
     * takes 12 bytes of the payload, and the envelope around the words takes
     * the rest. ProtocolContractTest encodes the largest one to prove it fits.
     */
    const val MAX_DIRECT_SEALED_TEXT_BYTES: Int = 170
}
