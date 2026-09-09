package com.getfirepit.core.protocol

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.meshtastic.proto.PortNum

/**
 * Spaces packets sent from the phone to satisfy the firmware's PhoneAPI rate
 * limits.
 *
 * This has to live in the app, not be discovered from responses: when the
 * radio drops a rate-limited position, waypoint, alert or telemetry packet it
 * **still returns a normal `QueueStatus`**, so a violation is invisible. Text
 * is the exception — it NAKs with `RATE_LIMIT_EXCEEDED`.
 *
 * Limits are per portnum, matching `PhoneAPI::handleToRadioPacket`.
 */
class OutboundPacer(private val nowMillis: () -> Long) {

    private val mutex = Mutex()
    private val lastSentAt = mutableMapOf<Int, Long>()

    /** Suspends until the next packet on [portNum] may be written. */
    suspend fun awaitSlot(portNum: PortNum) {
        val minimumGap = minimumGapFor(portNum) ?: return

        val waitFor = mutex.withLock {
            val now = nowMillis()
            val previous = lastSentAt[portNum.value]
            val readyAt = previous?.plus(minimumGap.inWholeMilliseconds) ?: now
            // Reserve the slot while holding the lock so concurrent senders
            // queue up behind each other instead of all waiting for the same
            // instant and then firing together.
            val sendAt = maxOf(now, readyAt)
            lastSentAt[portNum.value] = sendAt
            sendAt - now
        }

        if (waitFor > 0) delay(waitFor)
    }

    private fun minimumGapFor(portNum: PortNum): Duration? = when (portNum) {
        PortNum.TEXT_MESSAGE_APP -> TEXT_GAP
        PortNum.POSITION_APP,
        PortNum.WAYPOINT_APP,
        PortNum.ALERT_APP,
        PortNum.TELEMETRY_APP,
        -> BROADCASTABLE_GAP

        PortNum.TRACEROUTE_APP -> TRACEROUTE_GAP
        else -> null
    }

    private companion object {
        val TEXT_GAP = 2.seconds
        val BROADCASTABLE_GAP = 10.seconds
        val TRACEROUTE_GAP = 30.seconds
    }
}
