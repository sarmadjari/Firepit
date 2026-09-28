package com.getfirepit.core.data

import android.util.Log
import com.getfirepit.core.database.MessageDao
import com.getfirepit.core.database.NodeDao
import com.getfirepit.core.database.directLatest
import com.getfirepit.core.database.find
import com.getfirepit.core.database.latestPerChannel
import com.getfirepit.core.database.observeAll
import com.getfirepit.core.database.observeChannel
import com.getfirepit.core.database.observeDirect
import com.getfirepit.core.database.save
import com.getfirepit.core.database.saveIfNew
import com.getfirepit.core.model.BROADCAST_NODE_NUM
import com.getfirepit.core.model.ChannelRole
import com.getfirepit.core.model.ChatMessage
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.model.MessageStatus
import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.model.RoomKind
import com.getfirepit.core.protocol.ChannelKey
import com.getfirepit.core.protocol.ChannelLoad
import com.getfirepit.core.protocol.ChannelSlotManager
import com.getfirepit.core.crypto.SealedText
import com.getfirepit.protocol.meshchat.MeshChatControl
import com.getfirepit.protocol.meshchat.RoomText
import com.getfirepit.protocol.meshchat.SealedMessage
import com.getfirepit.core.protocol.Carriage
import com.getfirepit.core.protocol.MeshConstants
import com.getfirepit.core.protocol.MeshPacketBuilder
import com.getfirepit.core.protocol.MessagePrivacy
import com.getfirepit.core.protocol.MessageStatusRules
import com.getfirepit.core.protocol.OutboundPacer
import com.getfirepit.core.protocol.PositionPrecision
import com.getfirepit.core.protocol.RadioClock
import com.getfirepit.core.protocol.phoneapi.RadioSnapshot
import com.getfirepit.core.transport.LinkState
import com.getfirepit.core.transport.RadioLink
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.QueueStatus
import org.meshtastic.proto.Routing
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User

/** Why a message was not put on the air, in terms the composer can show. */
sealed class SendError(message: String) : Exception(message) {
    data object NotConnected : SendError("Connect your node first")

    /**
     * The firmware cannot encrypt to a node whose public key it has never seen,
     * and sending anyway would mean falling back to the channel key — which on
     * the primary is a key every Meshtastic radio holds.
     */
    data object NoPeerKey : SendError(
        "No secure channel to this person yet. Wait for their node to introduce " +
            "itself, or say hello in a room you share.",
    )

    /** Nothing to seal with, so there is no private way to say it. */
    data object NotARoom : SendError(
        "This channel isn't a conversation. Create a room, or add a Meshtastic " +
            "channel to talk to people outside Firepit.",
    )

    /** A channel with no key at all; Firepit has no reason to put words in the clear. */
    data object NotEncrypted : SendError(
        "This channel has no encryption at all, so anything sent on it is readable " +
            "by every radio in range.",
    )

    data class TooLong(val bytes: Int, val limit: Int) :
        SendError("Message is $bytes bytes, over the $limit-byte limit")
}

/**
 * Single source of truth for chat and node state.
 *
 * Owns the one-way flow from the radio into storage, and the outbound path from
 * the UI back out. Nothing above this layer touches the transport.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class MeshRepository @Inject constructor(
    private val link: RadioLink,
    private val messageDao: MessageDao,
    private val nodeDao: NodeDao,
    private val sessionStore: SessionStore,
    private val roomKeys: RoomKeyStore,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val pacer = OutboundPacer(System::currentTimeMillis)

    // Seeded from the last session so the map can identify us before, or
    // without, a radio connection.
    private val _myNodeNum = MutableStateFlow(sessionStore.myNodeNum)
    val myNodeNum: StateFlow<Int?> = _myNodeNum.asStateFlow()

    /** How far the radio's clock sits from the phone's, once a packet has shown us. */
    private val _clockSkewMillis = MutableStateFlow<Long?>(null)
    val clockSkewMillis: StateFlow<Long?> = _clockSkewMillis.asStateFlow()

    private val _channels = MutableStateFlow<List<RoomChannel>>(emptyList())
    val channels: StateFlow<List<RoomChannel>> = _channels.asStateFlow()

    private val _snapshot = MutableStateFlow<RadioSnapshot?>(null)
    val snapshot: StateFlow<RadioSnapshot?> = _snapshot.asStateFlow()

    /** Newly stored incoming messages. Replays nothing, so a late collector cannot re-notify. */
    private val _incomingMessages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 16)
    val incomingMessages: SharedFlow<ChatMessage> = _incomingMessages.asSharedFlow()

    /** Our own `User`, needed to introduce ourselves when joining a room. */
    val myUser: User?
        get() = _snapshot.value?.let { it.nodes[it.myNodeNum]?.user }

    /** Records our own fix from the phone's GPS. Local only; nothing is transmitted. */
    suspend fun setOwnPosition(nodeNum: Int, latitudeI: Int, longitudeI: Int, altitude: Int?, timeMillis: Long) {
        nodeDao.updatePosition(
            nodeNum = nodeNum,
            latitudeI = latitudeI,
            longitudeI = longitudeI,
            altitude = altitude,
            positionTime = timeMillis,
            positionPrecision = PositionPrecision.FULL,
            // Our own fix comes from the phone, which reports neither.
            groundSpeed = null,
            groundTrack = null,
        )
    }

    /**
     * Mirrors a rename onto our own node.
     *
     * The firmware acknowledges `set_owner` but does not send our own NodeInfo
     * back, so without this the app keeps showing the old name until the next
     * config download.
     */
    suspend fun setOwnName(nodeNum: Int, longName: String, shortName: String) {
        val node = nodeDao.find(nodeNum) ?: return
        nodeDao.save(node.copy(longName = longName, shortName = shortName), System.currentTimeMillis())
    }

    /** A node's public key, required before anything can be sent to it over PKI. */
    suspend fun publicKeyOf(nodeNum: Int): ByteString? =
        _snapshot.value?.nodes?.get(nodeNum)?.user?.public_key?.takeIf { it.size == PUBLIC_KEY_SIZE }
            ?: nodeDao.find(nodeNum)?.publicKey
                ?.let { runCatching { it.decodeBase64() }.getOrNull() }
                ?.takeIf { it.size == PUBLIC_KEY_SIZE }

    // Derived from the link, not from myNodeNum: that is remembered across
    // sessions so the map can identify us offline, and would otherwise report a
    // connection that does not exist.
    val isConnected: StateFlow<Boolean> = link.state
        .map { it is LinkState.Ready }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var hopLimit: Int = MeshConstants.DEFAULT_HOP_LIMIT

    /**
     * How busy our own radio finds the channel, or null before it says.
     *
     * Read from our own node rather than the mesh average: congestion is local,
     * and it is our antenna that has to find a gap to speak in.
     */
    val channelLoad: StateFlow<ChannelLoad?> = _myNodeNum
        .flatMapLatest { nodeNum ->
            if (nodeNum == null) flowOf(null) else nodeDao.observeAll()
                .map { nodes -> ChannelLoad.of(nodes.firstOrNull { it.nodeNum == nodeNum }?.channelUtilization) }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun observeChannel(channel: Int): Flow<List<ChatMessage>> = messageDao.observeChannel(channel)

    /** Newest message per channel, for the chat list previews. */
    fun observeLatestPerChannel(): Flow<List<ChatMessage>> = messageDao.latestPerChannel()

    /** One conversation with one person. */
    fun observeDirect(peer: Int): Flow<List<ChatMessage>> = messageDao.observeDirect(peer)

    /** Newest message per person, for the Direct list. */
    fun observeDirectLatest(): Flow<List<ChatMessage>> = messageDao.directLatest()

    fun observeNodes(): Flow<List<MeshNode>> = nodeDao.observeAll()

    /**
     * What kind of conversation a slot holds.
     *
     * Holding the Firepit key is the only thing that makes a room sealed, so it
     * is asked first and nothing else can stand in for it. Everything else is
     * an ordinary Meshtastic channel, described by how private its own key is.
     */
    private fun kindOf(roomId: Int, psk: ByteArray?): RoomKind = when {
        roomId != 0 && roomKeys.keyFor(roomId) != null -> RoomKind.FIREPIT
        else -> when (ChannelKey.of(psk)) {
            ChannelKey.PRIVATE -> RoomKind.MESHTASTIC_PRIVATE
            ChannelKey.DEFAULT -> RoomKind.MESHTASTIC_PUBLIC
            ChannelKey.NONE -> RoomKind.UNENCRYPTED
        }
    }

    private fun roomChannelOf(channel: Channel): RoomChannel {
        val id = channel.settings?.id ?: 0
        return RoomChannel(
            index = channel.index,
            name = channel.settings?.name.orEmpty().let(::sanitizeMeshText),
            role = when (channel.role) {
                Channel.Role.PRIMARY -> ChannelRole.PRIMARY
                Channel.Role.SECONDARY -> ChannelRole.SECONDARY
                else -> ChannelRole.DISABLED
            },
            id = id,
            positionPrecision = channel.settings?.module_settings?.position_precision ?: 0,
            kind = kindOf(id, channel.settings?.psk?.toByteArray()),
        )
    }

    /**
     * Folds a channel we just wrote into our own view of the radio.
     *
     * The firmware lists channels once, during the config download, and never
     * mentions them again. Without this a room stays invisible until the next
     * reconnect — it would not appear in the list, and nothing could be sent to
     * it, because the slot would not be known to carry a conversation.
     */
    internal fun applyChannelWrite(written: Channel) {
        _snapshot.value = _snapshot.value?.let { snapshot ->
            snapshot.copy(channels = snapshot.channels + (written.index to written))
        }
        _channels.value = (_channels.value.filterNot { it.index == written.index } + roomChannelOf(written))
            .sortedBy { it.index }
    }

    /** Starts the inbound pump. Safe to call once per process. */
    fun start() {
        scope.launch {
            link.state.collect { state ->
                // A different radio has a different clock; the old one's skew
                // would otherwise describe a device no longer attached.
                if (state !is LinkState.Ready) _clockSkewMillis.value = null
                if (state is LinkState.Ready) {
                    _snapshot.value = state.snapshot
                    _myNodeNum.value = state.snapshot.myNodeNum
                    sessionStore.myNodeNum = state.snapshot.myNodeNum
                    hopLimit = state.snapshot.lora?.hop_limit
                        ?.takeIf { it in 1..MeshConstants.MAX_HOP_LIMIT }
                        ?: MeshConstants.DEFAULT_HOP_LIMIT
                    _channels.value = state.snapshot.channels.values
                        .sortedBy { it.index }
                        .map(::roomChannelOf)
                    state.snapshot.nodes.values.forEach { saveNode(it) }
                }
            }
        }
        scope.launch {
            link.inbound.collect { message -> handle(message) }
        }
    }

    /**
     * Sends text to a room, or to one person when [to] names them.
     *
     * There is no unencrypted path out of here. A room's words are sealed under
     * a key the radio never holds; one person's words are encrypted to that
     * person's node key by the firmware. When neither is possible the message
     * is refused rather than quietly downgraded to the channel key — on the
     * primary that key is one every Meshtastic radio has, and a direct message
     * sent that way is readable by the whole mesh. That is precisely what
     * Meshtastic's own pre-2.5 direct messages did, and why they changed it.
     */
    suspend fun sendText(
        channel: Int,
        text: String,
        replyId: Int? = null,
        to: Int = BROADCAST_NODE_NUM,
    ) {
        val myNodeNum = _myNodeNum.value ?: throw SendError.NotConnected
        val payload = text.encodeUtf8()

        // A Firepit room only where we actually hold its key. Without one the
        // slot is an ordinary Meshtastic channel, and pretending otherwise
        // would seal words that the people on it cannot open.
        val roomId = roomIdForChannel(channel)
        val roomKey = roomId?.let { roomKeys.keyFor(it) }
        val peerKey = to.takeIf { it != BROADCAST_NODE_NUM }?.let { publicKeyOf(it) }

        val carriage = MessagePrivacy.carriageFor(
            to = to,
            channel = channel,
            isRoomSlot = isRoomSlot(channel),
            sealingRoomId = roomId.takeIf { roomKey != null },
            hasPeerKey = peerKey != null,
            channelKey = channelKeyOf(channel),
        )
        val limit = MessagePrivacy.textBudgetFor(carriage)
        if (carriage !is Carriage.Refused && payload.size > limit) {
            throw SendError.TooLong(payload.size, limit)
        }

        val packet = when (carriage) {
            is Carriage.Refused -> throw when (carriage.reason) {
                Carriage.Reason.NO_PEER_KEY -> SendError.NoPeerKey
                Carriage.Reason.NOT_A_ROOM -> SendError.NotARoom
                Carriage.Reason.NOT_ENCRYPTED -> SendError.NotEncrypted
            }

            is Carriage.ToOneNode -> MeshPacketBuilder.meshPacket(
                to = carriage.nodeNum,
                channel = channel,
                portNum = PortNum.TEXT_MESSAGE_APP,
                payload = payload,
                hopLimit = hopLimit,
                // Decision D-5: without this the firmware reports nothing back, so
                // there would be no "heard by the mesh" signal at all.
                wantAck = true,
                replyId = replyId,
                pkiEncrypted = true,
                publicKey = requireNotNull(peerKey),
            )

            // Plain text on a plain channel, because a stock Meshtastic client
            // cannot open our envelope and this conversation exists to reach one.
            is Carriage.OpenChannel -> MeshPacketBuilder.meshPacket(
                to = to,
                channel = carriage.channel,
                portNum = PortNum.TEXT_MESSAGE_APP,
                payload = payload,
                hopLimit = hopLimit,
                wantAck = true,
                replyId = replyId,
            )

            is Carriage.SealedRoom -> {
                val sealed = SealedText.seal(
                    requireNotNull(roomKey),
                    MeshChatControl(room_text = RoomText(text = text, reply_id = replyId ?: 0)).encode(),
                    SealedText.contextOf(carriage.roomId, myNodeNum),
                )
                MeshPacketBuilder.meshPacket(
                    to = to,
                    channel = carriage.channel,
                    portNum = PortNum.PRIVATE_APP,
                    payload = MeshChatControl(
                        sealed_message = SealedMessage(
                            room_id = carriage.roomId,
                            ciphertext = sealed.toByteString(),
                            generation = roomKeys.generationOf(carriage.roomId),
                        ),
                    ).encode().let(ByteString::of),
                    hopLimit = hopLimit,
                    wantAck = true,
                )
            }
        }

        messageDao.save(
            ChatMessage(
                id = packet.id,
                channel = channel,
                fromNodeNum = myNodeNum,
                toNodeNum = to,
                text = text,
                sentAt = System.currentTimeMillis(),
                status = MessageStatus.QUEUED,
                isOutgoing = true,
                replyId = replyId,
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

    /**
     * The radio hands a packet over as soon as it has it, so its stamp should
     * match the phone's clock. Whatever it is out by is what it will put on
     * every message it passes us.
     */
    private fun noteClockSkew(packet: MeshPacket) {
        val stamped = packet.rx_time?.takeIf { it != 0 } ?: return
        val skew = stamped.toLong() * 1_000 - System.currentTimeMillis()
        if (_clockSkewMillis.value == null) Log.i(TAG, "radio clock is ${skew / 1000}s from the phone")
        _clockSkewMillis.value = skew
    }

    /**
     * A stamp from our own radio, read in the phone's terms. See [RadioClock].
     */
    private fun Int.onPhoneClock(): Long? =
        RadioClock.onPhoneClock(this, _clockSkewMillis.value, System.currentTimeMillis())

    /**
     * A stamp another node put on its own fix, kept only while it could be
     * true. See [RadioClock].
     */
    private fun Int.ifPlausible(): Long? =
        RadioClock.ifPlausible(this, System.currentTimeMillis())

    private suspend fun handlePacket(packet: MeshPacket) {
        val data = packet.decoded ?: return
        noteClockSkew(packet)
        markHeard(packet)
        when (data.portnum) {
            PortNum.TEXT_MESSAGE_APP -> saveIncomingText(packet, data)

            // The firmware compresses outgoing text with Unishox2 on our behalf
            // and decompresses inbound before handing it over, so this port
            // should never arrive. If it does the bytes are not UTF-8, and
            // decoding them anyway would store mojibake as somebody's words.
            PortNum.TEXT_MESSAGE_COMPRESSED_APP ->
                Log.w(TAG, "dropped compressed text from ${packet.from}: firmware did not decompress it")

            PortNum.ROUTING_APP -> handleRouting(packet, data)
            PortNum.POSITION_APP -> handlePosition(packet, data)
            PortNum.TELEMETRY_APP -> handleTelemetry(packet, data)
            else -> Unit
        }
    }

    /**
     * Notes that a node was heard, from any packet at all.
     *
     * NodeInfo carries last_heard once at connection, so without this a node
     * transmitting right now still reads as last seen hours ago, or never.
     */
    private suspend fun markHeard(packet: MeshPacket) {
        if (packet.from == 0 || packet.from == _myNodeNum.value) return
        nodeDao.markHeard(
            nodeNum = packet.from,
            heardAt = System.currentTimeMillis(),
            snr = packet.rx_snr.takeIf { it != 0f },
            rssi = packet.rx_rssi?.takeIf { it != 0 },
            hopsAway = (packet.hop_start - packet.hop_limit).takeIf { packet.hop_start > 0 },
        )
    }

    /**
     * Records how busy the air is around a node.
     *
     * NodeInfo carries these figures once at connection and never again, so
     * without this the congestion warning would age into a lie within minutes.
     */
    private suspend fun handleTelemetry(packet: MeshPacket, data: Data) {
        val metrics = runCatching { Telemetry.ADAPTER.decode(data.payload) }.getOrNull()
            ?.device_metrics ?: return
        nodeDao.updateMetrics(
            nodeNum = packet.from,
            batteryLevel = metrics.battery_level,
            voltage = metrics.voltage,
            channelUtilization = metrics.channel_utilization,
            airUtilTx = metrics.air_util_tx,
        )
    }

    private suspend fun handlePosition(packet: MeshPacket, data: Data) {
        val position = runCatching { Position.ADAPTER.decode(data.payload) }.getOrNull() ?: return
        val latitude = position.latitude_i ?: return
        val longitude = position.longitude_i ?: return
        // 0,0 is in the Atlantic and is what a node with no fix reports.
        if (latitude == 0 && longitude == 0) return

        nodeDao.updatePosition(
            nodeNum = packet.from,
            latitudeI = latitude,
            longitudeI = longitude,
            altitude = position.altitude,
            // Their stamp while it holds up, otherwise the fact we can vouch
            // for: it reached us now.
            positionTime = position.time.ifPlausible() ?: System.currentTimeMillis(),
            positionPrecision = position.precision_bits.takeIf { it != 0 },
            groundSpeed = position.ground_speed,
            groundTrack = position.ground_track,
        )
    }

    private suspend fun saveIncomingText(packet: MeshPacket, data: Data) {
        // Firepit never converses on the primary, so anything broadcast there is
        // either another app's traffic or public mesh chatter. Storing it would
        // fill the database with a conversation nobody can reply to. A direct
        // message is different: PKI puts a channel hash of 0 on the wire, and it
        // is kept against the person rather than the slot.
        if (packet.channel == ChannelSlotManager.PRIMARY_SLOT && packet.to != _myNodeNum.value) {
            return
        }
        saveText(
            packet,
            sanitizeMeshText(data.payload.utf8()),
            replyId = data.reply_id.takeIf { it != 0 },
            emoji = data.emoji.takeIf { it != 0 },
        )
    }

    /**
     * Words that arrived sealed. Stored exactly like any other message: the
     * encryption is how it travelled, not what it is.
     */
    internal suspend fun saveSealedText(packet: MeshPacket, text: String, replyId: Int?) {
        saveText(packet, sanitizeMeshText(text), replyId, emoji = null)
    }

    private suspend fun saveText(packet: MeshPacket, text: String, replyId: Int?, emoji: Int?) {
        val myNodeNum = _myNodeNum.value ?: return
        if (packet.from == myNodeNum) return
        if (text.isEmpty()) return

        val message = ChatMessage(
            id = packet.id,
            channel = packet.channel,
            fromNodeNum = packet.from,
            toNodeNum = packet.to,
            text = text,
            sentAt = System.currentTimeMillis(),
            // Zero is a radio that has never been told the time, not 1970.
            rxTime = packet.rx_time?.onPhoneClock(),
            status = MessageStatus.RECEIVED,
            isOutgoing = false,
            rxSnr = packet.rx_snr.takeIf { it != 0f },
            rxRssi = packet.rx_rssi?.takeIf { it != 0 },
            hopsAway = (packet.hop_start - packet.hop_limit).takeIf { packet.hop_start > 0 },
            replyId = replyId,
            emoji = emoji,
            signed = packet.xeddsa_signed,
        )

        // Only announce genuinely new messages: the mesh repeats packets, and a
        // duplicate must not raise a second notification.
        if (messageDao.saveIfNew(message, myNodeNum)) {
            Log.i(TAG, "text from ${packet.from} on channel ${packet.channel}")
            _incomingMessages.tryEmit(message)
        }
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
        // Packet ids are random, so a routing reply could collide with a
        // received message. Only our own sends have a delivery status.
        if (!current.isOutgoing) return
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
                lastHeard = info.last_heard.onPhoneClock(),
                snr = info.snr.takeIf { it != 0f },
                hopsAway = info.hops_away,
                batteryLevel = info.device_metrics?.battery_level,
                voltage = info.device_metrics?.voltage,
                channelUtilization = info.device_metrics?.channel_utilization,
                airUtilTx = info.device_metrics?.air_util_tx,
                isFavorite = info.is_favorite,
                latitudeI = info.position?.latitude_i?.takeIf { it != 0 },
                longitudeI = info.position?.longitude_i?.takeIf { it != 0 },
                altitude = info.position?.altitude,
                // Nothing here says when this reached the radio, so an
                // unbelievable stamp leaves no time at all rather than a wrong one.
                positionTime = info.position?.time?.ifPlausible(),
                positionPrecision = info.position?.precision_bits?.takeIf { it != 0 },
            ),
            System.currentTimeMillis(),
        )
    }

    private companion object {
        const val TAG = "FirepitMesh"

        /** Curve25519 public key length; anything else cannot be a PKI key. */
        const val PUBLIC_KEY_SIZE = 32
    }
}
