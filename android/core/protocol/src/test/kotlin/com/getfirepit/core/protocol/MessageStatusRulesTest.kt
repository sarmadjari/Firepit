package com.getfirepit.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.getfirepit.core.model.MessageStatus
import org.meshtastic.proto.Routing

class MessageStatusRulesTest {

    private val myNodeNum = 0x1234
    private val peerNodeNum = 0x5678

    @Test
    fun `queue status zero means the radio accepted it`() {
        assertEquals(MessageStatus.SENT_TO_NODE, MessageStatusRules.fromQueueStatus(res = 0))
    }

    @Test
    fun `queue status non-zero means the radio refused it`() {
        assertEquals(MessageStatus.FAILED, MessageStatusRules.fromQueueStatus(res = 1))
    }

    @Test
    fun `ack from our own node is an implicit ack, never a delivery`() {
        val status = MessageStatusRules.fromRouting(Routing.Error.NONE, ackFrom = myNodeNum, myNodeNum)

        assertEquals(
            "hearing our own packet rebroadcast proves it entered the mesh, nothing more",
            MessageStatus.REACHED_MESH,
            status,
        )
    }

    @Test
    fun `ack from the peer is a real delivery`() {
        val status = MessageStatusRules.fromRouting(Routing.Error.NONE, ackFrom = peerNodeNum, myNodeNum)

        assertEquals(MessageStatus.DELIVERED, status)
    }

    @Test
    fun `max retransmit means nobody heard it`() {
        val status = MessageStatusRules.fromRouting(Routing.Error.MAX_RETRANSMIT, myNodeNum, myNodeNum)

        assertEquals(MessageStatus.UNHEARD, status)
    }

    @Test
    fun `every other routing error is a failure`() {
        val handledSeparately = setOf(Routing.Error.NONE, Routing.Error.MAX_RETRANSMIT)

        val errors = listOf(
            Routing.Error.NO_ROUTE,
            Routing.Error.GOT_NAK,
            Routing.Error.TIMEOUT,
            Routing.Error.NO_INTERFACE,
            Routing.Error.NO_CHANNEL,
            Routing.Error.TOO_LARGE,
            Routing.Error.NO_RESPONSE,
            Routing.Error.DUTY_CYCLE_LIMIT,
            Routing.Error.BAD_REQUEST,
            Routing.Error.NOT_AUTHORIZED,
            Routing.Error.PKI_FAILED,
            Routing.Error.PKI_UNKNOWN_PUBKEY,
            Routing.Error.ADMIN_BAD_SESSION_KEY,
            Routing.Error.ADMIN_PUBLIC_KEY_UNAUTHORIZED,
            Routing.Error.RATE_LIMIT_EXCEEDED,
            Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY,
        ).filterNot { it in handledSeparately }

        errors.forEach { error ->
            assertEquals(
                "$error should surface as a failure",
                MessageStatus.FAILED,
                MessageStatusRules.fromRouting(error, peerNodeNum, myNodeNum),
            )
        }
    }

    @Test
    fun `a missing error reason is treated as success`() {
        // Wire leaves the field null when the firmware omits it, which the
        // firmware does for a plain ACK.
        val status = MessageStatusRules.fromRouting(errorReason = null, ackFrom = myNodeNum, myNodeNum)

        assertEquals(MessageStatus.REACHED_MESH, status)
    }

    @Test
    fun `status only ever moves forward`() {
        val delivered = MessageStatus.DELIVERED

        assertEquals(delivered, MessageStatusRules.advance(delivered, MessageStatus.REACHED_MESH))
        assertEquals(delivered, MessageStatusRules.advance(delivered, MessageStatus.SENT_TO_NODE))
        assertEquals(delivered, MessageStatusRules.advance(delivered, MessageStatus.UNHEARD))
    }

    @Test
    fun `normal progression advances`() {
        var status = MessageStatus.QUEUED
        status = MessageStatusRules.advance(status, MessageStatus.SENT_TO_NODE)
        status = MessageStatusRules.advance(status, MessageStatus.REACHED_MESH)
        status = MessageStatusRules.advance(status, MessageStatus.DELIVERED)

        assertEquals(MessageStatus.DELIVERED, status)
    }

    @Test
    fun `a timeout cannot mask a result that already arrived`() {
        val reached = MessageStatus.REACHED_MESH

        assertEquals(reached, MessageStatusRules.advance(reached, MessageStatus.UNKNOWN))
    }

    @Test
    fun `unknown is only reachable from sent to node`() {
        assertEquals(
            MessageStatus.UNKNOWN,
            MessageStatusRules.advance(MessageStatus.SENT_TO_NODE, MessageStatus.UNKNOWN),
        )
    }

    @Test
    fun `failure states are flagged for the ui`() {
        assertTrue(MessageStatus.FAILED.isFailure)
        assertTrue(MessageStatus.UNHEARD.isFailure)
        assertTrue(!MessageStatus.REACHED_MESH.isFailure)
        assertTrue(!MessageStatus.DELIVERED.isFailure)
    }
}
