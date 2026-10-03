package com.getfirepit.core.data

import com.getfirepit.core.crypto.RoomCipher
import com.getfirepit.core.crypto.SealedText
import com.getfirepit.protocol.meshchat.MeshChatControl
import com.getfirepit.protocol.meshchat.RoomText
import com.getfirepit.protocol.meshchat.SealedMessage
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What rotating a room's key does and does not achieve.
 *
 * The point of a rotation is that a removed member reads nothing from then on,
 * while everyone still there keeps their history. Both halves matter: a
 * rotation that also blinded the people who stayed would delete the
 * conversation rather than protect it.
 */
class KeyRotationTest {

    private val roomId = 0x51DE51DE
    private val sender = -211096906

    private val hour = 491_234

    private val oldKey = RoomCipher.generateKey()
    private val newKey = RoomCipher.generateKey()

    private fun seal(text: String, key: ByteArray, generation: Int): ByteArray {
        val inner = MeshChatControl(room_text = RoomText(text = text)).encode()
        val sealed = SealedText.seal(key, hour, inner, SealedText.contextOf(roomId, sender))
        return MeshChatControl(
            sealed_message = SealedMessage(
                room_id = roomId,
                ciphertext = sealed.toByteString(),
                generation = generation,
            ),
        ).encode()
    }

    private fun open(payload: ByteArray, keys: Map<Int, ByteArray>): String? {
        val sealed = MeshChatControl.ADAPTER.decode(payload).sealed_message ?: return null
        val key = keys[sealed.generation] ?: return null
        val plain = SealedText.open(
            key,
            sealed.ciphertext.toByteArray(),
            SealedText.contextOf(sealed.room_id, sender),
        ) ?: return null
        return MeshChatControl.ADAPTER.decode(plain).room_text?.text
    }

    /**
     * Whoever stays holds both for a while after the move, so something sealed
     * just before it still opens when the mesh delivers it late. What they had
     * already read is kept opened on the phone, not under these keys.
     */
    private val staying = mapOf(1 to oldKey, 2 to newKey)

    /** Whoever was removed keeps the old key and is never sent the new one. */
    private val removed = mapOf(1 to oldKey)

    @Test
    fun `a removed member cannot read anything sent after the rotation`() {
        val after = seal("we have moved the meeting", newKey, generation = 2)

        assertNull(open(after, removed))
        assertEquals("we have moved the meeting", open(after, staying))
    }

    @Test
    fun `a message sealed just before the rotation still opens for those who stayed`() {
        val before = seal("meet at the north gate", oldKey, generation = 1)

        assertEquals("meet at the north gate", open(before, staying))
    }

    /**
     * A rotation protects the future only. Anything the removed member already
     * received, they keep, and no amount of key changing reaches back.
     */
    @Test
    fun `a removed member keeps what they already had`() {
        val before = seal("meet at the north gate", oldKey, generation = 1)

        assertEquals("meet at the north gate", open(before, removed))
    }

    @Test
    fun `a message says which generation sealed it`() {
        val payload = seal("anything", newKey, generation = 2)

        assertEquals(2, MeshChatControl.ADAPTER.decode(payload).sealed_message?.generation)
    }

    @Test
    fun `the new key is not derivable from the old one`() {
        val after = seal("after", newKey, generation = 2)

        assertNull("the old key must not open a new message", open(after, mapOf(2 to oldKey)))
    }
}
