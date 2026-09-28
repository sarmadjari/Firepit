package com.getfirepit.core.protocol

import okio.ByteString

/**
 * Which traffic from the mesh is believed, stated as data rather than as
 * branches scattered through the repositories.
 *
 * Everything that arrives over the air is attacker-controlled: node numbers,
 * room ids and invite ids can all be named by anyone who has seen them. Each
 * rule here was missing at some point, and each one is what stands between a
 * stranger with a radio and somebody else's room.
 */
object TrustRules {

    /** Curve25519 public key length, which is what the firmware's PKI uses. */
    const val RADIO_KEY_SIZE = 32

    /**
     * A join hello is believed only when the firmware decrypted it with the
     * very key it names.
     *
     * The key inside the hello is where the grant goes. Were it merely claimed,
     * a spoofed hello could point the room's keys at a stranger; the firmware
     * reports the key it actually decrypted with, and the two must agree.
     */
    fun helloIsBound(
        pkiEncrypted: Boolean,
        addressedToUs: Boolean,
        decryptedWith: ByteString,
        claimed: ByteString,
    ): Boolean = pkiEncrypted && addressedToUs && claimed.size == RADIO_KEY_SIZE && decryptedWith == claimed

    /**
     * A later hello from the same node never replaces a waiting one that named
     * different keys: the first one is what the person in front of you is
     * checking the fingerprint of.
     */
    fun mayReplacePending(
        waitingRadioKey: ByteString?,
        waitingPhoneKey: ByteString?,
        radioKey: ByteString,
        phoneKey: ByteString,
    ): Boolean = waitingRadioKey == null || (waitingRadioKey == radioKey && waitingPhoneKey == phoneKey)

    /** What to do with the inviter a scanned code names, judged against the radio's own NodeDB. */
    enum class Contact {
        /** The radio has never heard of them; the code's key is all there is. */
        ADD,

        /** The radio already holds this very key. Adding it again is harmless, and repairs an eviction. */
        KNOWN,

        /** The radio holds a different key for that node: the code is lying about who it is. */
        MISMATCH,
    }

    fun contactFor(knownKey: ByteString?, codeKey: ByteString): Contact = when {
        knownKey == null -> Contact.ADD
        knownKey == codeKey -> Contact.KNOWN
        else -> Contact.MISMATCH
    }

    /**
     * A grant for a room this phone already holds is only taken from a member
     * of it, and only to move it forward — otherwise a stranger's code naming
     * one of our room ids would overwrite that room with keys they chose.
     */
    fun mayTakeGrant(
        alreadyHeld: Boolean,
        senderIsMember: Boolean,
        grantGeneration: Int,
        currentGeneration: Int,
    ): Boolean = !alreadyHeld || (senderIsMember && grantGeneration > currentGeneration)

    /**
     * New keys for a room are believed only when they arrive privately, to us,
     * from a member, sealed under the key they replace, and move the room on.
     *
     * Sealing under the current key is what makes the sender prove they hold
     * it. Anyone can encrypt to a public key, so PKI alone proves nothing
     * about membership.
     */
    fun rotationAcceptable(
        sealedUnderRoom: Int?,
        sealedUnderGeneration: Int?,
        rotationRoom: Int,
        rotationGeneration: Int,
        currentGeneration: Int,
        privatelyToUs: Boolean,
        senderIsMember: Boolean,
    ): Boolean =
        sealedUnderRoom == rotationRoom &&
            sealedUnderGeneration == currentGeneration &&
            privatelyToUs &&
            senderIsMember &&
            rotationGeneration > currentGeneration

    /**
     * Where a sealed message may be believed.
     *
     * On the slot of the room it names, or directly to us over PKI. Otherwise a
     * member of two rooms could have one room's words shown in the other.
     */
    fun sealedPlacementOk(slotRoom: Int?, sealedRoom: Int, privatelyToUs: Boolean): Boolean =
        privatelyToUs || slotRoom == sealedRoom

    /**
     * A roster sync is only the word of the inviter who let us in, sent to us
     * alone, and only while they are still in the room: an inviter since
     * removed would otherwise put themselves back on the list new keys go to.
     */
    fun rosterSyncAcceptable(privatelyToUs: Boolean, sender: Int, ourInviter: Int?, senderIsMember: Boolean): Boolean =
        privatelyToUs && senderIsMember && ourInviter != null && sender == ourInviter

    /**
     * Whether to store a phone key for a node.
     *
     * Learned on first sight and then kept, so a card cannot quietly replace
     * somebody's key. Only [vouched] — a join a person approved, in front of
     * them — may replace one; that is how somebody who returns with a new phone
     * stays reachable. A false key gains nobody anything to read, since a
     * rotation still travels PKI to the member's own radio; at worst that
     * member misses one key.
     */
    fun shouldStorePhoneKey(known: ByteString?, incoming: ByteString, vouched: Boolean): Boolean = when {
        known == null -> true
        known == incoming -> false
        else -> vouched
    }

    /**
     * Whether a waypoint may create or change a pin.
     *
     * Only on a Firepit room's slot, never locked to somebody else, never moved
     * to another room, and never overriding the lock its owner set.
     */
    fun pinUpdateAllowed(
        onFirepitRoom: Boolean,
        sender: Int,
        claimedLock: Int,
        existingLock: Int?,
        existingChannel: Int?,
        channel: Int,
    ): Boolean =
        onFirepitRoom &&
            (claimedLock == 0 || claimedLock == sender) &&
            (existingChannel == null || existingChannel == channel) &&
            (existingLock == null || existingLock == 0 || existingLock == sender)

    /**
     * Whether [sender] may say they received or read one of our messages: the
     * one person a direct message went to, or a member of the room it was sent
     * in. Nobody else was ever meant to see it.
     */
    fun receiptAllowed(
        isOutgoing: Boolean,
        sentTo: Int,
        sender: Int,
        senderIsRoomMember: Boolean,
    ): Boolean = isOutgoing && if (sentTo == MeshConstants.BROADCAST_NODENUM) senderIsRoomMember else sentTo == sender

    /**
     * Whether unsealed text may be stored.
     *
     * A direct message only when the firmware decrypted it with the sender's
     * key: the channel key is shared by everyone on the channel. Never on the
     * primary, which is nobody's conversation, and never on a Firepit room's
     * slot, where anything real arrives sealed and anything unsealed was typed
     * by whoever holds a member's radio.
     */
    fun plainTextAcceptable(direct: Boolean, pkiEncrypted: Boolean, onPrimary: Boolean, onFirepitRoom: Boolean): Boolean =
        when {
            direct -> pkiEncrypted
            onPrimary -> false
            else -> !onFirepitRoom
        }
}
