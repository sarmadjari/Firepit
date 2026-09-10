package com.getfirepit.core.crypto

import com.getfirepit.core.protocol.MeshConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SealedTextTest {
    private val key = RoomCipher.generateKey()
    private val other = RoomCipher.generateKey()
    private val context = SealedText.contextOf(roomId = 7, senderNodeNum = 42)

    @Test
    fun `a member reads the message back`() {
        val sealed = SealedText.seal(key, "meet at the north gate", context)

        assertEquals("meet at the north gate", SealedText.open(key, sealed, context))
    }

    @Test
    fun `someone with only the channel key reads nothing`() {
        val sealed = SealedText.seal(key, "meet at the north gate", context)

        assertNull(SealedText.open(other, sealed, context))
    }

    @Test
    fun `a message cannot be re-attributed to another sender`() {
        val sealed = SealedText.seal(key, "on my way", context)

        assertNull(SealedText.open(key, sealed, SealedText.contextOf(7, 43)))
    }

    @Test
    fun `a message cannot be lifted into another room`() {
        val sealed = SealedText.seal(key, "on my way", context)

        assertNull(SealedText.open(key, sealed, SealedText.contextOf(8, 42)))
    }

    @Test
    fun `a version this build does not know is refused, not mis-read`() {
        val sealed = SealedText.seal(key, "hello", context)
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
        val sealed = SealedText.seal(key, arabic, context)

        assertEquals(arabic, SealedText.open(key, sealed, context))
    }

    @Test
    fun `a full length message still fits the payload`() {
        val text = "x".repeat(SealedText.MAX_TEXT_BYTES)

        val sealed = SealedText.seal(key, text, context)

        assertTrue("sealed to ${sealed.size} bytes", sealed.size <= MeshConstants.MAX_TEXT_BYTES)
        assertEquals(text, SealedText.open(key, sealed, context))
    }

    @Test
    fun `sealing costs twenty nine bytes of the budget`() {
        assertEquals(29, SealedText.OVERHEAD)
        assertEquals(171, SealedText.MAX_TEXT_BYTES)
    }

    @Test
    fun `the same words never seal the same way twice`() {
        assertNotEquals(
            SealedText.seal(key, "same", context).toList(),
            SealedText.seal(key, "same", context).toList(),
        )
    }
}
