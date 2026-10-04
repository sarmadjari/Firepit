package com.getfirepit.core.data

import com.getfirepit.core.crypto.RoomCipher
import com.getfirepit.core.crypto.SealedText
import com.getfirepit.core.protocol.MeshConstants
import com.getfirepit.protocol.meshchat.MeshChatControl
import com.getfirepit.protocol.meshchat.RoomText
import com.getfirepit.protocol.meshchat.SealedMessage
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bytes a room message becomes, and what it takes to read one back. */
class SealedRoomTextTest {

    private val key = RoomCipher.generateKey()
    private val roomId = 0x0BADF00D
    private val sender = -1181562854

    private fun wire(text: String, replyId: Int = 0, under: ByteArray = key, emoji: Int = 0): ByteArray {
        val inner = MeshChatControl(room_text = RoomText(text = text, reply_id = replyId, emoji = emoji)).encode()
        val sealed = SealedText.seal(under, 491_234, inner, SealedText.contextOf(roomId, sender))
        return MeshChatControl(
            sealed_message = SealedMessage(room_id = roomId, ciphertext = sealed.toByteString()),
        ).encode()
    }

    private fun open(payload: ByteArray, under: ByteArray = key, from: Int = sender): RoomText? {
        val sealed = MeshChatControl.ADAPTER.decode(payload).sealed_message ?: return null
        val plain = SealedText.open(
            under,
            sealed.ciphertext.toByteArray(),
            SealedText.contextOf(sealed.room_id, from),
        ) ?: return null
        return MeshChatControl.ADAPTER.decode(plain).room_text
    }

    @Test
    fun `a member reads the words back`() {
        val out = open(wire("meet at the north gate", replyId = 77))

        assertEquals("meet at the north gate", out?.text)
        assertEquals(77, out?.reply_id)
    }

    @Test
    fun `the words are nowhere in the packet`() {
        val payload = wire("meet at the north gate")

        assertTrue(
            "plaintext leaked into the payload",
            !payload.toString(Charsets.ISO_8859_1).contains("north gate"),
        )
    }

    @Test
    fun `a radio holding only the channel key reads nothing`() {
        assertNull(open(wire("on my way"), under = RoomCipher.generateKey()))
    }

    @Test
    fun `a message cannot be re-attributed to another sender`() {
        assertNull(open(wire("on my way"), from = sender + 1))
    }

    /**
     * The composer caps the draft at this, so the longest thing anyone can type
     * has to survive sealing and still fit one packet.
     */
    @Test
    fun `the longest message the composer allows still fits`() {
        val text = "x".repeat(SealedText.MAX_TEXT_BYTES)
        val payload = wire(text)

        assertTrue(
            "sealed to ${payload.size} bytes, over ${MeshConstants.DATA_PAYLOAD_LEN}",
            payload.size <= MeshConstants.DATA_PAYLOAD_LEN,
        )
        assertEquals(text, open(payload)?.text)
    }

    @Test
    fun `the same words never look the same twice`() {
        assertTrue(!wire("same").contentEquals(wire("same")))
    }

    /** UX §5.4: a reaction is Meshtastic's emoji flag, carried inside the seal so relays cannot tell it from words. */
    @Test
    fun `a reaction keeps its flag and target inside the seal`() {
        val payload = wire("👍", replyId = 77, emoji = 1)
        val out = open(payload)

        assertEquals("👍", out?.text)
        assertEquals(77, out?.reply_id)
        assertEquals(1, out?.emoji)
        assertTrue(
            "the reaction leaked into the payload",
            !payload.toString(Charsets.ISO_8859_1).contains("👍".toByteArray().toString(Charsets.ISO_8859_1)),
        )
    }

    @Test
    fun `words carry no reaction flag, and no extra byte for it`() {
        assertEquals(0, open(wire("on my way"))?.emoji)
        assertEquals(
            MeshChatControl(room_text = RoomText(text = "on my way")).encode().size,
            MeshChatControl(room_text = RoomText(text = "on my way", emoji = 0)).encode().size,
        )
    }
}
