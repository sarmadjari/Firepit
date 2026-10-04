package com.getfirepit.core.data

import android.util.Log
import com.getfirepit.core.database.MessageDao
import com.getfirepit.core.database.NodeDao
import com.getfirepit.core.database.PeerKeyDao
import com.getfirepit.core.database.RoomActivityDao
import com.getfirepit.core.database.RoomMemberDao
import com.getfirepit.core.database.directLatest
import com.getfirepit.core.database.find
import com.getfirepit.core.database.latestPerChannel
import com.getfirepit.core.database.observeAll
import com.getfirepit.core.database.observeChannel
import com.getfirepit.core.database.observeDirect
import com.getfirepit.core.database.recordActivity
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
import com.getfirepit.core.crypto.DirectSeal
import com.getfirepit.core.crypto.InviteCodec
import com.getfirepit.core.crypto.KeyEnvelope
import com.getfirepit.core.crypto.RoomCrypto
import com.getfirepit.protocol.meshchat.MeshChatControl
import com.getfirepit.protocol.meshchat.RoomText
import com.getfirepit.protocol.meshchat.SealedDirect
import com.getfirepit.core.protocol.Carriage
import com.getfirepit.core.protocol.MeshConstants
import com.getfirepit.core.protocol.MeshPacketBuilder
import com.getfirepit.core.protocol.MessagePrivacy
import com.getfirepit.core.protocol.MessageStatusRules
import com.getfirepit.core.protocol.OutboundPacer
import com.getfirepit.core.protocol.PositionPrecision
import com.getfirepit.core.protocol.RadioClock
import com.getfirepit.core.protocol.TrustRules
import com.getfirepit.core.protocol.phoneapi.RadioSnapshot
import com.getfirepit.core.transport.LinkState
import com.getfirepit.core.transport.RadioLink
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    /** A Firepit room still on the radio whose key this phone does not hold. */
    data object RoomKeyMissing : SendError(
        "This room's key isn't on this phone, so nothing sent here could be sealed. Leave the " +
            "room, or ask a member to invite you again.",
    )

    /** The room moved to a key that never reached us; the old one only reaches whoever was removed. */
    data object RoomMovedOn : SendError(
        "This room moved to a new key that didn't reach this phone. Ask a member to invite you again.",
    )

    data class TooLong(val bytes: Int, val limit: Int) :
        SendError("Message is $bytes bytes, over the $limit-byte limit")
}

/** What a radio said about one of our packets: the id it answers, who said it, and how it went. */
data class RoutingEvent(val requestId: Int, val from: Int, val error: Routing.Error)

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
    private val peerKeyDao: PeerKeyDao,
    private val memberDao: RoomMemberDao,
    private val phoneKeys: PhoneKeyStore,
    private val roomActivity: RoomActivityDao,
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

    /** Every routing answer the radio passes up, for whoever is waiting on one. */
    private val routing = MutableSharedFlow<RoutingEvent>(extraBufferCapacity = 64)

    /**
     * Sends [packet] and waits for [from] itself to acknowledge it.
     *
     * Our radio accepting a packet says nothing about where it went, and an
     * implicit ack only says a neighbour repeated it. Only the recipient's own
     * answer shows it arrived. Our radio refusing it — no key for them, nobody
     * heard it — ends the wait at once rather than running out the clock.
     */
    suspend fun sendAwaitingAck(packet: MeshPacket, from: Int, timeout: Duration): Boolean = coroutineScope {
        val myNodeNum = _myNodeNum.value
        // Listening starts before sending: an answer can arrive first.
        val answer = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(timeout) {
                routing.first { event ->
                    event.requestId == packet.id &&
                        (event.from == from || (event.from == myNodeNum && event.error != Routing.Error.NONE))
                }
            }
        }
        val sent = runCatching { link.send(ToRadio(packet = packet)) }
            .onFailure { cause -> Log.w(TAG, "could not send ${packet.id} to $from", cause) }
            .isSuccess
        if (!sent) {
            answer.cancel()
            return@coroutineScope false
        }
        val event = answer.await()
        event != null && event.from == from && event.error == Routing.Error.NONE
    }

    /** Whether words to [peer] can be sealed to their phone, as that changes. */
    fun observeDirectSealed(peer: Int): Flow<Boolean> = peerKeyDao.observeKnown(peer)

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

    /**
     * A person's phone key, which is what makes a direct message private from
     * the radios as well as the mesh. Null for anyone who has never shared a
     * room with us, which includes everybody not running Firepit.
     */
    internal suspend fun phoneKeyOf(nodeNum: Int): ByteArray? =
        peerKeyDao.find(nodeNum)?.phoneKey
            ?.let { runCatching { it.decodeBase64() }.getOrNull() }
            ?.toByteArray()
            ?.takeIf(KeyEnvelope::isValidPublicKey)

    /**
     * Which conversation a slot holds, for filing its history: the room id when
     * it has one, so history follows the room between radios, else 0.
     */
    private fun conversationIdOf(channel: Int): Int = roomIdForChannel(channel) ?: 0

    /** A node's public key, required before anything can be sent to it over PKI. */
    suspend fun publicKeyOf(nodeNum: Int): ByteString? =
        _snapshot.value?.nodes?.get(nodeNum)?.user?.public_key?.takeIf { it.size == PUBLIC_KEY_SIZE }
            ?: nodeDao.find(nodeNum)?.publicKey
                ?.let { runCatching { it.decodeBase64() }.getOrNull() }
                ?.takeIf { it.size == PUBLIC_KEY_SIZE }

    /** The key our own radio holds for [nodeNum], as it reported at connection, or null. */
    internal fun radioKeyOf(nodeNum: Int): ByteString? =
        _snapshot.value?.nodes?.get(nodeNum)?.user?.public_key?.takeIf { it.size == PUBLIC_KEY_SIZE }

    /**
     * [nodeNum] as a contact the radio can take back: its own NodeInfo while it
     * still holds one, else what this phone kept. The radio's node list is
     * bounded and evicts; this phone's is not, so a member the radio forgot can
     * be put back instead of silently becoming unreachable.
     */
    internal suspend fun contactFor(nodeNum: Int): User? {
        _snapshot.value?.nodes?.get(nodeNum)?.user?.takeIf { it.public_key.size == PUBLIC_KEY_SIZE }?.let { return it }
        val node = nodeDao.find(nodeNum) ?: return null
        val key = node.publicKey
            ?.let { runCatching { it.decodeBase64() }.getOrNull() }
            ?.takeIf { it.size == PUBLIC_KEY_SIZE }
            ?: return null
        return User(
            id = MeshConstants.formatNodeId(nodeNum),
            long_name = node.longName.orEmpty(),
            short_name = node.shortName.orEmpty(),
            public_key = key,
        )
    }

    // Derived from the link, not from myNodeNum: that is remembered across
    // sessions so the map can identify us offline, and would otherwise report a
    // connection that does not exist.
    val isConnected: StateFlow<Boolean> = link.state
        .map { it is LinkState.Ready }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var hopLimit: Int = MeshConstants.DEFAULT_HOP_LIMIT

    /** How far our own packets travel: the radio's own setting, within the firmware's bounds. */
    internal fun hopLimitForSending(): Int = hopLimit

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
    private fun kindOf(roomId: Int, psk: ByteArray?, secondary: Boolean): RoomKind = when {
        roomId != 0 && roomKeys.holds(roomId) ->
            if (roomKeys.isSuperseded(roomId)) RoomKind.FIREPIT_MOVED_ON else RoomKind.FIREPIT

        // Shaped like one of ours — an id and a full-length key, which Firepit
        // gives every room and never gives a Meshtastic channel — but with no
        // key on this phone. Calling it an ordinary channel would invite words
        // onto it under a key every member's radio holds.
        secondary && roomId != 0 && psk?.size == RoomCrypto.PSK_SIZE -> RoomKind.FIREPIT_KEY_MISSING

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
            kind = kindOf(id, channel.settings?.psk?.toByteArray(), secondary = channel.role == Channel.Role.SECONDARY),
        )
    }

    /**
     * Re-reads what each slot is, after something changed that the radio does
     * not know about: a room moving on without us, or a key arriving.
     */
    internal fun refreshRoomKinds() {
        val channels = _snapshot.value?.channels?.values ?: return
        _channels.value = channels.sortedBy { it.index }.map(::roomChannelOf)
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
     * a key the radio never holds; one person's words are sealed to their
     * phone's key when we know it, and always encrypted to their radio's key by
     * the firmware as well. When neither is possible the message is refused
     * rather than quietly downgraded to the channel key — on the primary that
     * key is one every Meshtastic radio has, and a direct message sent that way
     * is readable by the whole mesh. That is precisely what Meshtastic's own
     * pre-2.5 direct messages did, and why they changed it.
     */
    suspend fun sendText(
        channel: Int,
        text: String,
        replyId: Int? = null,
        to: Int = BROADCAST_NODE_NUM,
        /** One of the six reactions to [replyId] (UX §5.4): Meshtastic's `emoji`, sealed inside where the words are. */
        reaction: Boolean = false,
    ) {
        val emoji = if (reaction) REACTION_EMOJI else 0
        val myNodeNum = _myNodeNum.value ?: throw SendError.NotConnected
        val payload = text.encodeUtf8()

        // A Firepit room only where we actually hold its current key. Without
        // one the slot is either an ordinary Meshtastic channel, or one of ours
        // this phone can no longer seal for — which is refused, not downgraded.
        val roomId = roomIdForChannel(channel)
        val canSeal = roomId?.let { roomKeys.canSeal(it) } == true
        val kind = _channels.value.firstOrNull { it.index == channel }?.kind
        val peerKey = to.takeIf { it != BROADCAST_NODE_NUM }?.let { publicKeyOf(it) }
        val peerPhoneKey = to.takeIf { it != BROADCAST_NODE_NUM }?.let { phoneKeyOf(it) }

        val carriage = MessagePrivacy.carriageFor(
            to = to,
            channel = channel,
            isRoomSlot = isRoomSlot(channel),
            sealingRoomId = roomId.takeIf { canSeal },
            hasPeerKey = peerKey != null,
            channelKey = channelKeyOf(channel),
            hasPeerPhoneKey = peerPhoneKey != null,
            roomKind = kind?.takeIf { it.isStalledRoom },
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
                Carriage.Reason.ROOM_KEY_MISSING -> SendError.RoomKeyMissing
                Carriage.Reason.ROOM_MOVED_ON -> SendError.RoomMovedOn
            }

            // Sealed to their phone, then to their radio by the firmware. The
            // radios carry it without being able to open it.
            is Carriage.SealedDirect -> {
                val sealed = phoneKeys.sealDirect(
                    requireNotNull(peerPhoneKey),
                    MeshChatControl(
                        version = InviteCodec.VERSION,
                        room_text = RoomText(text = text, reply_id = replyId ?: 0, emoji = emoji),
                    ).encode(),
                    DirectSeal.contextOf(myNodeNum, carriage.nodeNum),
                )
                MeshPacketBuilder.meshPacket(
                    to = carriage.nodeNum,
                    channel = channel,
                    portNum = PortNum.PRIVATE_APP,
                    payload = MeshChatControl(
                        version = InviteCodec.VERSION,
                        sealed_direct = SealedDirect(ciphertext = sealed.toByteString()),
                    ).encode().let(ByteString::of),
                    hopLimit = hopLimit,
                    // Decision D-5: the recipient's own ack is the only proof it arrived.
                    wantAck = true,
                    pkiEncrypted = true,
                    publicKey = requireNotNull(peerKey),
                )
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
                emoji = emoji.takeIf { it != 0 },
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
                emoji = emoji.takeIf { it != 0 },
            )

            is Carriage.SealedRoom -> {
                val sealed = roomKeys.seal(
                    carriage.roomId,
                    myNodeNum,
                    MeshChatControl(room_text = RoomText(text = text, reply_id = replyId ?: 0, emoji = emoji)).encode(),
                ) ?: throw SendError.RoomKeyMissing
                MeshPacketBuilder.meshPacket(
                    to = to,
                    channel = carriage.channel,
                    portNum = PortNum.PRIVATE_APP,
                    payload = MeshChatControl(sealed_message = sealed).encode().let(ByteString::of),
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
                emoji = emoji.takeIf { it != 0 },
                roomId = if (to == BROADCAST_NODE_NUM) conversationIdOf(channel) else 0,
            ),
            myNodeNum,
        )
        // Speaking in a room is what keeps it from counting as quiet.
        if (carriage is Carriage.SealedRoom) roomActivity.recordActivity(carriage.roomId, System.currentTimeMillis())

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

    /**
     * A position the firmware broadcast in the open.
     *
     * Kept for nodes outside our rooms, where it is all there is. Never for a
     * member: their phones only ever send positions sealed, so an unsealed one
     * naming a member was put on the air by whoever holds a radio.
     */
    private suspend fun handlePosition(packet: MeshPacket, data: Data) {
        if (!TrustRules.unsealedPositionAcceptable(senderInOurRooms = memberDao.isInAnyRoom(packet.from))) {
            Log.w(TAG, "dropped an unsealed position for member ${packet.from}")
            return
        }
        val position = runCatching { Position.ADAPTER.decode(data.payload) }.getOrNull() ?: return
        storePosition(packet.from, position, precision = position.precision_bits.takeIf { it != 0 })
    }

    /**
     * A member's position, sealed by their phone under the room's key and
     * already opened and checked by the room layer.
     */
    internal suspend fun storeSealedPosition(nodeNum: Int, position: Position) {
        storePosition(nodeNum, position, precision = PositionPrecision.FULL)
    }

    private suspend fun storePosition(nodeNum: Int, position: Position, precision: Int?) {
        val latitude = position.latitude_i ?: return
        val longitude = position.longitude_i ?: return
        // 0,0 is in the Atlantic and is what a node with no fix reports.
        if (latitude == 0 && longitude == 0) return

        nodeDao.updatePosition(
            nodeNum = nodeNum,
            latitudeI = latitude,
            longitudeI = longitude,
            altitude = position.altitude,
            // Their stamp while it holds up, otherwise the fact we can vouch
            // for: it reached us now.
            positionTime = position.time.ifPlausible() ?: System.currentTimeMillis(),
            positionPrecision = precision,
            groundSpeed = position.ground_speed,
            groundTrack = position.ground_track,
        )
    }

    private suspend fun saveIncomingText(packet: MeshPacket, data: Data) {
        // Firepit never converses on the primary, so anything broadcast there is
        // either another app's traffic or public mesh chatter. Storing it would
        // fill the database with a conversation nobody can reply to. A direct
        // message is kept against the person rather than the slot, but only
        // when the firmware decrypted it with their key: under the channel key
        // it could have been sent by anyone holding that key, under any name.
        // And a Firepit room's words only ever arrive sealed; anything unsealed
        // on its slot was typed by whoever holds a member's radio.
        val acceptable = TrustRules.plainTextAcceptable(
            direct = packet.to == _myNodeNum.value,
            pkiEncrypted = packet.pki_encrypted,
            onPrimary = packet.channel == ChannelSlotManager.PRIMARY_SLOT,
            onFirepitRoom = roomIdForChannel(packet.channel)?.let { roomKeys.holds(it) } == true,
        )
        if (!acceptable) {
            Log.w(TAG, "dropped unsealed text from ${packet.from} on channel ${packet.channel}")
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
     * Words that arrived sealed in [roomId]. Stored like any other message: the
     * encryption is how it travelled, and the database is encrypted in its turn.
     */
    internal suspend fun saveSealedText(packet: MeshPacket, text: String, replyId: Int?, roomId: Int, emoji: Int? = null) {
        saveText(packet, sanitizeMeshText(text), replyId, emoji = emoji, roomId = roomId)
    }

    /** One person's words, sealed by their phone to ours and already opened. */
    internal suspend fun saveSealedDirectText(packet: MeshPacket, text: String, replyId: Int?, emoji: Int? = null) {
        saveText(packet, sanitizeMeshText(text), replyId, emoji = emoji, roomId = 0)
    }

    private suspend fun saveText(
        packet: MeshPacket,
        text: String,
        replyId: Int?,
        emoji: Int?,
        roomId: Int = if (packet.to == _myNodeNum.value) 0 else conversationIdOf(packet.channel),
    ) {
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
            roomId = roomId,
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
        val decoded = runCatching { Routing.ADAPTER.decode(data.payload) }.getOrNull() ?: return
        val error = decoded.error_reason ?: Routing.Error.NONE
        routing.tryEmit(RoutingEvent(originalId, packet.from, error))

        val sent = messageDao.find(originalId)?.takeIf { it.isOutgoing } ?: return
        val next = MessageStatusRules.fromRouting(error, packet.from, myNodeNum, sentTo = sent.toNodeNum) ?: run {
            // Packet ids are in every header; anybody can answer one.
            Log.w(TAG, "routing for $originalId from ${packet.from}, who it was not sent to; ignored")
            return
        }
        Log.i(TAG, "routing for $originalId: $error from ${packet.from} -> $next")
        setStatus(originalId, next, error.takeIf { next.isFailure }?.name)
    }

    private suspend fun handleQueueStatus(status: QueueStatus) {
        val packetId = status.mesh_packet_id.takeIf { it != 0 } ?: return
        val next = MessageStatusRules.fromQueueStatus(status.res)
        setStatus(packetId, next, "QueueStatus res=${status.res}".takeIf { next.isFailure })
    }

    /**
     * The person a sealed message went to says their phone could not open it.
     * Set outright rather than advanced: their radio's acknowledgement may
     * already have marked it delivered, which it was — to a radio, not a
     * reader. False when [messageId] is not ours to [by].
     */
    internal suspend fun markNotOpened(messageId: Int, by: Int): Boolean {
        val message = messageDao.find(messageId)?.takeIf { it.isOutgoing && it.toNodeNum == by } ?: return false
        messageDao.updateStatus(
            message.id,
            MessageStatus.FAILED,
            "Their phone could not open it: it has not learned your key yet. Try again once you've " +
                "both been connected in a room you share.",
        )
        return true
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
        // The radio learned this from an unsealed broadcast, if at all; a
        // member's position is only believed sealed, from their phone.
        val position = info.position.takeIf { TrustRules.unsealedPositionAcceptable(memberDao.isInAnyRoom(info.num)) }
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
                latitudeI = position?.latitude_i?.takeIf { it != 0 },
                longitudeI = position?.longitude_i?.takeIf { it != 0 },
                altitude = position?.altitude,
                // Nothing here says when this reached the radio, so an
                // unbelievable stamp leaves no time at all rather than a wrong one.
                positionTime = position?.time?.ifPlausible(),
                positionPrecision = position?.precision_bits?.takeIf { it != 0 },
            ),
            System.currentTimeMillis(),
        )
    }

    private companion object {
        /** Meshtastic's value for "this text is a reaction" in `Data.emoji`, which other apps set too. */
        const val REACTION_EMOJI = 1

        const val TAG = "FirepitMesh"

        /** Curve25519 public key length; anything else cannot be a PKI key. */
        const val PUBLIC_KEY_SIZE = 32
    }
}
