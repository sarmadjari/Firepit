package com.getfirepit.core.data

import android.util.Log
import com.getfirepit.core.protocol.MeshPacketBuilder
import com.getfirepit.core.protocol.OutboundPacer
import com.getfirepit.core.protocol.TraceRouteResult
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
import okio.ByteString
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.RouteDiscovery
import org.meshtastic.proto.ToRadio

/**
 * Asks the mesh which way it reaches a node.
 *
 * The only honest answer to "did my message get through" that the protocol can
 * give. Delivery receipts do not exist on a flood network, but the path does,
 * and it is measured on demand rather than added to every message.
 */
@Singleton
class TracerouteClient @Inject constructor(
    private val link: RadioLink,
    private val mesh: MeshRepository,
    private val rooms: RoomRepository,
) {

    private val pacer = OutboundPacer(System::currentTimeMillis)

    /**
     * Traces the route to [nodeNum], or returns null if nothing answers.
     *
     * Asked on a room's channel, never the primary: a route names every node
     * that carried it, and on a key this many people hold that is a map of who
     * is checking on whom, readable by all of them.
     *
     * The firmware allows one traceroute every 30 s and silently drops the
     * rest, so the pacer holds the caller rather than letting it believe a
     * dropped probe timed out.
     */
    suspend fun trace(nodeNum: Int, timeout: Duration = REPLY_TIMEOUT): TraceRouteResult? {
        if (mesh.myNodeNum.value == null) error("Connect your node first")
        val room = rooms.sharedRoomWith(nodeNum) ?: run {
            Log.i(TAG, "no room shared with $nodeNum; not tracing where others could read it")
            return null
        }

        pacer.awaitSlot(PortNum.TRACEROUTE_APP)

        val packet = MeshPacketBuilder.meshPacket(
            to = nodeNum,
            channel = room.index,
            portNum = PortNum.TRACEROUTE_APP,
            payload = RouteDiscovery().encode().let(ByteString::of),
            wantResponse = true,
        )

        return withTimeoutOrNull(timeout) {
            coroutineScope {
                // Listening starts before sending: the reply can arrive first.
                val reply = async {
                    link.inbound
                        .filter { from ->
                            val data = from.packet?.decoded
                            data?.portnum == PortNum.TRACEROUTE_APP && from.packet?.from == nodeNum
                        }
                        .first()
                }
                link.send(ToRadio(packet = packet))

                val data = reply.await().packet?.decoded ?: return@coroutineScope null
                val discovery = runCatching { RouteDiscovery.ADAPTER.decode(data.payload) }.getOrNull()
                    ?: return@coroutineScope null

                TraceRouteResult.from(
                    target = nodeNum,
                    route = discovery.route,
                    snrTowards = discovery.snr_towards,
                    routeBack = discovery.route_back,
                    snrBack = discovery.snr_back,
                )
            }
        }.also { if (it == null) Log.i(TAG, "no traceroute reply from $nodeNum") }
    }

    private companion object {
        const val TAG = "FirepitTrace"

        /** Generous: a reply crosses the mesh twice, once each way. */
        val REPLY_TIMEOUT = 60.seconds
    }
}
