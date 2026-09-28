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
 * How a receipt may travel, or why it may not.
 *
 * A receipt says what this phone has been reading. On a shared Meshtastic
 * channel there is nobody who could read one — no other client understands the
 * format — and putting it on the air anyway would announce which nodes run
 * Firepit and what they have opened. So it is sealed under a room key, or
 * encrypted to one node, or not sent.
 */
sealed interface ReceiptCarriage {
    /** Sealed under the room's own key, on the room's channel. */
    data class SealedRoom(val roomId: Int, val channel: Int) : ReceiptCarriage

    /** Encrypted by the firmware to the one person the message came from. */
    data class ToOneNode(val nodeNum: Int) : ReceiptCarriage

    /** No private way to say it, so nothing is sent and nothing is tracked. */
    data object None : ReceiptCarriage
}

/**
 * When a receipt goes out and which ones fit.
 *
 * Receipts are held back rather than sent per message: one packet carries many,
 * and a member who replies carries theirs along for nothing.
 */
object ReceiptRules {

    /**
     * How a receipt for this conversation would travel.
     *
     * [roomId] is the Firepit room the channel carries **and** whose key we
     * hold — null for a standard Meshtastic channel, which gets no receipts at
     * all. [peer] is set when the message was a direct one.
     */
    fun carriageFor(channel: Int, roomId: Int?, peer: Int?, hasPeerKey: Boolean): ReceiptCarriage =
        when {
            roomId != null -> ReceiptCarriage.SealedRoom(roomId, channel)
            peer != null && hasPeerKey -> ReceiptCarriage.ToOneNode(peer)
            else -> ReceiptCarriage.None
        }

    /** True when this conversation is worth collecting receipts for at all. */
    fun tracks(roomId: Int?, peer: Int?): Boolean = roomId != null || peer != null

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
