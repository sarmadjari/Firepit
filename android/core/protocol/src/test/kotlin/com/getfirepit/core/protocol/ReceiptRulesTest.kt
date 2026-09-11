package com.getfirepit.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptRulesTest {

    @Test
    fun `a message that arrives is owed a delivery receipt`() {
        val pending = ReceiptRules.received(PendingReceipts(), 11)

        assertEquals(setOf(11), pending.delivered)
        assertTrue(pending.read.isEmpty())
    }

    @Test
    fun `opening a message replaces its delivery receipt rather than adding one`() {
        val arrived = ReceiptRules.received(PendingReceipts(), 11)

        val opened = ReceiptRules.opened(arrived, setOf(11))

        assertTrue("still owes a delivery receipt", opened.delivered.isEmpty())
        assertEquals(setOf(11), opened.read)
    }

    @Test
    fun `a message is never owed both receipts at once`() {
        var pending = ReceiptRules.received(PendingReceipts(), 11)
        pending = ReceiptRules.opened(pending, setOf(11))
        pending = ReceiptRules.received(pending, 11)

        assertTrue(pending.delivered.isEmpty())
        assertEquals(setOf(11), pending.read)
    }

    @Test
    fun `a message read without being noticed first is still owed a read receipt`() {
        val opened = ReceiptRules.opened(PendingReceipts(), setOf(11))

        assertEquals(setOf(11), opened.read)
    }

    @Test
    fun `a packet carries no more ids than it has room for`() {
        val pending = PendingReceipts(delivered = (1..100).toSet(), read = (200..300).toSet())

        assertEquals(ReceiptRules.MAX_IDS_PER_PACKET, ReceiptRules.batch(pending).size)
    }

    @Test
    fun `read receipts take the room first`() {
        val pending = PendingReceipts(delivered = (1..100).toSet(), read = (200..210).toSet())

        val batch = ReceiptRules.batch(pending)

        assertEquals(11, batch.read.size)
        assertEquals(ReceiptRules.MAX_IDS_PER_PACKET - 11, batch.delivered.size)
    }

    @Test
    fun `what was sent is no longer owed`() {
        val pending = PendingReceipts(delivered = setOf(1, 2, 3), read = setOf(9))
        val sent = ReceiptRules.batch(pending)

        assertTrue(ReceiptRules.remaining(pending, sent).isEmpty)
    }

    @Test
    fun `what did not fit is still owed`() {
        val pending = PendingReceipts(delivered = (1..100).toSet())
        val sent = ReceiptRules.batch(pending)

        assertEquals(60, ReceiptRules.remaining(pending, sent).delivered.size)
    }

    @Test
    fun `a lost receipt is repeated in the next packet`() {
        val lost = ReceiptRules.batch(PendingReceipts(read = setOf(11, 12)))

        val next = ReceiptRules.batch(PendingReceipts(read = setOf(13)), ReceiptRules.echoOf(lost))

        assertTrue("11 was not repeated", 11 in next.read)
        assertTrue("13 was dropped for the repeat", 13 in next.read)
    }

    @Test
    fun `only the most recent are repeated`() {
        val sent = PendingReceipts(read = (1..50).toSet())

        assertEquals(ReceiptRules.ECHO, ReceiptRules.echoOf(sent).read.size)
        assertTrue("kept the oldest instead of the newest", 50 in ReceiptRules.echoOf(sent).read)
    }

    @Test
    fun `repeating never pushes a packet over the limit`() {
        val pending = PendingReceipts(read = (1..100).toSet())
        val echo = PendingReceipts(read = (500..508).toSet())

        assertEquals(ReceiptRules.MAX_IDS_PER_PACKET, ReceiptRules.batch(pending, echo).size)
    }

    @Test
    fun `a repeat does not duplicate something already owed`() {
        val pending = PendingReceipts(read = setOf(11, 12))
        val echo = PendingReceipts(read = setOf(12))

        assertEquals(setOf(11, 12), ReceiptRules.batch(pending, echo).read)
    }

    @Test
    fun `a message repeated as read is not also repeated as delivered`() {
        val batch = ReceiptRules.batch(
            PendingReceipts(read = setOf(11)),
            PendingReceipts(delivered = setOf(11)),
        )

        assertEquals(setOf(11), batch.read)
        assertTrue(batch.delivered.isEmpty())
    }

    @Test
    fun `owing nothing means sending nothing`() {
        assertTrue(PendingReceipts().isEmpty)
        assertTrue(ReceiptRules.batch(PendingReceipts()).isEmpty)
        assertFalse(ReceiptRules.received(PendingReceipts(), 1).isEmpty)
    }
}
