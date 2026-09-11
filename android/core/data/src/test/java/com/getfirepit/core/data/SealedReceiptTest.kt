package com.getfirepit.core.data

import com.getfirepit.core.crypto.RoomCipher
import com.getfirepit.core.crypto.SealedText
import com.getfirepit.core.protocol.MeshConstants
import com.getfirepit.core.protocol.PendingReceipts
import com.getfirepit.core.protocol.ReceiptRules
import com.getfirepit.protocol.meshchat.MeshChatControl
import com.getfirepit.protocol.meshchat.Receipt
import com.getfirepit.protocol.meshchat.SealedMessage
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The exact bytes a receipt travels as, sealed and back again. */
class SealedReceiptTest {

    private val key = ByteArray(RoomCipher.KEY_SIZE) { it.toByte() }
    private val roomId = 0x1234_5678
    private val sender = -1181562854

    private fun wire(receipt: Receipt): ByteArray {
        val inner = MeshChatControl(receipt = receipt).encode()
        val sealed = RoomCipher.seal(key, inner, SealedText.contextOf(roomId, sender))
        return MeshChatControl(
            sealed_message = SealedMessage(room_id = roomId, ciphertext = sealed.toByteString()),
        ).encode()
    }

    private fun unwire(payload: ByteArray): Receipt? {
        val outer = MeshChatControl.ADAPTER.decode(payload)
        val sealed = outer.sealed_message ?: return null
        val plain = RoomCipher.open(
            key,
            sealed.ciphertext.toByteArray(),
            SealedText.contextOf(sealed.room_id, sender),
        ) ?: return null
        return MeshChatControl.ADAPTER.decode(plain).receipt
    }

    @Test
    fun `a receipt survives sealing and opening`() {
        val receipt = Receipt(room_id = roomId, delivered = listOf(1, 2, 3), read = listOf(4, 5))
        val out = unwire(wire(receipt))
        assertEquals(listOf(1, 2, 3), out?.delivered)
        assertEquals(listOf(4, 5), out?.read)
    }

    @Test
    fun `the wrong key opens nothing`() {
        val payload = wire(Receipt(room_id = roomId, read = listOf(7)))
        val outer = MeshChatControl.ADAPTER.decode(payload)
        val opened = RoomCipher.open(
            ByteArray(RoomCipher.KEY_SIZE),
            outer.sealed_message!!.ciphertext.toByteArray(),
            SealedText.contextOf(roomId, sender),
        )
        assertEquals(null, opened)
    }

    @Test
    fun `a receipt naming another room will not open`() {
        val payload = wire(Receipt(room_id = roomId, read = listOf(7)))
        val outer = MeshChatControl.ADAPTER.decode(payload)
        val opened = RoomCipher.open(
            key,
            outer.sealed_message!!.ciphertext.toByteArray(),
            SealedText.contextOf(roomId + 1, sender),
        )
        assertEquals(null, opened)
    }

    /**
     * The cap has to hold once sealed and wrapped, not just as bare ids: the
     * room id, the version byte, the nonce and the tag all ride along.
     */
    @Test
    fun `a full batch still fits one packet`() {
        val full = ReceiptRules.batch(
            PendingReceipts(
                delivered = (1..ReceiptRules.MAX_IDS_PER_PACKET).map { it * 7919 }.toSet(),
                read = emptySet(),
            ),
        )
        val receipt = Receipt(room_id = roomId, delivered = full.delivered.toList())
        val payload = wire(receipt)
        assertTrue(
            "sealed receipt is ${payload.size} bytes, over ${MeshConstants.DATA_PAYLOAD_LEN}",
            payload.size <= MeshConstants.DATA_PAYLOAD_LEN,
        )
        assertNotNull(unwire(payload))
    }

    @Test
    fun `a full batch of read receipts also fits`() {
        val ids = (1..ReceiptRules.MAX_IDS_PER_PACKET).map { it * -7919 }
        val payload = wire(Receipt(room_id = roomId, read = ids))
        assertTrue(
            "sealed receipt is ${payload.size} bytes, over ${MeshConstants.DATA_PAYLOAD_LEN}",
            payload.size <= MeshConstants.DATA_PAYLOAD_LEN,
        )
        assertEquals(ids, unwire(payload)?.read)
    }
}
