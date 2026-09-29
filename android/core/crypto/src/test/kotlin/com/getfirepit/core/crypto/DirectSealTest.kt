package com.getfirepit.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The layer that keeps one person's words away from both radios they pass
 * through.
 *
 * If any of these start opening something they should not, a direct message is
 * back to being readable by whoever holds either radio.
 */
class DirectSealTest {

    private val alice = KeyEnvelope.generateKeyPair()
    private val alicePublic = KeyEnvelope.publicBytes(alice.public)
    private val bob = KeyEnvelope.generateKeyPair()
    private val bobPublic = KeyEnvelope.publicBytes(bob.public)
    private val words = "Meet at the ridge at six".toByteArray()
    private val aliceToBob = DirectSeal.contextOf(senderNodeNum = 11, recipientNodeNum = 22)

    @Test
    fun `the phone it was sealed for opens it`() {
        val sealed = DirectSeal.seal(alice.private, alicePublic, bobPublic, words, aliceToBob)

        assertEquals(words.size + DirectSeal.OVERHEAD, sealed.size)
        assertArrayEquals(words, DirectSeal.open(bob.private, bobPublic, alicePublic, sealed, aliceToBob))
    }

    @Test
    fun `the sender can read back what it sealed`() {
        val sealed = DirectSeal.seal(alice.private, alicePublic, bobPublic, words, aliceToBob)

        assertArrayEquals(words, DirectSeal.open(alice.private, alicePublic, bobPublic, sealed, aliceToBob))
    }

    @Test
    fun `any third phone cannot`() {
        val mallory = KeyEnvelope.generateKeyPair()
        val malloryPublic = KeyEnvelope.publicBytes(mallory.public)
        val sealed = DirectSeal.seal(alice.private, alicePublic, bobPublic, words, aliceToBob)

        assertNull(DirectSeal.open(mallory.private, malloryPublic, alicePublic, sealed, aliceToBob))
        assertNull(DirectSeal.open(mallory.private, bobPublic, alicePublic, sealed, aliceToBob))
    }

    @Test
    fun `nobody but the sender's phone can seal as them`() {
        // Mallory knows both public keys, which are no secret, and seals to Bob
        // claiming to be Alice.
        val mallory = KeyEnvelope.generateKeyPair()
        val forged = DirectSeal.seal(mallory.private, KeyEnvelope.publicBytes(mallory.public), bobPublic, words, aliceToBob)

        assertNull(DirectSeal.open(bob.private, bobPublic, alicePublic, forged, aliceToBob))
    }

    @Test
    fun `a message cannot be turned round or re-addressed`() {
        val sealed = DirectSeal.seal(alice.private, alicePublic, bobPublic, words, aliceToBob)

        listOf(
            DirectSeal.contextOf(senderNodeNum = 22, recipientNodeNum = 11),
            DirectSeal.contextOf(senderNodeNum = 11, recipientNodeNum = 23),
            DirectSeal.contextOf(senderNodeNum = 12, recipientNodeNum = 22),
        ).forEach { elsewhere ->
            assertNull(DirectSeal.open(bob.private, bobPublic, alicePublic, sealed, elsewhere))
        }
    }

    @Test
    fun `a changed byte anywhere is refused`() {
        val sealed = DirectSeal.seal(alice.private, alicePublic, bobPublic, words, aliceToBob)

        sealed.indices.forEach { index ->
            val tampered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertNull("byte $index", DirectSeal.open(bob.private, bobPublic, alicePublic, tampered, aliceToBob))
        }
    }

    @Test
    fun `the same words never seal the same way twice`() {
        val first = DirectSeal.seal(alice.private, alicePublic, bobPublic, words, aliceToBob)
        val second = DirectSeal.seal(alice.private, alicePublic, bobPublic, words, aliceToBob)

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `a peer key that is not a usable point is refused rather than used`() {
        // An x at or above the field prime has no point at all.
        val notAPoint = byteArrayOf(0x02) + ByteArray(32) { 0xFF.toByte() }
        val sealed = DirectSeal.seal(alice.private, alicePublic, bobPublic, words, aliceToBob)

        assertNull(DirectSeal.open(bob.private, bobPublic, notAPoint, sealed, aliceToBob))
    }

    @Test
    fun `a truncated or unversioned payload is refused`() {
        val sealed = DirectSeal.seal(alice.private, alicePublic, bobPublic, words, aliceToBob)

        assertNull(DirectSeal.open(bob.private, bobPublic, alicePublic, sealed.copyOf(DirectSeal.OVERHEAD), aliceToBob))
        assertNull(DirectSeal.open(bob.private, bobPublic, alicePublic, byteArrayOf(0x02) + sealed.drop(1), aliceToBob))
    }
}
