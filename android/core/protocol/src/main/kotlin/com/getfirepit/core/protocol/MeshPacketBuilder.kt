package com.getfirepit.core.protocol

import kotlin.random.Random
import okio.ByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum

/**
 * Builds outgoing packets so the hop-limit trap cannot be hit.
 *
 * `MeshService::handleToRadio` does not default `hop_limit`, and `sendLocal`
 * fills it only when `want_ack` is set. A phone-built packet with `hop_limit`
 * left at 0 and no `want_ack` is transmitted and then never rebroadcast, which
 * looks exactly like a range problem.
 */
object MeshPacketBuilder {

    /**
     * A packet destined for the mesh. [hopLimit] cannot be 0 here — use
     * [localPacket] for anything addressed to our own node.
     */
    fun meshPacket(
        to: Int,
        channel: Int,
        portNum: PortNum,
        payload: ByteString,
        hopLimit: Int = MeshConstants.DEFAULT_HOP_LIMIT,
        wantAck: Boolean = false,
        wantResponse: Boolean = false,
        priority: MeshPacket.Priority = MeshPacket.Priority.UNSET,
        pkiEncrypted: Boolean = false,
        publicKey: ByteString = ByteString.EMPTY,
        replyId: Int? = null,
        emoji: Int? = null,
        id: Int = randomPacketId(),
    ): MeshPacket {
        require(hopLimit in 1..MeshConstants.MAX_HOP_LIMIT) {
            "hop_limit must be 1..${MeshConstants.MAX_HOP_LIMIT}; 0 is transmitted but never rebroadcast"
        }
        require(channel in 0..7) { "channel index must be 0..7, was $channel" }
        require(payload.size <= MeshConstants.DATA_PAYLOAD_LEN) {
            "payload is ${payload.size} bytes, over the ${MeshConstants.DATA_PAYLOAD_LEN}-byte limit"
        }

        return MeshPacket(
            to = to,
            // PKI packets carry a channel hash of 0 on the wire.
            channel = if (pkiEncrypted) 0 else channel,
            id = id,
            hop_limit = hopLimit,
            want_ack = wantAck,
            priority = priority,
            pki_encrypted = pkiEncrypted,
            public_key = publicKey,
            decoded = Data(
                portnum = portNum,
                payload = payload,
                want_response = wantResponse,
                reply_id = replyId ?: 0,
                emoji = emoji ?: 0,
            ),
        )
    }

    /**
     * A packet for our own node that never reaches the air: local admin, and
     * feeding the phone's GPS fix to a radio that has no fix of its own.
     */
    fun localPacket(
        myNodeNum: Int,
        portNum: PortNum,
        payload: ByteString,
        wantResponse: Boolean = false,
        priority: MeshPacket.Priority = MeshPacket.Priority.UNSET,
        id: Int = randomPacketId(),
    ): MeshPacket = MeshPacket(
        to = myNodeNum,
        channel = 0,
        id = id,
        hop_limit = 0,
        want_ack = false,
        priority = priority,
        decoded = Data(
            portnum = portNum,
            payload = payload,
            want_response = wantResponse,
        ),
    )

    /** Non-zero so the packet can be correlated with its ACK. */
    fun randomPacketId(): Int = Random.nextInt().let { if (it == 0) 1 else it }
}
