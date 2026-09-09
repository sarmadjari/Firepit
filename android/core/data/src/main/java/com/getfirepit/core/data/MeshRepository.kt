package com.getfirepit.core.data

import android.util.Log
import com.getfirepit.core.database.MessageDao
import com.getfirepit.core.database.NodeDao
import com.getfirepit.core.database.find
import com.getfirepit.core.database.observeAll
import com.getfirepit.core.database.observeChannel
import com.getfirepit.core.database.save
import com.getfirepit.core.database.saveIfNew
import com.getfirepit.core.model.BROADCAST_NODE_NUM
import com.getfirepit.core.model.ChannelRole
import com.getfirepit.core.model.ChatMessage
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.model.MessageStatus
import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.protocol.MeshConstants
import com.getfirepit.core.protocol.MeshPacketBuilder
import com.getfirepit.core.protocol.MessageStatusRules
import com.getfirepit.core.protocol.OutboundPacer
import com.getfirepit.core.transport.LinkState
import com.getfirepit.core.transport.RadioLink
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.Routing
import org.meshtastic.proto.ToRadio

/**
 * Single source of truth for chat and node state.
 *
 * Owns the one-way flow from the radio into storage, and the outbound path from
 * the UI back out. Nothing above this layer touches the transport.
 */
@Singleton
class MeshRepository @Inject constructor(
    private val link: RadioLink,
    private val messageDao: MessageDao,
    private val nodeDao: NodeDao,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val pacer = OutboundPacer(System::currentTimeMillis)

    private val _myNodeNum = MutableStateFlow<Int?>(null)
    val myNodeNum: StateFlow<Int?> = _myNodeNum.asStateFlow()

    private val _channels = MutableStateFlow<List<RoomChannel>>(emptyList())
    val channels: StateFlow<List<RoomChannel>> = _channels.asStateFlow()

    val isConnected: StateFlow<Boolean> = _myNodeNum
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var hopLimit: Int = MeshConstants.DEFAULT_HOP_LIMIT

    fun observeChannel(channel: Int): Flow<List<ChatMessage>> = messageDao.observeChannel(channel)

    fun observeNodes(): Flow<List<MeshNode>> = nodeDao.observeAll()

    /** Starts the inbound pump. Safe to call once per process. */
    fun start() {
        scope.launch {
            link.state.collect { state ->
                if (state is LinkState.Ready) {
                    _myNodeNum.value = state.snapshot.myNodeNum
                    hopLimit = state.snapshot.lora?.hop_limit
                        ?.takeIf { it in 1..MeshConstants.MAX_HOP_LIMIT }
                        ?: MeshConstants.DEFAULT_HOP_LIMIT
                    _channels.value = state.snapshot.channels.values
                        .sortedBy { it.index }
                        .map { channel ->
                            RoomChannel(
                                index = channel.index,
                                name = channel.settings?.name.orEmpty().let(::sanitizeMeshText),
                                role = when (channel.role) {
                                    Channel.Role.PRIMARY -> ChannelRole.PRIMARY
                                    Channel.Role.SECONDARY -> ChannelRole.SECONDARY
                                    else -> ChannelRole.DISABLED
                                },
                                id = channel.settings?.id ?: 0,
                                positionPrecision = channel.settings?.module_settings?.position_precision ?: 0,
                            )
                        }
                    state.snapshot.nodes.values.forEach { saveNode(it) }
                }
            }
        }
        scope.launch {
            link.inbound.collect { message -> handle(message) }
        }
    }

    suspend fun sendText(channel: Int, text: String) {
        val myNodeNum = _myNodeNum.value ?: error("Not connected to a radio")
        val payload = text.encodeUtf8()
        require(payload.size <= MeshConstants.MAX_TEXT_BYTES) {
            "Message is ${payload.size} bytes, over the ${MeshConstants.MAX_TEXT_BYTES}-byte limit"
        }

        val packet = MeshPacketBuilder.meshPacket(
            to = BROADCAST_NODE_NUM,
            channel = channel,
            portNum = PortNum.TEXT_MESSAGE_APP,
            payload = payload,
            hopLimit = hopLimit,
            // Decision D-5: without this the firmware reports nothing back, so
            // there would be no "heard by the mesh" signal at all.
            wantAck = true,
        )

        messageDao.save(
            ChatMessage(
                id = packet.id,
                channel = channel,
                fromNodeNum = myNodeNum,
                toNodeNum = BROADCAST_NODE_NUM,
                text = text,
                sentAt = System.currentTimeMillis(),
                status = MessageStatus.QUEUED,
                isOutgoing = true,
            ),
            myNodeNum,
        )

        pacer.awaitSlot(PortNum.TEXT_MESSAGE_APP)
        runCatching { link.send(ToRadio(packet = packet)) }
            .onFailure { cause ->
                setStatus(packet.id, MessageStatus.FAILED, cause.message ?: "Send failed")
            }

        scheduleAckTimeout(packet.id)
    }

    private fun scheduleAckTimeout(packetId: Int) {
        scope.launch {
            delay(MessageStatusRules.ACK_TIMEOUT)
            val current = messageDao.find(packetId) ?: return@launch
            if (current.status == MessageStatus.SENT_TO_NODE) {
                setStatus(packetId, MessageStatus.UNKNOWN, null)
            }
        }
    }

    private suspend fun handle(message: FromRadio) {
        message.packet?.let { handlePacket(it) }
        message.queueStatus?.let { handleQueueStatus(it) }
        message.node_info?.let { saveNode(it) }
    }

    private suspend fun handlePacket(packet: MeshPacket) {
        val data = packet.decoded ?: return
        when (data.portnum) {
            PortNum.TEXT_MESSAGE_APP, PortNum.TEXT_MESSAGE_COMPRESSED_APP -> saveIncomingText(packet, data)
            PortNum.ROUTING_APP -> handleRouting(packet, data)
            else -> Unit
        }
    }

    private suspend fun saveIncomingText(packet: MeshPacket, data: Data) {
        val myNodeNum = _myNodeNum.value ?: return
        if (packet.from == myNodeNum) return

        val text = sanitizeMeshText(data.payload.utf8())
        if (text.isEmpty()) return

        val stored = messageDao.saveIfNew(
            ChatMessage(
                id = packet.id,
                channel = packet.channel,
                fromNodeNum = packet.from,
                toNodeNum = packet.to,
                text = text,
                sentAt = System.currentTimeMillis(),
                rxTime = packet.rx_time?.toLong()?.times(1_000),
                status = MessageStatus.REACHED_MESH,
                isOutgoing = false,
                rxSnr = packet.rx_snr.takeIf { it != 0f },
                rxRssi = packet.rx_rssi?.takeIf { it != 0 },
                hopsAway = (packet.hop_start - packet.hop_limit).takeIf { packet.hop_start > 0 },
                replyId = data.reply_id.takeIf { it != 0 },
                emoji = data.emoji.takeIf { it != 0 },
                signed = packet.xeddsa_signed,
            ),
            myNodeNum,
        )
        if (stored) Log.i(TAG, "text from ${packet.from} on channel ${packet.channel}")
    }

    private suspend fun handleRouting(packet: MeshPacket, data: Data) {
        val myNodeNum = _myNodeNum.value ?: return
        val originalId = data.request_id.takeIf { it != 0 } ?: return
        val routing = runCatching { Routing.ADAPTER.decode(data.payload) }.getOrNull() ?: return

        val next = MessageStatusRules.fromRouting(routing.error_reason, packet.from, myNodeNum)
        Log.i(TAG, "routing for $originalId: ${routing.error_reason} from ${packet.from} -> $next")
        setStatus(originalId, next, routing.error_reason?.takeIf { next.isFailure }?.name)
    }

    private suspend fun handleQueueStatus(status: QueueStatus) {
        val packetId = status.mesh_packet_id.takeIf { it != 0 } ?: return
        val next = MessageStatusRules.fromQueueStatus(status.res)
        setStatus(packetId, next, "QueueStatus res=${status.res}".takeIf { next.isFailure })
    }

    private suspend fun setStatus(packetId: Int, next: MessageStatus, reason: String?) {
        val current = messageDao.find(packetId) ?: return
        val advanced = MessageStatusRules.advance(current.status, next)
        if (advanced != current.status) {
            messageDao.updateStatus(packetId, advanced, reason)
        }
    }

    private suspend fun saveNode(info: NodeInfo) {
        val user = info.user
        nodeDao.save(
            MeshNode(
                nodeNum = info.num,
                userId = user?.id,
                longName = user?.long_name?.let(::sanitizeMeshText),
                shortName = user?.short_name?.let(::sanitizeMeshText),
                hwModel = user?.hw_model?.name,
                role = user?.role?.name,
                publicKey = user?.public_key?.takeIf { it.size > 0 }?.base64(),
                isUnmessagable = user?.is_unmessagable == true,
                lastHeard = info.last_heard.toLong().times(1_000).takeIf { info.last_heard != 0 },
                snr = info.snr.takeIf { it != 0f },
                hopsAway = info.hops_away,
                batteryLevel = info.device_metrics?.battery_level,
                voltage = info.device_metrics?.voltage,
                channelUtilization = info.device_metrics?.channel_utilization,
                airUtilTx = info.device_metrics?.air_util_tx,
                isFavorite = info.is_favorite,
            ),
            System.currentTimeMillis(),
        )
    }

    private companion object {
        const val TAG = "FirepitMesh"
    }
}
