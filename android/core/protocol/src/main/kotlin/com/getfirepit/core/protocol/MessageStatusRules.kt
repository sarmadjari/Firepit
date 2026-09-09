package com.getfirepit.core.protocol

import com.getfirepit.core.model.MessageStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.meshtastic.proto.Routing

object MessageStatusRules {

    /** After this, stop expecting a routing packet and admit we do not know. */
    val ACK_TIMEOUT: Duration = 120.seconds

    /**
     * `QueueStatus.res` is the radio's accept/reject for a packet we wrote.
     *
     * It is **not** a rate-limit signal: a position, waypoint, alert or
     * telemetry packet dropped for exceeding the phone-API rate limit still
     * reports success here. [OutboundPacer] exists because of that.
     */
    fun fromQueueStatus(res: Int): MessageStatus =
        if (res == 0) MessageStatus.SENT_TO_NODE else MessageStatus.FAILED

    /**
     * A `ROUTING_APP` packet whose `request_id` matches a message we sent.
     *
     * @param ackFrom the `from` field of the routing packet. The firmware sends
     * an implicit ACK from **our own** node number when it overhears our packet
     * being rebroadcast; a real acknowledgement carries the peer's number.
     */
    fun fromRouting(errorReason: Routing.Error?, ackFrom: Int, myNodeNum: Int): MessageStatus =
        when (errorReason ?: Routing.Error.NONE) {
            Routing.Error.NONE ->
                if (ackFrom == myNodeNum) MessageStatus.REACHED_MESH else MessageStatus.DELIVERED

            Routing.Error.MAX_RETRANSMIT -> MessageStatus.UNHEARD

            else -> MessageStatus.FAILED
        }

    /**
     * Packets arrive out of order, so status only ever moves forward. Without
     * this a late implicit ACK could downgrade a confirmed delivery.
     */
    fun advance(current: MessageStatus, next: MessageStatus): MessageStatus =
        if (next.ordinal > current.ordinal) next else current
}
