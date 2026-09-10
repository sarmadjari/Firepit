package com.getfirepit.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomCipherTest {
    private val key = RoomCipher.generateKey()
    private val other = RoomCipher.generateKey()
    private val text = "meet at the north gate".toByteArray()
    private val room = "room-7".toByteArray()

    @Test
    fun `a member with the key reads the message back`() {
        val sealed = RoomCipher.seal(key, text, room)

        assertArrayEquals(text, RoomCipher.open(key, sealed, room))
    }

    @Test
    fun `someone holding only the channel key reads nothing`() {
        val sealed = RoomCipher.seal(key, text, room)

        assertNull(RoomCipher.open(other, sealed, room))
    }

    @Test
    fun `the words are not in the packet`() {
        val sealed = RoomCipher.seal(key, text, room)

        assertEquals(-1, String(sealed, Charsets.ISO_8859_1).indexOf("north gate"))
    }

    @Test
    fun `changing a byte in flight is refused, not delivered`() {
        val sealed = RoomCipher.seal(key, text, room)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()

        assertNull(RoomCipher.open(key, sealed, room))
    }

    @Test
    fun `a message cannot be replayed into another room`() {
        val sealed = RoomCipher.seal(key, text, room)

        assertNull(RoomCipher.open(key, sealed, "room-8".toByteArray()))
    }

    @Test
    fun `sealing the same words twice never repeats a nonce`() {
        val nonces = (1..200).map { RoomCipher.seal(key, text, room).take(RoomCipher.NONCE_SIZE) }

        assertEquals(nonces.size, nonces.toSet().size)
    }

    @Test
    fun `the same words seal differently every time`() {
        assertNotEquals(
            RoomCipher.seal(key, text, room).toList(),
            RoomCipher.seal(key, text, room).toList(),
        )
    }

    @Test
    fun `sealing costs exactly the advertised overhead`() {
        assertEquals(text.size + RoomCipher.OVERHEAD, RoomCipher.seal(key, text, room).size)
        assertEquals(28, RoomCipher.OVERHEAD)
    }

    @Test
    fun `truncated or empty input is refused rather than crashing`() {
        assertNull(RoomCipher.open(key, ByteArray(0), room))
        assertNull(RoomCipher.open(key, ByteArray(RoomCipher.NONCE_SIZE), room))
        assertNull(RoomCipher.open(key, ByteArray(RoomCipher.OVERHEAD - 1), room))
    }

    @Test
    fun `an empty message still seals and opens`() {
        val sealed = RoomCipher.seal(key, ByteArray(0), room)

        assertArrayEquals(ByteArray(0), RoomCipher.open(key, sealed, room))
    }
}
