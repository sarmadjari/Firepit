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
        awaitingScannedInvite: Boolean = false,
    ): Boolean = !alreadyHeld || (senderIsMember && grantGeneration > currentGeneration) ||
        (awaitingScannedInvite && senderIsMember && grantGeneration == currentGeneration)

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
     * alone, sealed under the room's current key, and only while they are
     * still in the room: an inviter since removed would otherwise put
     * themselves back on the list new keys go to. The seal also keeps the
     * membership list away from the radios it passes through.
     */
    fun rosterSyncAcceptable(
        privatelyToUs: Boolean,
        sender: Int,
        ourInviter: Int?,
        senderIsMember: Boolean,
        sealedUnderCurrent: Boolean,
    ): Boolean = privatelyToUs && sealedUnderCurrent && senderIsMember && ourInviter != null && sender == ourInviter

    /**
     * A notice that the room has moved to a new key.
     *
     * Believed only sealed under the key we hold now, from a member, and naming
     * the very next generation: it arrives before our own copy of the new key,
     * or instead of it when that copy never reaches us, and either way we must
     * stop talking under a key the removed member still holds. Exactly the next
     * one, because a notice is always sealed under the key it replaces — and a
     * removed member who still holds that key must not be able to name a
     * generation so far ahead that no real key ever clears it.
     */
    fun rotationNoticeAcceptable(
        sealedUnderCurrent: Boolean,
        senderIsMember: Boolean,
        noticeGeneration: Int,
        currentGeneration: Int,
    ): Boolean = sealedUnderCurrent && senderIsMember && noticeGeneration == currentGeneration + 1

    /**
     * A member's position, sealed by their phone.
     *
     * Only under the room's current key, on the room's own slot: an older key
     * is what a removed member still holds, and a room's positions belong in
     * that room.
     */
    fun sealedPositionAcceptable(sealedUnderCurrent: Boolean, onItsRoomSlot: Boolean): Boolean =
        sealedUnderCurrent && onItsRoomSlot

    /**
     * An unsealed position, from the firmware's own broadcast or the radio's
     * node list.
     *
     * A node outside our rooms has nothing else to send, so its position is
     * kept, as it always was, for the map's "everyone this radio has heard".
     * A room member's phone only ever sends positions sealed, so an unsealed
     * one naming a member is taken only as the opt-in safety net: on the
     * channel of a Firepit room we share, never on the primary and never PKI,
     * and it is marked as from their radio.
     */
    fun unsealedPositionAcceptable(
        senderInOurRooms: Boolean,
        channel: Int,
        sharedFirepitRoomSlot: Int?,
        pkiEncrypted: Boolean,
    ): Boolean = !senderInOurRooms || (
        !pkiEncrypted &&
            channel != ChannelSlotManager.PRIMARY_SLOT &&
            sharedFirepitRoomSlot == channel
        )

    /**
     * A radio safety-net position must not roll back a newer phone-sealed fix.
     */
    fun radioPositionMayReplace(
        existingHasPosition: Boolean,
        existingFromRadio: Boolean,
        existingPositionTime: Long?,
        incomingPositionTime: Long?,
        nowMillis: Long,
    ): Boolean {
        if (!existingHasPosition) return true
        if (existingFromRadio) {
            return existingPositionTime == null ||
                (incomingPositionTime != null && incomingPositionTime >= existingPositionTime)
        }
        if (existingPositionTime != null && nowMillis - existingPositionTime < RADIO_POSITION_SEALED_QUIET_MILLIS) {
            return false
        }
        return existingPositionTime == null || (incomingPositionTime != null && incomingPositionTime > existingPositionTime)
    }

    private const val RADIO_POSITION_SEALED_QUIET_MILLIS = 10 * 60 * 1000L

    /**
     * Whether to answer "where are you?".
     *
     * Only a question sealed under the room's current key, put to us alone,
     * about a room we are sharing with right now. Anything else is somebody
     * finding out where we are without our say.
     */
    fun positionQueryAnswerable(
        sealedUnderCurrent: Boolean,
        addressedToUs: Boolean,
        queryRoom: Int,
        sharingWithRoom: Int?,
    ): Boolean = sealedUnderCurrent && addressedToUs && sharingWithRoom != null && sharingWithRoom == queryRoom

    enum class PhoneKeySource {
        /** This phone approved the join after an in-person fingerprint check. */
        IN_PERSON,

        /** A sealed JOINED roster event from the member who let the newcomer in. */
        VOUCHED,

        /** A person card, or any other announcement that carries a phone key. */
        ANNOUNCED,
    }

    data class PhoneKeyDecision(
        val store: Boolean,
        val inPerson: Boolean,
        val replaced: Boolean,
    )

    /**
     * Whether to store a phone key for a node, and whether it has in-person
     * provenance.
     *
     * First sight is kept. The same key is kept as-is, except that seeing it in
     * person marks it as in-person. A different in-person key replaces any old
     * one; a vouched key replaces only one that was not learned in person; a
     * plain announcement never replaces.
     */
    fun shouldStorePhoneKey(
        known: ByteString?,
        knownInPerson: Boolean,
        incoming: ByteString,
        source: PhoneKeySource,
    ): PhoneKeyDecision = when {
        known == null -> PhoneKeyDecision(store = true, inPerson = source == PhoneKeySource.IN_PERSON, replaced = false)
        known == incoming && source == PhoneKeySource.IN_PERSON && !knownInPerson ->
            PhoneKeyDecision(store = true, inPerson = true, replaced = false)
        known == incoming -> PhoneKeyDecision(store = false, inPerson = knownInPerson, replaced = false)
        source == PhoneKeySource.IN_PERSON -> PhoneKeyDecision(store = true, inPerson = true, replaced = true)
        source == PhoneKeySource.VOUCHED && !knownInPerson ->
            PhoneKeyDecision(store = true, inPerson = false, replaced = true)
        else -> PhoneKeyDecision(store = false, inPerson = knownInPerson, replaced = false)
    }

    /**
     * Whether a pin may be created or changed.
     *
     * Only sealed under a room's current key, so the sender is somebody who
     * holds it rather than whoever holds a member's radio; never locked to
     * somebody else, never moved to another room, and never overriding the lock
     * its owner set. The seal binds [sender], so a lock cannot be dodged by
     * writing somebody else's number in a header.
     */
    fun pinUpdateAllowed(
        sealedInRoom: Int?,
        sender: Int,
        claimedLock: Int,
        existingLock: Int?,
        existingRoom: Int?,
    ): Boolean =
        sealedInRoom != null &&
            (claimedLock == 0 || claimedLock == sender) &&
            (existingRoom == null || existingRoom == sealedInRoom) &&
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
