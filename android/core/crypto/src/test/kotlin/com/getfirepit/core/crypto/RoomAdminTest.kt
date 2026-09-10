package com.getfirepit.core.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomAdminTest {
    private val admin = RoomAdmin.generateKeyPair()
    private val stranger = RoomAdmin.generateKeyPair()
    private val psk = ByteArray(32) { it.toByte() }

    @Test
    fun `an invite signed by an admin verifies against their public key`() {
        val message = RoomAdmin.inviteBytes(roomId = 7, generation = 1, inviteId = 99, psk = psk)
        val signature = RoomAdmin.sign(admin.private, message)

        assertTrue(RoomAdmin.verify(admin.public.encoded, message, signature))
    }

    @Test
    fun `a signature from anyone else is refused`() {
        val message = RoomAdmin.inviteBytes(7, 1, 99, psk)
        val forged = RoomAdmin.sign(stranger.private, message)

        assertFalse(RoomAdmin.verify(admin.public.encoded, message, forged))
    }

    @Test
    fun `changing the key an invite carries breaks its signature`() {
        val message = RoomAdmin.inviteBytes(7, 1, 99, psk)
        val signature = RoomAdmin.sign(admin.private, message)
        val swapped = RoomAdmin.inviteBytes(7, 1, 99, ByteArray(32) { 9 })

        assertFalse(RoomAdmin.verify(admin.public.encoded, swapped, signature))
    }

    @Test
    fun `an invite for one room cannot be replayed into another`() {
        val message = RoomAdmin.inviteBytes(7, 1, 99, psk)
        val signature = RoomAdmin.sign(admin.private, message)

        assertFalse(RoomAdmin.verify(admin.public.encoded, RoomAdmin.inviteBytes(8, 1, 99, psk), signature))
    }

    @Test
    fun `a grant does not survive a key rotation`() {
        val grant = RoomAdmin.grantBytes(7, generation = 1, granteePublicKey = stranger.public.encoded)
        val signature = RoomAdmin.sign(admin.private, grant)
        val nextGeneration = RoomAdmin.grantBytes(7, 2, stranger.public.encoded)

        assertFalse(RoomAdmin.verify(admin.public.encoded, nextGeneration, signature))
    }

    @Test
    fun `a grant cannot be replayed as an invite`() {
        val grant = RoomAdmin.grantBytes(7, 1, stranger.public.encoded)
        val signature = RoomAdmin.sign(admin.private, grant)
        val invite = RoomAdmin.inviteBytes(7, 1, 99, stranger.public.encoded)

        assertFalse(RoomAdmin.verify(admin.public.encoded, invite, signature))
    }

    @Test
    fun `promoting two different people produces two different grants`() {
        val one = RoomAdmin.grantBytes(7, 1, stranger.public.encoded)
        val two = RoomAdmin.grantBytes(7, 1, RoomAdmin.generateKeyPair().public.encoded)

        assertNotEquals(one.toList(), two.toList())
    }

    @Test
    fun `rubbish is refused rather than crashing`() {
        val message = RoomAdmin.inviteBytes(7, 1, 99, psk)

        assertFalse(RoomAdmin.verify(ByteArray(0), message, ByteArray(64)))
        assertFalse(RoomAdmin.verify(admin.public.encoded, message, ByteArray(0)))
        assertFalse(RoomAdmin.verify(ByteArray(10) { 3 }, message, ByteArray(64) { 3 }))
    }
}
