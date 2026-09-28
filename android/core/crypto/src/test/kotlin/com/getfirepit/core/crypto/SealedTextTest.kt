package com.getfirepit.core.crypto

import com.getfirepit.core.protocol.MeshConstants
import com.getfirepit.core.protocol.MessagePrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SealedTextTest {
    private val key = RoomCipher.generateKey()
    private val other = RoomCipher.generateKey()
    private val context = SealedText.contextOf(roomId = 7, senderNodeNum = 42)

    /**
     * `:core:protocol` cannot see this module — the dependency runs the other
     * way — so it mirrors the figure and this is where the two are held
     * together. If they drift, the composer's byte count lies.
     */
    @Test
    fun `the budget the sender plans with matches what sealing actually costs`() {
        assertEquals(SealedText.OVERHEAD, MessagePrivacy.SEALED_OVERHEAD)

        val plaintext = ByteArray(SealedText.MAX_TEXT_BYTES)
        assertEquals(
            MeshConstants.MAX_TEXT_BYTES,
            SealedText.seal(key, plaintext, context).size,
        )
    }

    @Test
    fun `a member reads the message back`() {
        val sealed = SealedText.seal(key, "meet at the north gate".encodeToByteArray(), context)

        assertEquals("meet at the north gate", SealedText.open(key, sealed, context)?.decodeToString())
    }

    @Test
    fun `someone with only the channel key reads nothing`() {
        val sealed = SealedText.seal(key, "meet at the north gate".encodeToByteArray(), context)

        assertNull(SealedText.open(other, sealed, context))
    }

    @Test
    fun `a message cannot be re-attributed to another sender`() {
        val sealed = SealedText.seal(key, "on my way".encodeToByteArray(), context)

        assertNull(SealedText.open(key, sealed, SealedText.contextOf(7, 43)))
    }

    @Test
    fun `a message cannot be lifted into another room`() {
        val sealed = SealedText.seal(key, "on my way".encodeToByteArray(), context)

        assertNull(SealedText.open(key, sealed, SealedText.contextOf(8, 42)))
    }

    @Test
    fun `a version this build does not know is refused, not mis-read`() {
        val sealed = SealedText.seal(key, "hello".encodeToByteArray(), context)
        sealed[0] = 0x02

        assertNull(SealedText.open(key, sealed, context))
    }

    @Test
    fun `noise on the port is refused rather than crashing`() {
        assertNull(SealedText.open(key, ByteArray(0), context))
        assertNull(SealedText.open(key, byteArrayOf(0x01), context))
        assertNull(SealedText.open(key, ByteArray(4) { 0x01 }, context))
    }

    @Test
    fun `other alphabets survive the round trip`() {
        val arabic = "نلتقي عند البوابة"
        val sealed = SealedText.seal(key, arabic.encodeToByteArray(), context)

        assertEquals(arabic, SealedText.open(key, sealed, context)?.decodeToString())
    }

    @Test
    fun `a full length message still fits the payload`() {
        val text = "x".repeat(SealedText.MAX_TEXT_BYTES)

        val sealed = SealedText.seal(key, text.encodeToByteArray(), context)

        assertTrue("sealed to ${sealed.size} bytes", sealed.size <= MeshConstants.MAX_TEXT_BYTES)
        assertEquals(text, SealedText.open(key, sealed, context)?.decodeToString())
    }

    @Test
    fun `sealing costs twenty nine bytes of the budget`() {
        assertEquals(29, SealedText.OVERHEAD)
        assertEquals(171, SealedText.MAX_TEXT_BYTES)
    }

    @Test
    fun `the same words never seal the same way twice`() {
        assertNotEquals(
            SealedText.seal(key, "same".encodeToByteArray(), context).toList(),
            SealedText.seal(key, "same".encodeToByteArray(), context).toList(),
        )
    }
}
