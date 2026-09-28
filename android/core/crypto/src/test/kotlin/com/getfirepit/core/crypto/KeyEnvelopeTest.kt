package com.getfirepit.core.crypto

import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layer that keeps a room's own key away from the radios it passes through.
 *
 * If any of these start opening something they should not, the room key is back
 * to being readable by whoever holds a member's radio.
 */
class KeyEnvelopeTest {

    private val joiner = KeyEnvelope.generateKeyPair()
    private val joinerPublic = KeyEnvelope.publicBytes(joiner.public)
    private val roomKey = RoomCipher.generateKey()
    private val context = KeyEnvelope.contextOf(roomId = 0x0BADF00D, generation = 3, recipientNodeNum = 42)

    @Test
    fun `the phone it was sealed to opens it`() {
        val sealed = KeyEnvelope.seal(joinerPublic, roomKey, context)

        assertEquals(KeyEnvelope.SEALED_SIZE, sealed.size)
        assertArrayEquals(roomKey, KeyEnvelope.open(joiner.private, joinerPublic, sealed, context))
    }

    @Test
    fun `any other phone cannot`() {
        val other = KeyEnvelope.generateKeyPair()
        val sealed = KeyEnvelope.seal(joinerPublic, roomKey, context)

        assertNull(KeyEnvelope.open(other.private, KeyEnvelope.publicBytes(other.public), sealed, context))
        assertNull(KeyEnvelope.open(other.private, joinerPublic, sealed, context))
    }

    @Test
    fun `a key sealed for one room, generation or person opens for no other`() {
        val sealed = KeyEnvelope.seal(joinerPublic, roomKey, context)

        listOf(
            KeyEnvelope.contextOf(roomId = 0x0BADF00E, generation = 3, recipientNodeNum = 42),
            KeyEnvelope.contextOf(roomId = 0x0BADF00D, generation = 4, recipientNodeNum = 42),
            KeyEnvelope.contextOf(roomId = 0x0BADF00D, generation = 3, recipientNodeNum = 43),
        ).forEach { elsewhere ->
            assertNull(KeyEnvelope.open(joiner.private, joinerPublic, sealed, elsewhere))
        }
    }

    @Test
    fun `a changed byte anywhere is refused`() {
        val sealed = KeyEnvelope.seal(joinerPublic, roomKey, context)

        sealed.indices.forEach { index ->
            val tampered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertNull("byte $index", KeyEnvelope.open(joiner.private, joinerPublic, tampered, context))
        }
    }

    @Test
    fun `two seals of the same key look unrelated`() {
        val first = KeyEnvelope.seal(joinerPublic, roomKey, context)
        val second = KeyEnvelope.seal(joinerPublic, roomKey, context)

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `a stored private key still opens what was sealed to it`() {
        val restored = KeyEnvelope.restorePrivate(KeyEnvelope.privateBytes(joiner))
        val sealed = KeyEnvelope.seal(joinerPublic, roomKey, context)

        assertNotNull(restored)
        assertArrayEquals(roomKey, KeyEnvelope.open(restored!!, joinerPublic, sealed, context))
    }

    @Test
    fun `public keys are compressed points`() {
        assertEquals(KeyEnvelope.PUBLIC_KEY_SIZE, joinerPublic.size)
        assertTrue(joinerPublic[0] == 0x02.toByte() || joinerPublic[0] == 0x03.toByte())
        assertTrue(KeyEnvelope.isValidPublicKey(joinerPublic))
    }

    /** ProtocolContractTest restates these to size packets, since it cannot see this module. */
    @Test
    fun `the sizes the packet budgets are planned with`() {
        assertEquals(33, KeyEnvelope.PUBLIC_KEY_SIZE)
        assertEquals(93, KeyEnvelope.SEALED_SIZE)
    }

    /**
     * An x with no point above it is exactly what an invalid-curve attack sends.
     * Found here with Euler's criterion, independently of the code under test.
     */
    @Test
    fun `an x coordinate that is not on the curve is refused`() {
        val offCurve = generateSequence(BigInteger.ONE) { it + BigInteger.ONE }
            .first { x -> !isQuadraticResidue(x.pow(3) + A * x + B) }

        val bytes = byteArrayOf(0x02) + fixed(offCurve)

        assertFalse(KeyEnvelope.isValidPublicKey(bytes))
        assertNull(KeyEnvelope.open(joiner.private, joinerPublic, bytes + ByteArray(60), context))
    }

    @Test
    fun `anything that is not a compressed point is refused`() {
        assertFalse(KeyEnvelope.isValidPublicKey(ByteArray(0)))
        assertFalse(KeyEnvelope.isValidPublicKey(ByteArray(32)))
        assertFalse(KeyEnvelope.isValidPublicKey(joinerPublic.copyOf().also { it[0] = 0x04 }))
        assertFalse(KeyEnvelope.isValidPublicKey(byteArrayOf(0x02) + ByteArray(32) { 0xFF.toByte() }))
        assertFalse(KeyEnvelope.isValidPublicKey(joinerPublic + byteArrayOf(0)))
    }

    @Test
    fun `too short to hold a sealed key is refused rather than thrown`() {
        assertNull(KeyEnvelope.open(joiner.private, joinerPublic, ByteArray(10), context))
    }

    private fun isQuadraticResidue(value: BigInteger): Boolean =
        value.mod(P).modPow((P - BigInteger.ONE).shiftRight(1), P) == BigInteger.ONE

    private fun fixed(value: BigInteger): ByteArray {
        val raw = value.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return ByteArray(32 - raw.size) + raw
    }

    private companion object {
        // NIST P-256 (SEC 2, secp256r1).
        val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
        val A: BigInteger = P - BigInteger.valueOf(3)
        val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    }
}
