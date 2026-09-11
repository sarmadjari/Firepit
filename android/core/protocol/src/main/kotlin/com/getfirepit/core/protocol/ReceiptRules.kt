package com.getfirepit.core.protocol

/**
 * What this phone owes the room.
 *
 * Sets keep insertion order, so the newest ids are at the end. That matters
 * only for [echoOf], which repeats the most recent ones.
 */
data class PendingReceipts(
    val delivered: Set<Int> = emptySet(),
    val read: Set<Int> = emptySet(),
) {
    val isEmpty: Boolean get() = delivered.isEmpty() && read.isEmpty()
    val size: Int get() = delivered.size + read.size
}

/**
 * When a receipt goes out and which ones fit.
 *
 * Receipts are held back rather than sent per message: one packet carries many,
 * and a member who replies carries theirs along for nothing.
 */
object ReceiptRules {

    /**
     * Ids per packet.
     *
     * Each costs 4 bytes of the 233-byte payload, less the 29 sealing takes and
     * the room it belongs to. Forty leaves headroom rather than sitting on the
     * limit, the same way roster entries are capped.
     */
    const val MAX_IDS_PER_PACKET = 40

    /**
     * Recently sent ids repeated in the next packet.
     *
     * A lost receipt then heals itself on the following batch instead of being
     * retransmitted, which would turn a quiet feature into a second flood.
     */
    const val ECHO = 8

    /** A message arrived and has not been opened. */
    fun received(pending: PendingReceipts, messageId: Int): PendingReceipts =
        if (messageId in pending.read) pending
        else pending.copy(delivered = pending.delivered + messageId)

    /**
     * Opening a message replaces its delivery receipt rather than adding one.
     *
     * Read already says it arrived, so sending both would be two packets making
     * a single point.
     */
    fun opened(pending: PendingReceipts, messageIds: Set<Int>): PendingReceipts =
        PendingReceipts(
            delivered = pending.delivered - messageIds,
            read = pending.read + messageIds,
        )

    /**
     * What goes in the next packet.
     *
     * Read receipts are placed first: they are the more useful of the two and
     * say everything a delivery receipt would.
     */
    fun batch(pending: PendingReceipts, echo: PendingReceipts = PendingReceipts()): PendingReceipts {
        val read = (pending.read + echo.read).take(MAX_IDS_PER_PACKET).toSet()
        val room = MAX_IDS_PER_PACKET - read.size
        val delivered = (pending.delivered + echo.delivered)
            .filterNot { it in read }
            .take(room)
            .toSet()
        return PendingReceipts(delivered = delivered, read = read)
    }

    /** What is still owed once [batch] has gone out. */
    fun remaining(pending: PendingReceipts, sent: PendingReceipts): PendingReceipts =
        PendingReceipts(
            delivered = pending.delivered - sent.delivered,
            read = pending.read - sent.read,
        )

    /** The tail of a sent batch, to be repeated once in the next one. */
    fun echoOf(sent: PendingReceipts): PendingReceipts =
        PendingReceipts(
            delivered = sent.delivered.toList().takeLast(ECHO).toSet(),
            read = sent.read.toList().takeLast(ECHO).toSet(),
        )
}
