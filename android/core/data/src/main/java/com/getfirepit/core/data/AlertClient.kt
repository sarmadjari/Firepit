package com.getfirepit.core.data

import android.util.Log
import com.getfirepit.core.model.MessageStatus
import com.getfirepit.core.protocol.MeshPacketBuilder
import com.getfirepit.core.protocol.MessageStatusRules
import com.getfirepit.core.protocol.OutboundPacer
import com.getfirepit.core.transport.RadioLink
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.encodeUtf8
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Routing
import org.meshtastic.proto.ToRadio

/** What the mesh said about a buzz. Silence is not success. */
sealed interface BuzzResult {
    /** The radio acknowledged it. Whether it made a sound is between it and its owner. */
    data object Delivered : BuzzResult

    /** Heard being rebroadcast, which says the mesh took it, not that it arrived. */
    data object ReachedMesh : BuzzResult

    /** It answered, and the answer was no. */
    data class Refused(val reason: Routing.Error) : BuzzResult

    data object NoAnswer : BuzzResult

    /**
     * Their node has never published a public key, so there is no way to reach
     * it without putting "this phone buzzed that node" on a shared channel.
     */
    data object NoKey : BuzzResult
}

/**
 * Makes one radio announce itself.
 *
 * Two identical boards on a desk are told apart by making one of them make a
 * noise, which is the one question a list of names cannot settle when the names
 * were chosen badly.
 *
 * Sent as a **text message**, not on `ALERT_APP`. Field-tested: an alert reaches
 * the node and a screen will draw it, but the External Notification module only
 * sounds its buzzer for an arriving text, so the port that looks right is the
 * one that stays silent. The bell character leads the payload so a radio with
 * `alert_bell` configured rings on that too.
 *
 * A board with no buzzer, or with the module switched off, does nothing at all,
 * and nothing comes back over the mesh to say which happened.
 */
@Singleton
class AlertClient @Inject constructor(
    private val link: RadioLink,
    private val mesh: MeshRepository,
) {

    private val pacer = OutboundPacer(System::currentTimeMillis)

    suspend fun buzz(nodeNum: Int, timeout: Duration = REPLY_TIMEOUT): BuzzResult {
        val myNodeNum = mesh.myNodeNum.value ?: error("Connect a radio first")

        // Our own radio is reached without touching the air at all; the firmware
        // hands a local packet to its modules just the same, and answers nothing.
        if (nodeNum == myNodeNum) {
            link.send(
                ToRadio(
                    packet = MeshPacketBuilder.localPacket(
                        myNodeNum = myNodeNum,
                        portNum = PortNum.TEXT_MESSAGE_APP,
                        payload = BELL,
                    ),
                ),
            )
            Log.i(TAG, "buzzed our own node")
            return BuzzResult.Delivered
        }

        // Encrypted to the node, like any other directed message: on a shared
        // channel this would announce who is buzzing whom to everyone holding
        // the key, and on a factory primary that is every radio in range.
        val publicKey = mesh.publicKeyOf(nodeNum) ?: run {
            Log.w(TAG, "no public key for $nodeNum; not buzzing over a shared channel")
            return BuzzResult.NoKey
        }

        pacer.awaitSlot(PortNum.TEXT_MESSAGE_APP)
        val packet = MeshPacketBuilder.meshPacket(
            to = nodeNum,
            channel = 0,
            portNum = PortNum.TEXT_MESSAGE_APP,
            payload = BELL,
            wantAck = true,
            pkiEncrypted = true,
            publicKey = publicKey,
        )

        val result = withTimeoutOrNull(timeout) {
            coroutineScope {
                // Listening starts before sending: the reply can arrive first.
                val reply = async {
                    link.inbound
                        .filter { from ->
                            val data = from.packet?.decoded
                            data?.portnum == PortNum.ROUTING_APP && data.request_id == packet.id
                        }
                        .first()
                }
                link.send(ToRadio(packet = packet))

                val answer = reply.await().packet ?: return@coroutineScope BuzzResult.NoAnswer
                val routing = answer.decoded?.payload
                    ?.let { runCatching { Routing.ADAPTER.decode(it) }.getOrNull() }
                when (MessageStatusRules.fromRouting(routing?.error_reason, answer.from, myNodeNum)) {
                    MessageStatus.DELIVERED -> BuzzResult.Delivered
                    MessageStatus.REACHED_MESH -> BuzzResult.ReachedMesh
                    else -> BuzzResult.Refused(routing?.error_reason ?: Routing.Error.NONE)
                }
            }
        } ?: BuzzResult.NoAnswer

        Log.i(TAG, "buzzed $nodeNum -> $result")
        return result
    }

    private companion object {
        const val TAG = "FirepitAlert"

        /**
         * Bell first, because that is the byte the firmware's notification
         * module rings on. The word is only for whoever is looking at a screen
         * when it lands, and is deliberately not the product name: this is the
         * one thing Firepit sends that cannot be sealed, so it must not be the
         * thing that announces Firepit to anyone holding the channel key.
         */
        val BELL = "\u0007Ping".encodeUtf8()

        /**
         * Far short of [MessageStatusRules.ACK_TIMEOUT]: this answers "which one
         * is this", and nobody holds a board waiting two minutes to find out.
         */
        val REPLY_TIMEOUT = 30.seconds
    }
}
