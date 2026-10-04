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
    private val room = DirectSeal.RoomSecret(
        roomId = 0x0BADF00D,
        generation = 3,
        hour = 491_234,
        key = ByteArray(32) { it.toByte() },
    )

    @Test
    fun `the phone it was sealed for opens it`() {
        val sealed = DirectSeal.seal(alice.private, alicePublic, bobPublic, words, aliceToBob)

        assertEquals(words.size + DirectSeal.OVERHEAD, sealed.size)
        assertArrayEquals(words, openPlain(bob.private, bobPublic, alicePublic, sealed, aliceToBob))
    }

    @Test
    fun `the sender can read back what it sealed`() {
        val sealed = DirectSeal.seal(alice.private, alicePublic, bobPublic, words, aliceToBob)

        assertArrayEquals(words, openPlain(alice.private, alicePublic, bobPublic, sealed, aliceToBob))
    }

    @Test
    fun `any third phone cannot`() {
        val mallory = KeyEnvelope.generateKeyPair()
        val malloryPublic = KeyEnvelope.publicBytes(mallory.public)
        val sealed = DirectSeal.seal(alice.private, alicePublic, bobPublic, words, aliceToBob)

        assertNull(openPlain(mallory.private, malloryPublic, alicePublic, sealed, aliceToBob))
        assertNull(openPlain(mallory.private, bobPublic, alicePublic, sealed, aliceToBob))
    }

    @Test
    fun `nobody but the sender's phone can seal as them`() {
        // Mallory knows both public keys, which are no secret, and seals to Bob
        // claiming to be Alice.
        val mallory = KeyEnvelope.generateKeyPair()
        val forged = DirectSeal.seal(mallory.private, KeyEnvelope.publicBytes(mallory.public), bobPublic, words, aliceToBob)

        assertNull(openPlain(bob.private, bobPublic, alicePublic, forged, aliceToBob))
    }

    @Test
    fun `a message cannot be turned round or re-addressed`() {
        val sealed = DirectSeal.seal(alice.private, alicePublic, bobPublic, words, aliceToBob)

        listOf(
            DirectSeal.contextOf(senderNodeNum = 22, recipientNodeNum = 11),
            DirectSeal.contextOf(senderNodeNum = 11, recipientNodeNum = 23),
            DirectSeal.contextOf(senderNodeNum = 12, recipientNodeNum = 22),
        ).forEach { elsewhere ->
            assertNull(openPlain(bob.private, bobPublic, alicePublic, sealed, elsewhere))
        }
    }

    @Test
    fun `a changed byte anywhere is refused`() {
        val sealed = DirectSeal.seal(alice.private, alicePublic, bobPublic, words, aliceToBob)

        sealed.indices.forEach { index ->
            val tampered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertNull("byte $index", openPlain(bob.private, bobPublic, alicePublic, tampered, aliceToBob))
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

        assertNull(openPlain(bob.private, bobPublic, notAPoint, sealed, aliceToBob))
    }

    @Test
    fun `a truncated or unversioned payload is refused`() {
        val sealed = DirectSeal.seal(alice.private, alicePublic, bobPublic, words, aliceToBob)

        assertNull(openPlain(bob.private, bobPublic, alicePublic, sealed.copyOf(DirectSeal.OVERHEAD), aliceToBob))
        assertNull(openPlain(bob.private, bobPublic, alicePublic, byteArrayOf(0x03) + sealed.drop(1), aliceToBob))
    }

    @Test
    fun `direct room part is the HMAC the protocol describes`() {
        assertEquals(
            "8e350523e13aac797e216c73f3e101593033477d1c191a00da5ebbf37745edc3",
            DirectSeal.roomPart(room).hex(),
        )
    }

    @Test
    fun `v2 uses the room part in the phone key`() {
        val expected = v2Key(alice.private, alicePublic, bobPublic, aliceToBob, room)
        val nonce = byteArrayOf(
            (RoomRatchet.tagOf(room.hour) ushr 8).toByte(),
            RoomRatchet.tagOf(room.hour).toByte(),
        ) + ByteArray(10) { (it + 1).toByte() }
        val sealed = byteArrayOf(0x02) + RoomCipher.seal(expected, words, aliceToBob, nonce)

        val opened = DirectSeal.open(bob.private, bobPublic, alicePublic, sealed, aliceToBob, listOf(room))

        assertArrayEquals(words, opened?.plain)
        assertEquals(room.roomId, opened?.room?.roomId)
        assertEquals(room.hour, opened?.hour)
        assertArrayEquals(nonce, opened?.nonce)
    }

    @Test
    fun `v2 fails with the wrong room key`() {
        val sealed = DirectSeal.seal(alice.private, alicePublic, bobPublic, words, aliceToBob, room)
        val wrong = room.copy(key = room.key.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })

        assertNull(DirectSeal.open(bob.private, bobPublic, alicePublic, sealed, aliceToBob, listOf(wrong)))
    }

    @Test
    fun `v2 a changed byte anywhere is refused`() {
        val sealed = DirectSeal.seal(alice.private, alicePublic, bobPublic, words, aliceToBob, room)

        sealed.indices.forEach { index ->
            val tampered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertNull("byte $index", DirectSeal.open(bob.private, bobPublic, alicePublic, tampered, aliceToBob, listOf(room)))
        }
    }

    private fun openPlain(
        privateKey: java.security.PrivateKey,
        publicKey: ByteArray,
        peerPublic: ByteArray,
        sealed: ByteArray,
        context: ByteArray,
    ): ByteArray? = DirectSeal.open(privateKey, publicKey, peerPublic, sealed, context)?.plain

    private fun v2Key(
        privateKey: java.security.PrivateKey,
        ownPublic: ByteArray,
        peerPublic: ByteArray,
        context: ByteArray,
        room: DirectSeal.RoomSecret,
    ): ByteArray {
        val shared = KeyEnvelope.agree(privateKey, requireNotNull(KeyEnvelope.decode(peerPublic)))
        val salt = ordered(ownPublic, peerPublic)
        val prk = KeyEnvelope.hmac(salt, shared + DirectSeal.roomPart(room))
        return KeyEnvelope.hmac(prk, "firepit-direct-v2".toByteArray() + context + byteArrayOf(1))
    }

    private fun ordered(a: ByteArray, b: ByteArray): ByteArray {
        for (index in 0 until minOf(a.size, b.size)) {
            val left = a[index].toInt() and 0xFF
            val right = b[index].toInt() and 0xFF
            if (left != right) return if (left < right) a + b else b + a
        }
        return if (a.size <= b.size) a + b else b + a
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
