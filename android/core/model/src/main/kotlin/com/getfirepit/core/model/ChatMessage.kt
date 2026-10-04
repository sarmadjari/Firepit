package com.getfirepit.core.model

/** Broadcast destination; the same value the firmware uses for "everyone". */
const val BROADCAST_NODE_NUM: Int = -1

/**
 * One chat message, incoming or outgoing.
 *
 * @param id the mesh packet id. Non-zero and used to correlate acknowledgements,
 * so it doubles as the primary key.
 * @param channel slot index 0-7 for room traffic. Direct messages are PKI
 * encrypted and always report channel 0, so [isDirect] decides which it is.
 * @param rxTime the radio's clock, which may be absent on nodes without an RTC.
 * @param roomId which room a room message belongs to, or 0. A slot is only a
 * place on one radio: another radio can carry another room there, so history
 * follows the room rather than the number.
 */
data class ChatMessage(
    val id: Int,
    val channel: Int,
    val fromNodeNum: Int,
    val toNodeNum: Int,
    val text: String,
    val sentAt: Long,
    val rxTime: Long? = null,
    val status: MessageStatus = MessageStatus.QUEUED,
    val failureReason: String? = null,
    val isOutgoing: Boolean = false,
    val rxSnr: Float? = null,
    val rxRssi: Int? = null,
    val hopsAway: Int? = null,
    val replyId: Int? = null,
    val emoji: Int? = null,
    /** True only when a 2.8 node signed the broadcast and it verified. */
    val signed: Boolean = false,
    val roomId: Int = 0,
) {
    val isDirect: Boolean get() = toNodeNum != BROADCAST_NODE_NUM
    val isNotice: Boolean get() = fromNodeNum == NOTICE_NODE_NUM

    /** The other person in a direct conversation, matching the database's peerNodeNum. */
    fun peerNode(myNodeNum: Int?): Int =
        if (isOutgoing) {
            toNodeNum
        } else {
            fromNodeNum.takeIf { it != myNodeNum && it != NOTICE_NODE_NUM } ?: toNodeNum
        }
}

/** No real node has zero, so it marks a local notice line. */
const val NOTICE_NODE_NUM: Int = 0
