package com.getfirepit.core.protocol

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide which mesh traffic is believed.
 *
 * Each negative case here is an attack that worked before the rule existed.
 */
class TrustRulesTest {

    private val bob = key(1)
    private val eve = key(2)
    private val bobPhone = key(3, size = 33)
    private val evePhone = key(4, size = 33)

    // --- join hello ----------------------------------------------------------

    @Test
    fun `a hello decrypted with the key it names is believed`() {
        assertTrue(TrustRules.helloIsBound(pkiEncrypted = true, addressedToUs = true, decryptedWith = bob, claimed = bob))
    }

    @Test
    fun `a hello that names a key other than the one it came under is not`() {
        assertFalse(TrustRules.helloIsBound(true, true, decryptedWith = bob, claimed = eve))
    }

    @Test
    fun `an unencrypted or misaddressed hello is not`() {
        assertFalse(TrustRules.helloIsBound(pkiEncrypted = false, addressedToUs = true, decryptedWith = bob, claimed = bob))
        assertFalse(TrustRules.helloIsBound(pkiEncrypted = true, addressedToUs = false, decryptedWith = bob, claimed = bob))
    }

    @Test
    fun `a hello with no usable key is not`() {
        assertFalse(TrustRules.helloIsBound(true, true, decryptedWith = ByteString.EMPTY, claimed = ByteString.EMPTY))
    }

    @Test
    fun `a second hello cannot swap the keys of one already waiting`() {
        assertTrue(TrustRules.mayReplacePending(null, null, bob, bobPhone))
        assertTrue(TrustRules.mayReplacePending(bob, bobPhone, bob, bobPhone))
        assertFalse(TrustRules.mayReplacePending(bob, bobPhone, eve, bobPhone))
        assertFalse(TrustRules.mayReplacePending(bob, bobPhone, bob, evePhone))
    }

    // --- scanning ------------------------------------------------------------

    @Test
    fun `a scanned code never overwrites a key the radio already holds`() {
        assertEquals(TrustRules.Contact.ADD, TrustRules.contactFor(knownKey = null, codeKey = bob))
        assertEquals(TrustRules.Contact.KNOWN, TrustRules.contactFor(knownKey = bob, codeKey = bob))
        assertEquals(TrustRules.Contact.MISMATCH, TrustRules.contactFor(knownKey = bob, codeKey = eve))
    }

    // --- grants --------------------------------------------------------------

    @Test
    fun `a grant for a new room is taken`() {
        assertTrue(TrustRules.mayTakeGrant(alreadyHeld = false, senderIsMember = false, grantGeneration = 1, currentGeneration = 1))
    }

    @Test
    fun `a stranger cannot overwrite a room we already hold`() {
        assertFalse(TrustRules.mayTakeGrant(alreadyHeld = true, senderIsMember = false, grantGeneration = 9, currentGeneration = 1))
    }

    @Test
    fun `a member can bring us forward, never back`() {
        assertTrue(TrustRules.mayTakeGrant(alreadyHeld = true, senderIsMember = true, grantGeneration = 3, currentGeneration = 2))
        assertFalse(TrustRules.mayTakeGrant(alreadyHeld = true, senderIsMember = true, grantGeneration = 2, currentGeneration = 2))
        assertFalse(TrustRules.mayTakeGrant(alreadyHeld = true, senderIsMember = true, grantGeneration = 1, currentGeneration = 2))
        assertTrue(
            TrustRules.mayTakeGrant(
                alreadyHeld = true,
                senderIsMember = true,
                grantGeneration = 2,
                currentGeneration = 2,
                awaitingScannedInvite = true,
            ),
        )
    }

    // --- rotation ------------------------------------------------------------

    private fun rotation(
        sealedUnderRoom: Int? = ROOM,
        sealedUnderGeneration: Int? = 2,
        rotationRoom: Int = ROOM,
        rotationGeneration: Int = 3,
        privatelyToUs: Boolean = true,
        senderIsMember: Boolean = true,
    ) = TrustRules.rotationAcceptable(
        sealedUnderRoom = sealedUnderRoom,
        sealedUnderGeneration = sealedUnderGeneration,
        rotationRoom = rotationRoom,
        rotationGeneration = rotationGeneration,
        currentGeneration = 2,
        privatelyToUs = privatelyToUs,
        senderIsMember = senderIsMember,
    )

    @Test
    fun `a rotation sealed under the current key, privately, from a member, is taken`() {
        assertTrue(rotation())
    }

    @Test
    fun `a rotation that does not prove it holds the current key is refused`() {
        assertFalse("unsealed", rotation(sealedUnderRoom = null, sealedUnderGeneration = null))
        assertFalse("an old generation's key", rotation(sealedUnderGeneration = 1))
        assertFalse("another room's key", rotation(sealedUnderRoom = ROOM + 1))
    }

    @Test
    fun `a rotation broadcast, from a stranger, or not moving forward is refused`() {
        assertFalse(rotation(privatelyToUs = false))
        assertFalse(rotation(senderIsMember = false))
        assertFalse(rotation(rotationGeneration = 2))
    }

    // --- placement and rosters ----------------------------------------------

    @Test
    fun `a sealed message is only believed on its own room's slot or privately`() {
        assertTrue(TrustRules.sealedPlacementOk(slotRoom = ROOM, sealedRoom = ROOM, privatelyToUs = false))
        assertTrue(TrustRules.sealedPlacementOk(slotRoom = null, sealedRoom = ROOM, privatelyToUs = true))
        assertFalse(TrustRules.sealedPlacementOk(slotRoom = ROOM + 1, sealedRoom = ROOM, privatelyToUs = false))
        assertFalse(TrustRules.sealedPlacementOk(slotRoom = null, sealedRoom = ROOM, privatelyToUs = false))
    }

    private fun sync(
        privatelyToUs: Boolean = true,
        sender: Int = 7,
        ourInviter: Int? = 7,
        senderIsMember: Boolean = true,
        sealedUnderCurrent: Boolean = true,
    ) = TrustRules.rosterSyncAcceptable(privatelyToUs, sender, ourInviter, senderIsMember, sealedUnderCurrent)

    @Test
    fun `a roster sync is only the word of whoever let us in`() {
        assertTrue(sync())
        assertFalse(sync(privatelyToUs = false))
        assertFalse(sync(sender = 8))
        assertFalse(sync(ourInviter = null))
    }

    @Test
    fun `an inviter since removed cannot put themselves back`() {
        assertFalse(sync(senderIsMember = false))
    }

    /** Unsealed, the membership list is readable by both radios it passes through. */
    @Test
    fun `a roster sync only counts sealed under the room's current key`() {
        assertFalse(sync(sealedUnderCurrent = false))
    }

    // --- rotation notices ----------------------------------------------------

    @Test
    fun `a notice that the room moved on is believed from a member under the current key`() {
        assertTrue(TrustRules.rotationNoticeAcceptable(true, senderIsMember = true, noticeGeneration = 3, currentGeneration = 2))
    }

    @Test
    fun `a rotation notice under an old key, from a stranger, or not moving forward is ignored`() {
        assertFalse(TrustRules.rotationNoticeAcceptable(false, senderIsMember = true, noticeGeneration = 3, currentGeneration = 2))
        assertFalse(TrustRules.rotationNoticeAcceptable(true, senderIsMember = false, noticeGeneration = 3, currentGeneration = 2))
        assertFalse(TrustRules.rotationNoticeAcceptable(true, senderIsMember = true, noticeGeneration = 2, currentGeneration = 2))
        assertFalse(TrustRules.rotationNoticeAcceptable(true, senderIsMember = true, noticeGeneration = 1, currentGeneration = 2))
    }

    /**
     * A removed member still holds the old key, so they can seal a notice. One
     * naming a generation far ahead would leave members waiting for a key that
     * never comes; only the very next generation is believed.
     */
    @Test
    fun `a rotation notice that skips ahead is ignored`() {
        assertFalse(TrustRules.rotationNoticeAcceptable(true, senderIsMember = true, noticeGeneration = 4, currentGeneration = 2))
        assertFalse(
            TrustRules.rotationNoticeAcceptable(true, senderIsMember = true, noticeGeneration = Int.MAX_VALUE, currentGeneration = 2),
        )
    }

    // --- positions -----------------------------------------------------------

    @Test
    fun `a sealed position counts only under the current key on its own room`() {
        assertTrue(TrustRules.sealedPositionAcceptable(sealedUnderCurrent = true, onItsRoomSlot = true))
        assertFalse(TrustRules.sealedPositionAcceptable(sealedUnderCurrent = false, onItsRoomSlot = true))
        assertFalse(TrustRules.sealedPositionAcceptable(sealedUnderCurrent = true, onItsRoomSlot = false))
    }

    /** A member's position only ever travels sealed; an unsealed one was written by a radio holder. */
    @Test
    fun `an unsealed position is never believed about a member`() {
        assertFalse(TrustRules.unsealedPositionAcceptable(senderInOurRooms = true))
        assertTrue(TrustRules.unsealedPositionAcceptable(senderInOurRooms = false))
    }

    @Test
    fun `where we are is only said to a member of the room we share with`() {
        assertTrue(TrustRules.positionQueryAnswerable(true, addressedToUs = true, queryRoom = ROOM, sharingWithRoom = ROOM))
        assertFalse(TrustRules.positionQueryAnswerable(true, addressedToUs = true, queryRoom = ROOM, sharingWithRoom = null))
        assertFalse(TrustRules.positionQueryAnswerable(true, addressedToUs = true, queryRoom = ROOM, sharingWithRoom = ROOM + 1))
        assertFalse(TrustRules.positionQueryAnswerable(false, addressedToUs = true, queryRoom = ROOM, sharingWithRoom = ROOM))
        assertFalse(TrustRules.positionQueryAnswerable(true, addressedToUs = false, queryRoom = ROOM, sharingWithRoom = ROOM))
    }

    // --- phone keys ----------------------------------------------------------

    @Test
    fun `phone key provenance rule table`() {
        val sources = TrustRules.PhoneKeySource.entries

        sources.forEach { source ->
            val decision = TrustRules.shouldStorePhoneKey(
                known = null,
                knownInPerson = false,
                incoming = bobPhone,
                source = source,
            )
            assertEquals("first sight $source", true, decision.store)
            assertEquals("first sight provenance $source", source == TrustRules.PhoneKeySource.IN_PERSON, decision.inPerson)
            assertEquals("first sight is not a replacement $source", false, decision.replaced)
        }

        sources.forEach { source ->
            listOf(false, true).forEach { knownInPerson ->
                val decision = TrustRules.shouldStorePhoneKey(
                    known = bobPhone,
                    knownInPerson = knownInPerson,
                    incoming = bobPhone,
                    source = source,
                )
                assertEquals("same key stores only to mark in-person: $source/$knownInPerson",
                    source == TrustRules.PhoneKeySource.IN_PERSON && !knownInPerson,
                    decision.store,
                )
                assertEquals(
                    "same key provenance $source/$knownInPerson",
                    knownInPerson || source == TrustRules.PhoneKeySource.IN_PERSON,
                    decision.inPerson,
                )
                assertEquals("same key never replaces $source/$knownInPerson", false, decision.replaced)
            }
        }

        sources.forEach { source ->
            listOf(false, true).forEach { knownInPerson ->
                val decision = TrustRules.shouldStorePhoneKey(
                    known = bobPhone,
                    knownInPerson = knownInPerson,
                    incoming = evePhone,
                    source = source,
                )
                val shouldStore = source == TrustRules.PhoneKeySource.IN_PERSON ||
                    (source == TrustRules.PhoneKeySource.VOUCHED && !knownInPerson)
                assertEquals("different key store $source/$knownInPerson", shouldStore, decision.store)
                assertEquals(
                    "different key provenance $source/$knownInPerson",
                    if (shouldStore) source == TrustRules.PhoneKeySource.IN_PERSON else knownInPerson,
                    decision.inPerson,
                )
                assertEquals("different key replaced $source/$knownInPerson", shouldStore, decision.replaced)
            }
        }
    }

    // --- pins ----------------------------------------------------------------

    private fun pin(
        sealedInRoom: Int? = ROOM,
        sender: Int = 7,
        claimedLock: Int = 7,
        existingLock: Int? = null,
        existingRoom: Int? = null,
    ) = TrustRules.pinUpdateAllowed(sealedInRoom, sender, claimedLock, existingLock, existingRoom)

    @Test
    fun `a pin sealed in a room by its owner is taken`() {
        assertTrue(pin())
        assertTrue(pin(claimedLock = 0))
    }

    /** Unsealed, anyone holding a member's radio could have written it under any name. */
    @Test
    fun `a pin that was not sealed in a room is refused`() {
        assertFalse(pin(sealedInRoom = null))
    }

    @Test
    fun `nobody can lock a pin to somebody else`() {
        assertFalse(pin(claimedLock = 8))
    }

    @Test
    fun `a locked pin only changes at its owner's hand, and never changes room`() {
        assertTrue(pin(sender = 7, existingLock = 7, existingRoom = ROOM))
        assertFalse(pin(sender = 8, claimedLock = 0, existingLock = 7, existingRoom = ROOM))
        assertTrue(pin(sender = 8, claimedLock = 0, existingLock = 0, existingRoom = ROOM))
        assertFalse(pin(sender = 7, existingLock = 7, existingRoom = ROOM + 1))
    }

    // --- receipts ------------------------------------------------------------

    @Test
    fun `a receipt for a room message comes only from a member`() {
        assertTrue(TrustRules.receiptAllowed(true, MeshConstants.BROADCAST_NODENUM, sender = 7, senderIsRoomMember = true))
        assertFalse(TrustRules.receiptAllowed(true, MeshConstants.BROADCAST_NODENUM, sender = 7, senderIsRoomMember = false))
    }

    @Test
    fun `a receipt for a direct message comes only from its recipient`() {
        assertTrue(TrustRules.receiptAllowed(true, sentTo = 7, sender = 7, senderIsRoomMember = false))
        assertFalse(TrustRules.receiptAllowed(true, sentTo = 7, sender = 8, senderIsRoomMember = true))
    }

    @Test
    fun `nobody can receipt a message we did not send`() {
        assertFalse(TrustRules.receiptAllowed(false, sentTo = 7, sender = 7, senderIsRoomMember = true))
    }

    // --- unsealed text -------------------------------------------------------

    @Test
    fun `a direct message is only stored when it came under PKI`() {
        assertTrue(TrustRules.plainTextAcceptable(direct = true, pkiEncrypted = true, onPrimary = true, onFirepitRoom = false))
        assertFalse(TrustRules.plainTextAcceptable(direct = true, pkiEncrypted = false, onPrimary = true, onFirepitRoom = false))
        assertFalse(TrustRules.plainTextAcceptable(direct = true, pkiEncrypted = false, onPrimary = false, onFirepitRoom = true))
    }

    @Test
    fun `unsealed text on a sealed room or the primary is dropped`() {
        assertFalse(TrustRules.plainTextAcceptable(direct = false, pkiEncrypted = false, onPrimary = false, onFirepitRoom = true))
        assertFalse(TrustRules.plainTextAcceptable(direct = false, pkiEncrypted = false, onPrimary = true, onFirepitRoom = false))
    }

    @Test
    fun `text on an ordinary Meshtastic channel is kept`() {
        assertTrue(TrustRules.plainTextAcceptable(direct = false, pkiEncrypted = false, onPrimary = false, onFirepitRoom = false))
    }

    private fun key(seed: Int, size: Int = 32): ByteString = ByteArray(size) { seed.toByte() }.toByteString()

    private companion object {
        const val ROOM = 0x0BADF00D
    }
}
