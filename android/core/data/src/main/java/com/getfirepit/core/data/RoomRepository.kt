package com.getfirepit.core.data

import android.util.Log
import com.getfirepit.core.crypto.KeyEnvelope
import com.getfirepit.core.crypto.RoomCipher
import com.getfirepit.core.crypto.HourKey
import com.getfirepit.core.crypto.RoomRatchet
import com.getfirepit.core.crypto.InviteCodec
import com.getfirepit.core.crypto.RoomCrypto
import com.getfirepit.core.crypto.DirectSeal
import com.getfirepit.core.database.MapPinDao
import com.getfirepit.core.database.PeerKeyDao
import com.getfirepit.core.database.PeerKeyEntity
import com.getfirepit.core.database.PendingHandoverDao
import com.getfirepit.core.database.PendingHandoverEntity
import com.getfirepit.core.database.RoomActivityDao
import com.getfirepit.core.database.deleteRoom
import com.getfirepit.core.database.deleteUnfiled
import com.getfirepit.core.database.moveUnfiled
import com.getfirepit.core.database.RoomMemberDao
import com.getfirepit.core.database.PersonCardDao
import com.getfirepit.core.database.PersonCardEntity
import com.getfirepit.core.database.observeAll
import com.getfirepit.core.database.observeRoom
import com.getfirepit.core.database.record
import com.getfirepit.core.database.recordReported
import com.getfirepit.core.database.recordActivity
import com.getfirepit.core.model.ChannelRole
import com.getfirepit.core.model.PersonCard
import com.getfirepit.core.protocol.OwnerName
import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.model.RoomKind
import com.getfirepit.core.model.RoomMember
import com.getfirepit.core.database.MessageDao
import com.getfirepit.core.database.save
import com.getfirepit.core.model.BROADCAST_NODE_NUM
import com.getfirepit.core.model.ChatMessage
import com.getfirepit.core.model.MessageStatus
import com.getfirepit.core.protocol.ChannelSlotManager
import com.getfirepit.core.protocol.ChannelUrl
import com.getfirepit.core.protocol.KeyFingerprint
import com.getfirepit.core.protocol.MeshConstants
import com.getfirepit.core.protocol.MeshPacketBuilder
import com.getfirepit.core.protocol.MeshtasticChannel
import com.getfirepit.core.protocol.PacketOrigin
import com.getfirepit.core.protocol.PositionPrecision
import com.getfirepit.core.protocol.RangeMode
import com.getfirepit.core.protocol.RoomKeyChange
import com.getfirepit.core.protocol.ScheduledKeyChange
import com.getfirepit.core.protocol.TrustRules
import com.getfirepit.core.protocol.TrustRules.PhoneKeySource
import com.getfirepit.protocol.meshchat.Invite
import com.getfirepit.protocol.meshchat.Inviter
import com.getfirepit.protocol.meshchat.JoinHello
import com.getfirepit.protocol.meshchat.LoRaProfile
import com.getfirepit.protocol.meshchat.MeshChatControl
import com.getfirepit.protocol.meshchat.PersonCard as ProtoPersonCard
import com.getfirepit.protocol.meshchat.KeyRotation
import com.getfirepit.protocol.meshchat.SealedMessage
import com.getfirepit.protocol.meshchat.RosterEntry
import com.getfirepit.protocol.meshchat.RoomGrant
import com.getfirepit.protocol.meshchat.RosterEvent
import com.getfirepit.protocol.meshchat.RosterSync
import com.getfirepit.protocol.meshchat.SealedDirect
import com.getfirepit.protocol.meshchat.SealedDirectRefused
import com.getfirepit.core.transport.RadioLink
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleSettings
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio

/** Why a room operation could not be carried out, in terms the UI can show. */
sealed class RoomError(message: String) : Exception(message) {
    data object NotConnected : RoomError("Connect your node first")
    data object NoFreeSlot : RoomError("You're in 7 rooms — leave one to create or join another")
    data class NameTooLong(val bytes: Int) :
        RoomError("Room name is $bytes bytes; the radio allows ${InviteCodec.MAX_ROOM_NAME_BYTES}")

    data object InviteExpired : RoomError("This code expired — ask for a fresh one")
    data object InviteInvalid : RoomError("That isn't a Firepit invite or a Meshtastic channel link")

    /** Firepit's own features need a Firepit room; a shared channel has none of them. */
    data object NotAFirepitRoom : RoomError(
        "This is a standard Meshtastic channel, so it only does what Meshtastic does. " +
            "Create a private room for Firepit's own features.",
    )

    /** The code claims a radio this one already knows, under a key that is not that radio's. */
    data object KeyMismatch : RoomError(
        "This code names a radio yours already knows, but with a different key. Ask them to " +
            "show a fresh code, and check the key their screen shows.",
    )

    data object AlreadyInRoom : RoomError("You're already in this room")

    /** Asked to invite to a room that is no longer on the radio: left, or moved off it by another app. */
    data object RoomGone : RoomError("This room isn't on your radio any more")

    /** A channel read timed out; writing on regardless would rewrite a room with no key. */
    data object RadioUnreadable : RoomError(
        "Couldn't read the radio's channels, so nothing was changed. Try again.",
    )
}

/**
 * Creating, inviting to, joining and leaving rooms.
 *
 * A room is a Meshtastic secondary channel: its 32-byte key *is* the access
 * control, so there is nothing else to grant or revoke.
 */
private sealed interface DirectOpening {
    class Read(val opening: DirectSeal.Opening) : DirectOpening
    data object NoPeerKey : DirectOpening
    data object Replayed : DirectOpening
    data object OutOfHours : DirectOpening
    data object Unreadable : DirectOpening
}

@Singleton
class RoomRepository @Inject constructor(
    private val link: RadioLink,
    private val mesh: MeshRepository,
    private val admin: NodeAdminClient,
    private val memberDao: RoomMemberDao,
    private val messageDao: MessageDao,
    private val receipts: ReceiptRepository,
    private val roomKeys: RoomKeyStore,
    private val range: RangeRepository,
    private val personCardDao: PersonCardDao,
    private val phoneKeys: PhoneKeyStore,
    private val peerKeyDao: PeerKeyDao,
    private val pinDao: MapPinDao,
    private val roomActivity: RoomActivityDao,
    private val keyChangePreferences: RoomKeyChangePreferences,
    private val roomKeyMade: RoomKeyMadeStore,
    private val handovers: PendingHandoverDao,
    private val history: RoomHistory,
    private val sharingStore: SharingStore,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val _opened = MutableSharedFlow<OpenedInRoom>(extraBufferCapacity = 32)
    private val scheduledRotations = ConcurrentHashMap<Int, Unit>()
    private val rotationLocks = ConcurrentHashMap<Int, Mutex>()

    /**
     * Positions, pins and position questions, opened from a room's seal and
     * handed to whoever owns them. Opening is done once, here, where the room
     * keys and the rules about them live.
     */
    val openedInRooms: SharedFlow<OpenedInRoom> = _opened.asSharedFlow()

    /** When each member with a key still owed to them was last tried, so hearing them often costs one resend. */
    private val handoverTried = ConcurrentHashMap<Int, Long>()

    /**
     * How often each member's radio has acknowledged a key handed to them, by
     * room and member. A radio taking it proves nothing about their app keeping
     * it, so the handover stays owed until the app's own word (see
     * [settleHandover]); this only stops it being sent for ever to someone
     * whose radio takes it every time.
     */
    private val handoverAcks = ConcurrentHashMap<Pair<Int, Int>, Int>()
    private val sentHandoverGenerations = ConcurrentHashMap<Pair<Int, Int>, MutableSet<Int>>()

    /** When each member heard still sealing under an old key was last handed the new one, apart from [handoverTried]. */
    private val oldKeyRetried = ConcurrentHashMap<Int, Long>()

    /**
     * Handovers on their way right now, counted by room and member, so a retry
     * never runs alongside one. Counted, so whichever finishes first cannot
     * clear the mark while another is still going.
     */
    private val handingOver = ConcurrentHashMap<Pair<Int, Int>, Int>()

    /** When each sender was last told a sealed message of theirs would not open here. */
    private val refusedAt = ConcurrentHashMap<Int, Long>()

    /** Who the room has been told is on an older build, once per room and sender while the app runs. */
    private val outdatedNoticed = ConcurrentHashMap.newKeySet<Pair<Int, Int>>()

    /**
     * Invites we have issued, so a join hello can be tied back to a room.
     *
     * Only the derived invite key is kept, never the room PSK. Entries are
     * dropped once no hello could still plausibly arrive, which is also what
     * stops the map growing for the life of the process.
     */
    private val issuedInvites = ConcurrentHashMap<Int, IssuedInvite>()

    /** Recent attempts per node, so one stranger cannot grind the token check. */
    private val joinAttempts = ConcurrentHashMap<Int, List<Long>>()
    private val phoneKeyMutex = Mutex()
    private val approvingMutex = Mutex()
    private val approvingJoins = mutableSetOf<Int>()

    private val _pendingJoins = MutableStateFlow<List<PendingJoin>>(emptyList())

    /** People asking to be let in, waiting on an answer from whoever is holding this phone. */
    val pendingJoins: StateFlow<List<PendingJoin>> = _pendingJoins.asStateFlow()

    private val _awaiting = MutableStateFlow<AwaitedRoom?>(null)

    /** The room we have asked to join, until the answer arrives or is given up on. */
    val awaiting: StateFlow<AwaitedRoom?> = _awaiting.asStateFlow()

    /** Stops waiting, for when the answer never came or the reader walked away. */
    fun stopWaiting() {
        _awaiting.value = null
    }

    /** Kept so a room joined later can be told who we are without asking the UI again. */
    @Volatile
    private var latestCard: Card? = null

    private data class IssuedInvite(
        val roomId: Int,
        val inviteKey: ByteArray,
        val secret: ByteArray,
        val issuedAt: Long,
        /** Set once somebody has been let in on it, which spends it. */
        val usedBy: Int? = null,
    ) {
        fun wipe() {
            inviteKey.fill(0)
            secret.fill(0)
        }
    }

    private val inviteRandom = SecureRandom()

    /** Who we have seen in [roomId]. See [RoomMember] for what this can and cannot know. */
    fun observeMembers(roomId: Int): Flow<List<RoomMember>> = memberDao.observeRoom(roomId)

    /** Every node in any room we are in — the people this phone counts as ours. */
    fun observeGroupNodes(): Flow<Set<Int>> = memberDao.observeAllNodeNums().map { it.toSet() }

    /**
     * A Firepit room we and [nodeNum] are both in, or null when we share none.
     *
     * Asking somebody's radio where it is travels inside a room and nowhere
     * else: the question and the answer both ride the room's key, so nobody
     * outside it learns that the question was asked or what came back.
     */
    suspend fun sharedRoomWith(nodeNum: Int): RoomChannel? =
        rooms().firstOrNull { room ->
            roomKeys.holds(room.id) && nodeNum in memberDao.nodeNumsIn(room.id)
        }

    /** How the people in our rooms describe themselves, by node number. */
    fun observePersonCards(): Flow<Map<Int, PersonCard>> = personCardDao.observeAll()

    /** Remembers the card without sending it, for restoring it at startup. */
    fun rememberPersonCard(name: String, tag: String, colourSlot: Int?) {
        latestCard = Card(name, tag, colourSlot)
    }

    /**
     * Tells every room we are in who is holding this radio.
     *
     * Sealed per room, so it reaches the people who already share a key with us
     * and nobody else. Nothing goes on the primary channel: on the open mesh a
     * Firepit node looks like any other node, which is the point.
     */
    suspend fun sharePersonCard(name: String, tag: String, colourSlot: Int?) {
        latestCard = Card(name, tag, colourSlot)
        sharePersonCard()
    }

    /** Re-sends whatever was last set, for when a new room appears. */
    private suspend fun sharePersonCard() {
        shareCard(latestCard ?: NO_NAME, ChannelSlotManager.rooms(mesh.channels.value))
    }

    /** Introduces us to one room, for when somebody new turns up in it. */
    private suspend fun shareCardWith(roomId: Int) {
        val room = ChannelSlotManager.findByRoomId(mesh.channels.value, roomId) ?: return
        shareCard(latestCard ?: NO_NAME, listOf(room))
    }

    /**
     * Answers a join after a random pause, so a room full of people does not
     * reply to the same arrival at once and talk over each other on the air.
     */
    private fun greet(roomId: Int) {
        scope.launch {
            delay(Random.nextLong(GREETING_SPREAD.inWholeMilliseconds))
            runCatching { shareCardWith(roomId) }
                .onFailure { cause -> Log.w(TAG, "could not greet room $roomId", cause) }
        }
    }

    private data class Card(val name: String, val tag: String, val colourSlot: Int?)

    /**
     * Sent when nobody has chosen a name, so the card carries only the phone
     * key. Without it, somebody who never opened the settings — typically the
     * room's founder — could never be handed a new room key.
     */
    private val NO_NAME = Card(name = "", tag = "", colourSlot = null)

    private suspend fun shareCard(card: Card, rooms: List<RoomChannel>) {
        val control = MeshChatControl(
            version = InviteCodec.VERSION,
            person_card = ProtoPersonCard(
                name = OwnerName.longName(card.name),
                tag = OwnerName.shortName(card.tag),
                // Plus one, because proto3 cannot tell an unset 0 from slot 0.
                colour_slot_plus_one = card.colourSlot?.plus(1) ?: 0,
                // Where members seal the next room key, should the room move on.
                phone_key = phoneKeys.publicKey(),
            ),
        )
        val myNodeNum = mesh.myNodeNum.value ?: return

        rooms.forEach { room ->
            // Firepit rooms only: on an interoperable channel this would be
            // unreadable noise to every other client on it.
            val sealed = sealFor(room.id, myNodeNum, control) ?: return@forEach
            runCatching {
                link.send(
                    ToRadio(
                        packet = MeshPacketBuilder.meshPacket(
                            to = MeshConstants.BROADCAST_NODENUM,
                            channel = room.index,
                            portNum = PortNum.PRIVATE_APP,
                            payload = MeshChatControl(sealed_message = sealed).encode().let(ByteString::of),
                            hopLimit = mesh.hopLimitForSending(),
                            // Like words, so the header does not say which this is.
                            wantAck = true,
                            // Nobody is waiting on it, and it must never delay words.
                            priority = MeshPacket.Priority.BACKGROUND,
                        ),
                    ),
                )
            }.onFailure { cause -> Log.w(TAG, "could not share card to room ${room.id}", cause) }
        }
        Log.i(TAG, "shared person card with ${rooms.size} rooms")
    }

    /**
     * [control] sealed under this hour's key of one generation of [roomId],
     * ready to travel. Null when this phone holds no such key, which means the
     * slot is not a Firepit room.
     *
     * Nothing new is sealed under a key the room has moved on from; only a
     * rotation names an older generation, and does so on purpose.
     */
    private fun sealFor(
        roomId: Int,
        myNodeNum: Int,
        control: MeshChatControl,
        generation: Int? = null,
    ): SealedMessage? = roomKeys.seal(roomId, myNodeNum, control.encode(), generation)

    /**
     * Seals [control] under [roomId]'s current key and puts it on the room's
     * own slot, to everyone or to one member. False when this phone cannot
     * seal for the room, or the radio would not take it.
     *
     * Always asks for an acknowledgement, whatever the payload, so the one
     * header bit anyone can read does not tell words from receipts or pins.
     */
    suspend fun sendSealed(
        roomId: Int,
        control: MeshChatControl,
        to: Int = MeshConstants.BROADCAST_NODENUM,
        priority: MeshPacket.Priority = MeshPacket.Priority.BACKGROUND,
    ): Boolean {
        val myNodeNum = mesh.myNodeNum.value ?: return false
        val slot = ChannelSlotManager.slotOf(mesh.channels.value, roomId) ?: return false
        val sealed = sealFor(roomId, myNodeNum, control) ?: return false
        return runCatching {
            link.send(
                ToRadio(
                    packet = MeshPacketBuilder.meshPacket(
                        to = to,
                        channel = slot,
                        portNum = PortNum.PRIVATE_APP,
                        payload = MeshChatControl(sealed_message = sealed).encode().let(ByteString::of),
                        hopLimit = mesh.hopLimitForSending(),
                        wantAck = true,
                        priority = priority,
                    ),
                ),
            )
        }.onFailure { cause -> Log.w(TAG, "could not send to room $roomId", cause) }.isSuccess
    }

    /** Something happened in [roomId] that a person did, for judging when it went quiet. */
    internal suspend fun noteActivity(roomId: Int) {
        roomActivity.recordActivity(roomId, System.currentTimeMillis())
    }

    /**
     * Keeps a phone key per [TrustRules.shouldStorePhoneKey], tracking which
     * keys this phone saw checked in person.
     */
    private data class PhoneKeyLearned(val stored: Boolean, val replaced: Boolean)

    private suspend fun learnPhoneKey(nodeNum: Int, key: ByteString, source: PhoneKeySource): PhoneKeyLearned {
        if (!KeyEnvelope.isValidPublicKey(key.toByteArray())) return PhoneKeyLearned(stored = false, replaced = false)
        val learned = phoneKeyMutex.withLock {
            val row = peerKeyDao.find(nodeNum)
            val known = row?.phoneKey?.decodeBase64()
            val decision = TrustRules.shouldStorePhoneKey(known, row?.inPerson == true, key, source)
            if (!decision.store) {
                if (known != null && known != key) {
                    Log.w(TAG, "a different phone key was offered for $nodeNum; kept the one first seen")
                }
                PhoneKeyLearned(stored = false, replaced = false)
            } else {
                peerKeyDao.upsert(PeerKeyEntity(nodeNum, key.base64(), System.currentTimeMillis(), decision.inPerson))
                PhoneKeyLearned(stored = true, replaced = decision.replaced)
            }
        }
        if (learned.replaced) noticeKeyChanged(nodeNum)
        return learned
    }

    private suspend fun noticeKeyChanged(nodeNum: Int) {
        val myNodeNum = mesh.myNodeNum.value ?: return
        val name = personCardDao.findEntity(nodeNum)
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: MeshConstants.formatNodeId(nodeNum)
        messageDao.save(
            ChatMessage(
                id = MeshPacketBuilder.randomPacketId(),
                channel = 0,
                fromNodeNum = NOTICE_NODE,
                toNodeNum = nodeNum,
                text = "$name's phone key changed. If they did not get a new phone, check with them in person.",
                sentAt = System.currentTimeMillis(),
                status = MessageStatus.RECEIVED,
                isOutgoing = false,
            ),
            myNodeNum,
        )
    }

    /** Starts listening for join and roster traffic. Safe to call once per process. */
    fun start() {
        scope.launch {
            link.inbound.collect { message ->
                message.packet?.let { runCatching { handlePacket(it) }.onFailure { cause -> Log.w(TAG, "roster", cause) } }
            }
        }

        scope.launch {
            // Re-introduce ourselves each time the radio comes back. Somebody
            // out of range while the card changed catches up by reconnecting,
            // which is when they were going to hear anything anyway — cheaper
            // than a timer that pays for the silence too.
            // StateFlow already conflates, so this fires on the change alone.
            mesh.isConnected.collect { connected ->
                if (!connected) return@collect
                // Channels arrive with the connection, and there is nothing to
                // seal to until they do.
                val ready = withTimeoutOrNull(CHANNELS_TIMEOUT) {
                    mesh.channels.first { it.isNotEmpty() }
                }
                if (ready != null) runCatching { sharePersonCard() }
            }
        }

        scope.launch {
            // On the clock rather than on traffic: a quiet room has to forget
            // its old keys as surely as a busy one.
            while (isActive) {
                runCatching { eraseOldKeys() }.onFailure { cause -> Log.w(TAG, "could not erase old room keys", cause) }
                delay(KEY_ERASE_EVERY)
            }
        }
    }

    /**
     * Destroys every hour's key no longer needed, keeping only the generations
     * a member still owed a rotation holds: their new key is sealed under it.
     */
    private suspend fun eraseOldKeys() {
        roomKeys.erase(handovers.all().flatMap { record -> mayHold(record).map { RoomGeneration(record.roomId, it) } }.toSet())
    }

    /**
     * Every generation a member owed [record] might be holding: the one they
     * were last seen sealing under, up to the one before the key they are
     * owed. Usually just the first; more when a rotation came while an
     * earlier key was on its way to them.
     */
    private fun mayHold(record: PendingHandoverEntity): IntRange = record.heldGeneration until record.generation

    /** Rooms currently provisioned on the radio, lowest slot first. */
    fun rooms(): List<RoomChannel> = ChannelSlotManager.rooms(mesh.channels.value)

    suspend fun createRoom(name: String): RoomChannel {
        val myNodeNum = mesh.myNodeNum.value ?: throw RoomError.NotConnected

        val trimmed = name.trim()
        val nameBytes = trimmed.toByteArray().size
        if (nameBytes > InviteCodec.MAX_ROOM_NAME_BYTES) throw RoomError.NameTooLong(nameBytes)

        val slot = ChannelSlotManager.nextFreeSlot(mesh.channels.value) ?: throw RoomError.NoFreeSlot
        val roomId = RoomCrypto.generateRoomId()

        // Before the channel exists, so the slot is a sealed room from the
        // moment it appears rather than briefly looking like a plain one.
        roomKeys.generate(roomId)

        admin.setChannel(
            channelFor(
                index = slot,
                name = trimmed,
                psk = RoomCrypto.generatePsk(),
                roomId = roomId,
            ),
        )
        Log.i(TAG, "created room $roomId in slot $slot")

        // Nobody vouches for the founder; they are in the room by construction.
        val now = System.currentTimeMillis()
        memberDao.record(roomId, myNodeNum, now, invitedBy = myNodeNum)
        roomActivity.joined(roomId, now)
        roomKeyMade.record(roomId, RoomKeyStore.FIRST, now)
        roomKeyMade.markMadeByMe(roomId)

        return RoomChannel(
            index = slot,
            name = trimmed,
            role = ChannelRole.SECONDARY,
            id = roomId,
            positionPrecision = PositionPrecision.DISABLED,
            kind = RoomKind.FIREPIT,
        )
    }

    /**
     * Adds a channel that other Meshtastic clients can take part in.
     *
     * Not a Firepit room and deliberately not sealed: a stock client cannot
     * open our envelope, so the whole point of this is to speak the plain
     * protocol. [psk] null means the published default key and the open mesh;
     * a 32-byte key means a channel shared with particular people, which keeps
     * the mesh at large out but not anyone holding one of the radios.
     *
     * It occupies a secondary slot, so it sits alongside private rooms rather
     * than replacing them — secondary channels are used only for decryption,
     * and the frequency still comes from slot 0.
     */
    suspend fun addMeshtasticChannel(name: String, psk: ByteString? = null): RoomChannel {
        mesh.myNodeNum.value ?: throw RoomError.NotConnected

        val preset = mesh.snapshot.value?.lora?.modem_preset
        val trimmed = name.trim().ifEmpty { MeshtasticChannel.publicNameFor(preset) }
        val nameBytes = trimmed.toByteArray().size
        if (nameBytes > InviteCodec.MAX_ROOM_NAME_BYTES) throw RoomError.NameTooLong(nameBytes)

        // Only an ordinary channel of the same name is updated in place. A
        // Firepit room is never taken over this way: a link naming one would
        // otherwise replace a sealed room with a plain one under a stranger's key.
        val existing = ChannelSlotManager.rooms(mesh.channels.value)
            .firstOrNull { it.name == trimmed && it.kind != RoomKind.FIREPIT }
        val slot = existing?.index
            ?: ChannelSlotManager.nextFreeSlot(mesh.channels.value)
            ?: throw RoomError.NoFreeSlot

        val channel = if (psk == null) {
            MeshtasticChannel.publicChannel(index = slot, preset = preset)
        } else {
            MeshtasticChannel.privateChannel(index = slot, name = trimmed, psk = psk)
        }
        admin.setChannel(channel)
        Log.i(TAG, "added Meshtastic channel \"$trimmed\" in slot $slot")

        return RoomChannel(
            index = slot,
            name = trimmed,
            role = ChannelRole.SECONDARY,
            id = channel.settings?.id ?: 0,
            // Never our location: this channel reaches people we have not chosen.
            positionPrecision = PositionPrecision.DISABLED,
            kind = if (psk == null) RoomKind.MESHTASTIC_PUBLIC else RoomKind.MESHTASTIC_PRIVATE,
        )
    }

    /**
     * Joins the channels in a Meshtastic share link.
     *
     * Reading these is one-way on purpose: Firepit issues its own invites, which
     * carry a rotating token and a room key the radio never holds. This format
     * can carry neither, so anything shared through it would be weaker than what
     * the person sharing it thinks they are handing over.
     *
     * The link's own primary is taken as a secondary channel rather than
     * written to slot 0: that slot sets the radio's frequency and carries our
     * identity, and a scanned link should add a conversation, not silently
     * reconfigure the node.
     */
    suspend fun joinMeshtasticChannels(shared: ChannelUrl.Shared): List<RoomChannel> {
        mesh.myNodeNum.value ?: throw RoomError.NotConnected

        return shared.channels.mapNotNull { settings ->
            val name = sanitizeMeshText(settings.name)
                .ifEmpty { MeshtasticChannel.publicNameFor(shared.lora?.modem_preset) }
            runCatching {
                addMeshtasticChannel(
                    name = name,
                    psk = settings.psk.takeUnless { MeshtasticChannel.isWellKnown(it) },
                )
            }.onFailure { cause ->
                Log.w(TAG, "could not add \"$name\" from a channel link", cause)
            }.getOrNull()
        }.also { added ->
            if (added.isEmpty()) throw RoomError.NoFreeSlot
        }
    }

    /**
     * Builds a QR invite for [roomId], valid for the current rotation window.
     *
     * Carries no key material. A photograph of the result is a request to be
     * let in, which the inviter still has to approve, and which stops being
     * accepted a few seconds later.
     */
    suspend fun buildInvite(roomId: Int, nowMillis: Long = System.currentTimeMillis()): Invite {
        val myNodeNum = mesh.myNodeNum.value ?: throw RoomError.NotConnected
        val room = ChannelSlotManager.findByRoomId(mesh.channels.value, roomId) ?: throw RoomError.RoomGone
        val settings = admin.getChannel(room.index)?.settings ?: throw RoomError.NotConnected
        val psk = settings.psk.toByteArray()

        val generation = 1
        val inviteKey = RoomCrypto.inviteKey(psk, roomId, generation)
        val window = RoomCrypto.windowFor(nowMillis)

        forgetStaleInvites(nowMillis)
        // Stable for as long as this room keeps being offered, so a hello that
        // arrives several rotations later still points at the right room. The
        // rotating token, not this id, is what limits a stolen code. A spent id
        // is never reissued: that is what makes one code let one person in.
        val existingInvite = issuedInvites.entries
            .firstOrNull { it.value.roomId == roomId && it.value.usedBy == null }
        val inviteId = existingInvite?.key ?: RoomCrypto.generateRoomId()
        val issued = existingInvite?.value?.copy(issuedAt = nowMillis)
            ?: IssuedInvite(
                roomId = roomId,
                inviteKey = inviteKey,
                secret = ByteArray(InviteCodec.INVITE_SECRET_SIZE).also(inviteRandom::nextBytes),
                issuedAt = nowMillis,
            )
        issuedInvites.put(inviteId, issued)?.let { old ->
            if (old.secret !== issued.secret || old.inviteKey !== issued.inviteKey) old.wipe()
        }

        return Invite(
            version = InviteCodec.VERSION,
            room_id = roomId,
            room_name = room.name,
            generation = generation,
            inviter = Inviter(node_num = myNodeNum, user = mesh.myUser),
            invite_id = inviteId,
            issued_at = (nowMillis / 1000L).toInt(),
            window = window,
            token = RoomCrypto.token(issued.inviteKey, myNodeNum, window).toByteString(),
            secret = issued.secret.toByteString(),
            // Which frequency slot we are on. A joiner on the other mode is
            // tuned elsewhere and would never hear this room at all.
            lora = LoRaProfile(mesh_mode = range.mode.value.wire),
        )
    }

    /**
     * Asks to be let into the invited room.
     *
     * Nothing is written to the radio here: the code carries no keys, so there
     * is no room to write until the inviter answers with a [RoomGrant]. The
     * radio is retuned first, because a joiner on the other mode would never
     * hear the answer.
     */
    suspend fun joinRoom(invite: Invite, nowMillis: Long = System.currentTimeMillis()) {
        mesh.myNodeNum.value ?: throw RoomError.NotConnected
        verify(invite, nowMillis)
        val inviter = invite.inviter?.node_num ?: throw RoomError.InviteInvalid

        // A code naming a room we already hold is only worth answering from a
        // member of it, bringing us up to date after a missed key change. From
        // anyone else it is an attempt to replace that room with theirs.
        if (ChannelSlotManager.findByRoomId(mesh.channels.value, invite.room_id) != null) {
            if (memberDao.findEntity(invite.room_id, inviter) == null) throw RoomError.AlreadyInRoom
        } else if (ChannelSlotManager.nextFreeSlot(mesh.channels.value) == null) {
            throw RoomError.NoFreeSlot
        }

        // Before anything is sent: the answer comes back on the frequency this
        // sets, so asking from the wrong one is asking into silence.
        range.alignWith(RangeMode.of(invite.lora?.mesh_mode))

        _awaiting.value = AwaitedRoom(
            roomId = invite.room_id,
            roomName = invite.room_name,
            inviteId = invite.invite_id,
            inviter = inviter,
            ownFingerprint = ownFingerprint(),
            inviteSecret = invite.secret,
        )
        askToJoin(invite)
    }

    /**
     * This radio's key and this phone's key as one line, short enough to read
     * aloud to the person being asked. Their screen shows the same line for
     * the hello they received; see [KeyFingerprint.ofJoin].
     */
    fun ownFingerprint(): String? {
        val radioKey = mesh.myUser?.public_key?.takeIf { it.size == PUBLIC_KEY_SIZE } ?: return null
        return KeyFingerprint.ofJoin(radioKey.toByteArray(), phoneKeys.publicKey().toByteArray())
    }

    /**
     * Sends the hello, encrypted to the key in the invite.
     *
     * Straight to PKI with no NodeInfo broadcast first: the inviter's public
     * key came with the code, so nothing has to propagate before we can speak
     * privately. Our own keys ride inside, where only they can read them.
     */
    private suspend fun askToJoin(invite: Invite) {
        val inviter = invite.inviter ?: throw RoomError.InviteInvalid
        val publicKey = inviter.user?.public_key?.takeIf { it.size == PUBLIC_KEY_SIZE }
            ?: throw RoomError.InviteInvalid
        val mine = mesh.myUser?.public_key?.takeIf { it.size == PUBLIC_KEY_SIZE }
            ?: throw RoomError.NotConnected

        // The radio encrypts from its own NodeDB, which is bounded and may never
        // have heard of this inviter. The code carried their key, so hand it
        // over rather than broadcasting and hoping they answer in time. Judged
        // against what the radio itself reported, not the phone's own history
        // of nodes, which outlives evictions and other radios: the only thing
        // refused is a code that would overwrite a key the radio really holds,
        // which would redirect everything we send that node to whoever printed
        // the code. Re-adding the same key is harmless, and repairs an eviction.
        val radioKnows = mesh.snapshot.value?.nodes?.get(inviter.node_num)?.user?.public_key
            ?.takeIf { it.size == PUBLIC_KEY_SIZE }
        if (TrustRules.contactFor(radioKnows, publicKey) == TrustRules.Contact.MISMATCH) {
            _awaiting.value = null
            Log.w(TAG, "code for ${inviter.node_num} carries a key that is not the one the radio holds")
            throw RoomError.KeyMismatch
        }
        inviter.user?.let { user ->
            runCatching { admin.addContact(inviter.node_num, user) }
                .onFailure { cause -> Log.w(TAG, "could not add ${inviter.node_num} as a contact", cause) }
        }

        val control = MeshChatControl(
            version = InviteCodec.VERSION,
            join_hello = JoinHello(
                invite_id = invite.invite_id,
                token = invite.token,
                generation = invite.generation,
                app_version = InviteCodec.VERSION,
                joiner_key = mine,
                phone_key = phoneKeys.publicKey(),
            ),
        )
        link.send(
            ToRadio(
                packet = MeshPacketBuilder.meshPacket(
                    to = inviter.node_num,
                    channel = 0,
                    portNum = PortNum.PRIVATE_APP,
                    payload = control.encode().let(ByteString::of),
                    pkiEncrypted = true,
                    publicKey = publicKey,
                    wantAck = true,
                ),
            ),
        )
        Log.i(TAG, "asked ${inviter.node_num} to be let into room ${invite.room_id}")
    }

    /**
     * Moves the room to new keys and leaves [remove] behind.
     *
     * Nothing can take a key back from someone who already has it, so removal
     * is really everyone else moving on without them. In order:
     *
     * 1. The room is told, sealed under the key being replaced and on the
     *    channel key being replaced, that it is moving. A member whose own copy
     *    of the new key then goes astray still stops talking under the old one,
     *    which by then only the removed member reads.
     * 2. Our own radio moves to the new channel key.
     * 3. Each remaining member is handed the new keys, sealed to their phone,
     *    and counts as reached only when their own radio acknowledges it. Our
     *    radio accepting the packet proves nothing about where it went.
     *
     * Anyone not reached is remembered and handed the key when next heard.
     */
    suspend fun rotateRoom(roomId: Int, remove: Set<Int> = emptySet(), scheduled: Boolean = false): RotationResult =
        rotationLock(roomId).withLock { rotateRoomLocked(roomId, remove, scheduled) }

    private suspend fun rotateRoomLocked(roomId: Int, remove: Set<Int>, scheduled: Boolean): RotationResult {
        val myNodeNum = mesh.myNodeNum.value ?: throw RoomError.NotConnected
        val room = ChannelSlotManager.findByRoomId(mesh.channels.value, roomId)
            ?: throw RoomError.InviteInvalid
        // Rotation hands out a new Firepit key over PKI; a standard Meshtastic
        // channel has no such key and no way to receive one, and a room that
        // moved on without us is not ours to move again.
        if (!roomKeys.canSeal(roomId)) throw RoomError.NotAFirepitRoom

        val previous = roomKeys.generationOf(roomId)
        val keys = NewKeys(
            roomId = roomId,
            roomName = room.name,
            generation = previous + 1,
            psk = RoomCrypto.generatePsk(),
            firepitKey = HourKey(RoomRatchet.hourOf(roomKeys.time.wallMillis()), RoomCipher.generateKey()),
            quiet = scheduled,
        )
        // The store keeps its own copy of the new key; this one goes when the rotation is done.
        try {
            val previousMadeAt = roomKeyMade.record(roomId)?.madeAt ?: 0L
            // Anyone still owed a key from an earlier rotation holds an older one
            // than [previous], and missed its removals too: their handover has to
            // be sealed under what they actually hold and carry both.
            val stillOwed = handovers.forRoom(roomId).associateBy { it.nodeNum }

            // From the notice to the record of who is owed the key, all or nothing:
            // stopping half-way — the app swiped away, the screen closed — would
            // leave members told the room moved and nobody remembering to hand
            // them the key it moved to.
            val owed = withContext(NonCancellable) {
                val now = System.currentTimeMillis()
                announceRotation(room.index, roomId, myNodeNum, previous, keys.generation, quiet = scheduled)
                admin.setChannel(channelFor(room.index, room.name, keys.psk, roomId))
                roomKeys.remember(roomId, keys.firepitKey, keys.generation)
                roomActivity.joined(roomId, now)
                roomKeyMade.record(roomId, keys.generation, now)
                roomKeyMade.clearDue(roomId)
                remove.forEach { memberDao.remove(roomId, it) }

                val owed = memberDao.nodeNumsIn(roomId).filter { it != myNodeNum }.map { member ->
                    val earlier = stillOwed[member]
                    PendingHandoverEntity(
                        roomId = roomId,
                        nodeNum = member,
                        generation = keys.generation,
                        heldGeneration = earlier?.heldGeneration ?: previous,
                        removed = (earlier?.removedNodes().orEmpty() + remove).distinct().joinToString(","),
                        createdAt = now,
                        lastTriedAt = now,
                    )
                }
                // Written before anything stale is cleared, never the other way
                // round: the hourly erase keeps whichever generation a record says
                // a member still holds, and must never catch a moment with none.
                owed.forEach { handovers.upsert(it) }
                owed.forEach {
                    sentHandoverGenerations.putIfAbsent(it.roomId to it.nodeNum, Collections.synchronizedSet(mutableSetOf()))
                }
                val owedNodes = owed.map { it.nodeNum }.toSet()
                handovers.forRoom(roomId)
                    .filter { it.nodeNum !in owedNodes }
                    .forEach {
                        handovers.delete(roomId, it.nodeNum)
                        sentHandoverGenerations.remove(roomId to it.nodeNum)
                    }
                if (!scheduled) noticeInRoom(room.index, rotationNotice(remove), roomId)
                owed
            }

            // Sent a moment apart and awaited together: one member out of range
            // must not hold up everyone else's key for the length of a timeout.
            val immediate = if (scheduled) {
                owed.filter { record ->
                    memberDao.findEntity(roomId, record.nodeNum)?.lastHeard?.let { it >= previousMadeAt } == true
                }
            } else {
                owed
            }
            // Just tried, so their radio's own acknowledgement of it does not set
            // off a retry.
            val handedAt = System.currentTimeMillis()
            immediate.forEach { record ->
                handoverTried[record.nodeNum] = handedAt
            }
            immediate.forEach { record -> handingOver.merge(roomId to record.nodeNum, 1, Int::plus) }
            val reached = try {
                coroutineScope {
                    immediate.mapIndexed { order, record ->
                        async {
                            delay(HANDOVER_SPACING * order)
                            record.nodeNum.takeIf { handOver(record, keys) }
                        }
                    }.awaitAll().filterNotNull().toSet()
                }
            } finally {
                immediate.forEach { record -> doneHandingOver(roomId to record.nodeNum) }
            }
            // Still owed until their app seals something under the new key.
            reached.forEach { member -> handoverAcks.merge(roomId to member, 1, Int::plus) }

            val keeping = owed.map { it.nodeNum }.toSet()
            Log.i(TAG, "rotated room $roomId to generation ${keys.generation}, ${reached.size}/${keeping.size} confirmed")
            return RotationResult(keys.generation, reached, keeping - reached)
        } finally {
            keys.firepitKey.key.fill(0)
        }
    }

    /** The keys a rotation moves a room to, before they are sealed to anybody. */
    private class NewKeys(
        val roomId: Int,
        val roomName: String,
        val generation: Int,
        val psk: ByteArray,
        /** One hour's key: whoever is handed it reads from that hour on, and nothing before. */
        val firepitKey: HourKey,
        val quiet: Boolean,
    )

    private fun PendingHandoverEntity.removedNodes(): List<Int> = removed.split(",").mapNotNull(String::toIntOrNull)

    private fun rotationLock(roomId: Int): Mutex = rotationLocks.getOrPut(roomId) { Mutex() }

    /**
     * Hands one member the new keys, and says whether their radio confirmed it.
     *
     * The new room key is sealed to their phone, so the radio in between cannot
     * read it, and the whole rotation is sealed under the key they hold now, so
     * only somebody who holds the room can move it on. Their radio key is put
     * back in our radio first — its node list evicts, and the firmware refuses
     * to encrypt to a node it no longer knows. Members whose phone key we never
     * learned cannot be handed one.
     */
    private suspend fun handOver(record: PendingHandoverEntity, keys: NewKeys): Boolean {
        val member = record.nodeNum
        val myNodeNum = mesh.myNodeNum.value ?: return false
        val radioKey = mesh.publicKeyOf(member) ?: run {
            Log.w(TAG, "no radio key for $member; cannot hand over the new room key")
            return false
        }
        val phoneKey = peerKeyDao.find(member)?.phoneKey?.decodeBase64()?.toByteArray()
            ?.takeIf(KeyEnvelope::isValidPublicKey)
            ?: run {
                Log.w(TAG, "no phone key for $member; cannot hand over the new room key")
                return false
            }
        // Never overwrite a key our radio holds for them with a different one:
        // that would send the room's new key to whoever we were told about.
        if (TrustRules.contactFor(mesh.radioKeyOf(member), radioKey) == TrustRules.Contact.MISMATCH) {
            Log.w(TAG, "our radio holds a different key for $member; not handing over")
            return false
        }
        mesh.contactFor(member)?.takeIf { it.public_key == radioKey }?.let { user ->
            runCatching { admin.addContact(member, user) }
                .onFailure { cause -> Log.w(TAG, "could not add $member as a contact", cause) }
        }

        val rotation = KeyRotation(
            room_id = keys.roomId,
            generation = keys.generation,
            room_psk = keys.psk.toByteString(),
            room_name = keys.roomName,
            removed = record.removedNodes(),
            quiet = keys.quiet,
            sealed_key = KeyEnvelope.seal(
                phoneKey,
                keys.firepitKey.key,
                KeyEnvelope.contextOf(keys.roomId, keys.generation, member, keys.firepitKey.hour),
            ).toByteString(),
            key_hour = keys.firepitKey.hour,
        )
        // Sealed under each generation they might hold, newest first: they
        // only accept it under the one they hold now, and when a rotation came
        // while an earlier key was on its way, that is not known here.
        var confirmed = false
        val sealGenerations = handoverSealGenerations(record)
        if (sealGenerations.isEmpty()) return false
        for (held in sealGenerations) {
            val sealed = sealFor(
                keys.roomId,
                myNodeNum,
                MeshChatControl(version = InviteCodec.VERSION, key_rotation = rotation),
                generation = held,
            ) ?: continue
            val payload = MeshChatControl(sealed_message = sealed).encode()
            if (payload.size > PKI_PAYLOAD_BUDGET) {
                Log.w(TAG, "rotation for $member is ${payload.size} bytes, too big to send privately")
                return false
            }
            val packet = MeshPacketBuilder.meshPacket(
                to = member,
                channel = 0,
                portNum = PortNum.PRIVATE_APP,
                payload = payload.toByteString(),
                hopLimit = mesh.hopLimitForSending(),
                pkiEncrypted = true,
                publicKey = radioKey,
                wantAck = true,
            )
            if (mesh.sendAwaitingAck(packet, member, HANDOVER_ACK_TIMEOUT)) confirmed = true
        }
        sentHandoverGenerations
            .getOrPut(record.roomId to record.nodeNum) { Collections.synchronizedSet(mutableSetOf()) }
            .add(record.generation)
        if (!confirmed) Log.w(TAG, "$member did not confirm the new key for room ${keys.roomId}")
        return confirmed
    }

    private suspend fun handoverSealGenerations(record: PendingHandoverEntity): List<Int> {
        val range = mayHold(record)
        if (range.count() > MAX_HANDOVER_GENERATION_RANGE) {
            handovers.delete(record.roomId, record.nodeNum)
            sentHandoverGenerations.remove(record.roomId to record.nodeNum)
            return emptyList()
        }
        val sent = sentHandoverGenerations[record.roomId to record.nodeNum]
            ?: return range.reversed().toList()
        return (listOf(record.heldGeneration) + sent.filter { it in range })
            .distinct()
            .sorted()
    }

    /**
     * Tries again to hand [nodeNum] a key a rotation could not deliver.
     *
     * Hearing anything from them means they are back in range. At most once
     * every [HANDOVER_RETRY] per member, however chatty they are. Keys are read
     * back from the radio and the key store, so nothing secret waits on disk.
     *
     * Once their radio has taken a key [MAX_UNCONFIRMED_HANDOVERS] times, only
     * [stillOnOldKey] sends it again: them sealing under the key they had is
     * proof the new one never reached their app.
     */
    private fun retryHandoversTo(nodeNum: Int, stillOnOldKey: Boolean = false) {
        val now = System.currentTimeMillis()
        // Proof they lack the key keeps its own interval, so ordinary traffic
        // heard just before it cannot swallow the one retry that would help.
        val tried = if (stillOnOldKey) oldKeyRetried else handoverTried
        val last = tried[nodeNum]
        if (last != null && now - last < HANDOVER_RETRY.inWholeMilliseconds) return
        tried[nodeNum] = now
        // And the ordinary retry the same packet sets off afterwards has nothing left to do.
        if (stillOnOldKey) handoverTried[nodeNum] = now
        scope.launch {
            runCatching {
                handovers.forNode(nodeNum).forEach { owed ->
                    val acked = handoverAcks[owed.roomId to nodeNum] ?: 0
                    if (stillOnOldKey || acked < MAX_UNCONFIRMED_HANDOVERS) retryHandover(owed, now)
                }
            }.onFailure { cause -> Log.w(TAG, "could not retry handing $nodeNum a key", cause) }
        }
    }

    /**
     * What a member sealing under [generation] of [roomId] says about a key
     * still owed to them: under the key they were handed, or a later one, their
     * app has it and the handover is done; under an older one it never arrived.
     */
    private suspend fun settleHandover(roomId: Int, member: Int, generation: Int) {
        val owed = handovers.forNode(member).firstOrNull { it.roomId == roomId } ?: return
        if (generation >= owed.generation) {
            if (handovers.deleteUpTo(roomId, member, generation) > 0) {
                handoverAcks.remove(roomId to member)
                sentHandoverGenerations.remove(roomId to member)
                Log.i(TAG, "$member confirmed the key for room $roomId")
            }
        } else {
            retryHandoversTo(member, stillOnOldKey = true)
        }
    }

    private suspend fun retryHandover(owed: PendingHandoverEntity, now: Long) {
        val member = owed.roomId to owed.nodeNum
        if (handingOver.putIfAbsent(member, 1) != null) return
        try {
            retryHandoverNow(owed, now)
        } finally {
            doneHandingOver(member)
        }
    }

    private fun doneHandingOver(member: Pair<Int, Int>) {
        handingOver.computeIfPresent(member) { _, count -> (count - 1).takeIf { it > 0 } }
    }

    private suspend fun retryHandoverNow(owed: PendingHandoverEntity, now: Long) {
        val stillOwed = owed.generation == roomKeys.generationOf(owed.roomId) &&
            memberDao.findEntity(owed.roomId, owed.nodeNum) != null
        if (!stillOwed) {
            // This record only: a later rotation's, written meanwhile, stays.
            handovers.deleteUpTo(owed.roomId, owed.nodeNum, owed.generation)
            return
        }
        val room = ChannelSlotManager.findByRoomId(mesh.channels.value, owed.roomId) ?: return
        val psk = admin.getChannel(room.index)?.settings?.psk?.takeIf { it.size == RoomCrypto.PSK_SIZE } ?: return
        // This hour's key, not the one the rotation started with: they read
        // from when they are handed it, as anybody joining would.
        val key = roomKeys.currentKey(owed.roomId, owed.generation) ?: return
        val keys = NewKeys(
            roomId = owed.roomId,
            roomName = room.name,
            generation = owed.generation,
            psk = psk.toByteArray(),
            firepitKey = key,
            quiet = owed.removedNodes().isEmpty(),
        )
        val handed = try {
            handOver(owed, keys)
        } finally {
            key.key.fill(0)
        }
        // Only while still owed as tried: their app may have settled it while
        // this was in flight, and a settled handover must stay settled.
        val stillOwedAsTried = handovers.touch(owed.roomId, owed.nodeNum, owed.generation, now) > 0
        if (handed && stillOwedAsTried) {
            handoverAcks.merge(owed.roomId to owed.nodeNum, 1, Int::plus)
            Log.i(TAG, "handed ${owed.nodeNum} the key for room ${owed.roomId} again; waiting for their app to use it")
        }
    }

    /**
     * Tells the room it is moving on, while our radio still holds the old
     * channel key — so this goes out on it — sealed under the old room key, so
     * members who hold it can open it and nobody holding only a radio can.
     */
    private suspend fun announceRotation(
        slot: Int,
        roomId: Int,
        myNodeNum: Int,
        previous: Int,
        generation: Int,
        quiet: Boolean = false,
    ) {
        val sealed = sealFor(
            roomId,
            myNodeNum,
            MeshChatControl(
                version = InviteCodec.VERSION,
                roster_event = RosterEvent(
                    kind = RosterEvent.Kind.KEY_ROTATED,
                    node_num = myNodeNum,
                    generation = generation,
                    quiet = quiet,
                ),
            ),
            generation = previous,
        ) ?: return
        runCatching {
            link.send(
                ToRadio(
                    packet = MeshPacketBuilder.meshPacket(
                        to = MeshConstants.BROADCAST_NODENUM,
                        channel = slot,
                        portNum = PortNum.PRIVATE_APP,
                        payload = MeshChatControl(sealed_message = sealed).encode().let(ByteString::of),
                        hopLimit = mesh.hopLimitForSending(),
                        wantAck = true,
                        priority = MeshPacket.Priority.RELIABLE,
                    ),
                ),
            )
        }.onFailure { Log.w(TAG, "could not announce the rotation", it) }
    }

    /** Applies a rotation somebody else performed. See [TrustRules.rotationAcceptable]. */
    private suspend fun handleKeyRotation(
        packet: MeshPacket,
        rotation: KeyRotation,
        sealedRoomId: Int?,
        sealedGeneration: Int?,
        myNodeNum: Int,
    ) {
        rotationLock(rotation.room_id).withLock {
            handleKeyRotationLocked(packet, rotation, sealedRoomId, sealedGeneration, myNodeNum)
        }
    }

    private suspend fun handleKeyRotationLocked(
        packet: MeshPacket,
        rotation: KeyRotation,
        sealedRoomId: Int?,
        sealedGeneration: Int?,
        myNodeNum: Int,
    ) {
        val roomId = rotation.room_id
        val acceptable = TrustRules.rotationAcceptable(
            sealedUnderRoom = sealedRoomId,
            sealedUnderGeneration = sealedGeneration,
            rotationRoom = roomId,
            rotationGeneration = rotation.generation,
            currentGeneration = roomKeys.generationOf(roomId),
            privatelyToUs = packet.pki_encrypted && packet.to == myNodeNum,
            // Only somebody already in the room may change its locks.
            senderIsMember = memberDao.findEntity(roomId, packet.from) != null,
        )
        if (!acceptable) {
            Log.w(TAG, "key rotation for $roomId from ${packet.from} does not prove it holds the room; ignored")
            return
        }
        val psk = rotation.room_psk.toByteArray()
        val firepitKey = phoneKeys.open(
            rotation.sealed_key.toByteArray(),
            KeyEnvelope.contextOf(roomId, rotation.generation, myNodeNum, rotation.key_hour),
        )?.takeIf { it.size == RoomCipher.KEY_SIZE }
        if (psk.size != RoomCrypto.PSK_SIZE || firepitKey == null) {
            Log.w(TAG, "key rotation for $roomId from ${packet.from} would not open; ignored")
            return
        }

        val slot = ChannelSlotManager.slotOf(mesh.channels.value, roomId) ?: return
        admin.setChannel(channelFor(slot, rotation.room_name, psk, roomId))
        try {
            roomKeys.remember(roomId, HourKey(rotation.key_hour, firepitKey), rotation.generation)
            roomKeyMade.record(roomId, rotation.generation, System.currentTimeMillis())
        } finally {
            firepitKey.fill(0)
        }
        rotation.removed.forEach { memberDao.remove(roomId, it) }
        // Holding the new key ends being left behind, if a notice got here first.
        mesh.refreshRoomKinds()

        if (!rotation.quiet || rotation.removed.isNotEmpty()) noticeInRoom(slot, rotationNotice(rotation.removed.toSet()), roomId)
        Log.i(TAG, "took the new key for room $roomId, generation ${rotation.generation}")
        // Something sealed under the new key, so whoever handed it over knows
        // our app has it, not only our radio.
        runCatching { shareCardWith(roomId) }.onFailure { cause -> Log.w(TAG, "could not confirm the new key", cause) }
    }

    /**
     * Says who is on a build from before hourly keys, rather than leave their
     * messages failing to open without a word. Once per room and sender.
     */
    private suspend fun noticeOutdated(roomId: Int, sender: Int) {
        if (!outdatedNoticed.add(roomId to sender)) return
        Log.w(TAG, "sealed payload for room $roomId from $sender is from a build before hourly keys")
        val slot = ChannelSlotManager.slotOf(mesh.channels.value, roomId) ?: return
        noticeInRoom(slot, outdatedNotice(sender), roomId)
    }

    private fun outdatedNotice(sender: Int): String =
        "${MeshConstants.formatNodeId(sender)} is using an older version of Firepit. " +
            "Their messages can't be opened here until they update."

    private fun rotationNotice(removed: Set<Int>): String = when {
        removed.isEmpty() -> "The room's key was changed. Everyone still here has the new one."
        removed.size == 1 -> "${MeshConstants.formatNodeId(removed.first())} was removed. " +
            "The room's key was changed, so they cannot read anything from now on."
        else -> "${removed.size} people were removed. " +
            "The room's key was changed, so they cannot read anything from now on."
    }

    /**
     * A line in the room that nobody said.
     *
     * Stored like a message so it survives a restart and sits in the history at
     * the moment it happened, with no sender so the UI can tell it apart.
     */
    private suspend fun noticeInRoom(slot: Int, text: String, roomId: Int) {
        val myNodeNum = mesh.myNodeNum.value ?: return
        messageDao.save(
            ChatMessage(
                id = MeshPacketBuilder.randomPacketId(),
                channel = slot,
                fromNodeNum = NOTICE_NODE,
                toNodeNum = BROADCAST_NODE_NUM,
                text = text,
                sentAt = System.currentTimeMillis(),
                status = MessageStatus.RECEIVED,
                isOutgoing = false,
                roomId = roomId,
            ),
            myNodeNum,
        )
    }

    /**
     * Frees a slot and closes the gap so the channels stay consecutive.
     *
     * [slot] names the channel exactly: a Meshtastic channel has no id of its
     * own, so two of them both answer to [roomId] 0, and only the slot says
     * which is meant. Nothing is deleted until every slot that moves has been
     * read back, and the slots are rewritten with history placement held off,
     * so no in-between layout can file one room's history under another.
     */
    suspend fun leaveRoom(roomId: Int, slot: Int? = null, announce: Boolean = true) = history.whileRearranging {
        val channels = ChannelSlotManager.rooms(mesh.channels.value)
        val leaving = (
            slot?.let { index -> channels.firstOrNull { it.index == index && it.id == roomId } }
                ?: channels.firstOrNull { it.id == roomId && roomId != 0 }
            ) ?: throw RoomError.InviteInvalid
        val plan = planTakingOff(leaving.index)
        val myNodeNum = mesh.myNodeNum.value
        if (announce && roomId != 0 && myNodeNum != null && roomKeys.canSeal(roomId)) {
            sendSealed(
                roomId,
                MeshChatControl(
                    version = InviteCodec.VERSION,
                    roster_event = RosterEvent(kind = RosterEvent.Kind.LEFT, node_num = myNodeNum),
                ),
                priority = MeshPacket.Priority.RELIABLE,
            )
        }

        // What sits in the slot unfiled is this channel's, or this room's from
        // before rooms were recorded. A room's own history goes by its id,
        // wherever it sits — including set aside while another radio was on.
        messageDao.deleteUnfiled(leaving.index)
        pinDao.deleteUnfiled(leaving.index)
        if (roomId != 0) {
            messageDao.deleteRoom(roomId)
            pinDao.deleteRoom(roomId)
        }
        plan.writes.forEach { admin.setChannel(it) }
        followSlots(plan.moves)

        Log.i(TAG, "left channel in slot ${leaving.index}")
        if (roomId == 0) return@whileRearranging
        memberDao.deleteRoom(roomId)
        roomKeys.forget(roomId)
        roomKeyMade.forget(roomId)
        roomActivity.forget(roomId)
        handovers.deleteRoom(roomId)
        issuedInvites.entries.removeAll { it.value.roomId == roomId }
        // A share with a room we are no longer in must not come back to life
        // if somebody invites us again.
        if (sharingStore.deadline.value?.roomId == roomId) sharingStore.clear()
    }

    /**
     * Takes every Firepit room off the connected radio and puts back the
     * primary channel it came with, for a radio that is being given away,
     * lent, or retired. What this phone holds — keys, history, rosters — stays,
     * because the rooms go on, on whichever radio carries them next.
     *
     * Highest slot first, so no room has to shift down only to be removed.
     */
    suspend fun removeFirepitFromRadio() {
        mesh.myNodeNum.value ?: throw RoomError.NotConnected
        history.whileRearranging {
            ChannelSlotManager.rooms(mesh.channels.value)
                .filter { it.id != 0 && (it.kind == RoomKind.FIREPIT || it.kind.isStalledRoom) }
                .sortedByDescending { it.index }
                .forEach { room ->
                    val plan = planTakingOff(room.index)
                    plan.writes.forEach { admin.setChannel(it) }
                    followSlots(plan.moves)
                }
        }
        range.keepPublic()
        Log.i(TAG, "took every Firepit room off this radio")
    }

    private fun launchScheduledChangeIfDue(roomId: Int, generation: Int, receivedAt: Long) {
        if (scheduledRotations.putIfAbsent(roomId, Unit) != null) return
        scope.launch {
            try {
                rotationLock(roomId).withLock {
                    if (!scheduledChangeDue(roomId, generation, receivedAt, alreadyRotating = false)) return@withLock
                    rotateRoomLocked(roomId, remove = emptySet(), scheduled = true)
                }
            } catch (cause: RoomError.NotConnected) {
                Log.w(TAG, "scheduled room-key change for $roomId skipped: not connected")
            } catch (cause: Exception) {
                Log.w(TAG, "scheduled room-key change for $roomId failed", cause)
            } finally {
                scheduledRotations.remove(roomId)
            }
        }
    }

    private suspend fun scheduledChangeDue(roomId: Int, evidenceGeneration: Int, evidenceReceivedAt: Long, alreadyRotating: Boolean): Boolean {
        val myNodeNum = mesh.myNodeNum.value ?: return false
        val current = roomKeys.generationOf(roomId)
        if (evidenceGeneration != current) return false
        val existing = roomKeyMade.record(roomId)
        if (existing == null || existing.generation != current) {
            roomKeyMade.record(roomId, current, System.currentTimeMillis())
            return false
        }
        val freshAfter = existing.madeAt + (ScheduledKeyChange.intervalMillis(keyChangePreferences.choice.value) ?: return false)
        if (!roomKeyMade.isDueNow(roomId) && evidenceReceivedAt < freshAfter) return false
        val pending = handovers.forRoom(roomId)
        if (roomKeys.isSuperseded(roomId)) return false
        if (pending.any { memberDao.findEntity(roomId, it.nodeNum)?.lastHeard?.let { heard -> heard >= existing.madeAt } == true }) {
            return false
        }
        return ScheduledKeyChange.shouldChange(
            isMaker = roomKeyMade.isMadeByMe(roomId),
            setting = keyChangePreferences.choice.value,
            keyAgeMillis = if (roomKeyMade.isDueNow(roomId)) {
                ScheduledKeyChange.intervalMillis(keyChangePreferences.choice.value)
            } else {
                System.currentTimeMillis() - existing.madeAt
            },
            hasCurrentGenerationEvidence = true,
            connected = mesh.isConnected.value,
            alreadyRotating = alreadyRotating,
        )
    }

    /**
     * Moves every room [nodeNum] is in to new keys without it, for a radio
     * that is lost or in somebody else's hands. It keeps the channel keys it
     * already holds, and gets none of the ones that come next.
     *
     * Returns how many rooms were moved on.
     */
    suspend fun removeFromAllRooms(nodeNum: Int): Int {
        val rooms = ChannelSlotManager.rooms(mesh.channels.value)
            .filter { it.kind == RoomKind.FIREPIT && memberDao.findEntity(it.id, nodeNum) != null }
        rooms.forEach { room -> rotateRoom(room.id, remove = setOf(nodeNum)) }
        return rooms.size
    }

    /** The slot writes that free one slot and close the gap, and which slots move where. */
    private class TakeOff(val writes: List<Channel>, val moves: List<Pair<Int, Int>>)

    /**
     * Works out, reading every slot that moves, how to free [slot] and close
     * the gap. Changes nothing, so a read that fails stops everything before it
     * starts.
     */
    private suspend fun planTakingOff(slot: Int): TakeOff {
        val channels = mesh.channels.value
        val moves = ChannelSlotManager.slotMovesForLeavingSlot(channels, slot)
        // A write names where a room is going; its key has to be read from
        // where it is now, or every room above the one left would take on the
        // key of the room below it.
        val movingFrom = moves.associate { (from, to) -> to to from }

        // A read that timed out used to write the room back with an empty key,
        // which the firmware takes as "use the primary's" — a published key —
        // while the app went on calling it a sealed room.
        val writes = ChannelSlotManager.writesForLeavingSlot(channels, slot).map { write ->
            val channel = write.channel
            if (channel == null) {
                Channel(index = write.index, role = Channel.Role.DISABLED, settings = ChannelSettings())
            } else {
                // Carry the key across with the room; a slot move must not
                // change what the room is.
                val source = movingFrom[write.index] ?: throw RoomError.RadioUnreadable
                val psk = admin.getChannel(source)?.settings?.psk ?: throw RoomError.RadioUnreadable
                if (channel.kind == RoomKind.FIREPIT && psk.size != RoomCrypto.PSK_SIZE) {
                    throw RoomError.RadioUnreadable
                }
                channelFor(write.index, channel.name, psk.toByteArray(), channel.id)
            }
        }
        return TakeOff(writes, moves)
    }

    /**
     * Moves what is stored against a slot but filed under no room — a
     * Meshtastic channel's history and pins — to where that channel went. A
     * room's are placed by its id instead (see [RoomHistory]).
     */
    private suspend fun followSlots(moves: List<Pair<Int, Int>>) {
        moves.forEach { (from, to) ->
            messageDao.moveUnfiled(from, to)
            pinDao.moveUnfiled(from, to)
        }
    }

    /**
     * What a scanner can check before asking.
     *
     * Only the window: the token is an HMAC under the room's key, and this code
     * no longer carries one. Proving it is the inviter's job, and they do it
     * before granting anything. Checking the window here is worth it anyway —
     * it fails an obviously old photograph on the spot rather than after a
     * round trip.
     */
    private fun verify(invite: Invite, nowMillis: Long) {
        // Node number as well as presence: it is what the grant is matched
        // against later, and zero matches a packet from nobody.
        val inviter = invite.inviter ?: throw RoomError.InviteInvalid
        if (inviter.node_num == 0) throw RoomError.InviteInvalid
        if (invite.secret.size != InviteCodec.INVITE_SECRET_SIZE) throw RoomError.InviteInvalid

        // A tokenless invite never stops working, so a copy kept from a room
        // that has long since emptied would still be worth presenting.
        with(InviteCodec) { if (!invite.isTimeBound()) throw RoomError.InviteExpired }

        val drift = abs(invite.window - RoomCrypto.windowFor(nowMillis))
        if (drift > RoomCrypto.WINDOW_TOLERANCE) throw RoomError.InviteExpired
    }

    /**
     * The Firepit room a slot carries, or null when it is an ordinary
     * Meshtastic channel.
     *
     * Holding the room's key is what makes it ours, and it is the gate on every
     * Firepit-only behaviour: rosters, person cards, receipts and key rotation
     * are all things a stock client knows nothing about. On a shared channel
     * they would be unreadable noise to everyone else on it, and would announce
     * which nodes are running Firepit.
     */
    private fun firepitRoomFor(channel: Int): Int? =
        mesh.roomIdForChannel(channel)?.takeIf { roomKeys.holds(it) }

    private suspend fun handlePacket(packet: MeshPacket) {
        val data = packet.decoded ?: return
        val myNodeNum = mesh.myNodeNum.value ?: return
        if (packet.from == myNodeNum) return

        try {
            // Membership is not inferred from anything unsealed: on a room's slot
            // that is somebody holding a member's radio, not a member. Opening a
            // sealed message under the room's current key is what counts.
            if (data.portnum == PortNum.PRIVATE_APP) {
                handleControl(
                    packet,
                    data.payload.toByteArray(),
                    myNodeNum,
                    // Anyone on a shared channel can put bytes on it under any name.
                    authenticated = packet.pki_encrypted,
                )
            }

            if (data.portnum == PortNum.TEXT_MESSAGE_APP) {
                // Unsealed text is only kept as a direct message that came under
                // PKI; on a room's slot it is dropped. A receipt goes where the
                // message was kept. A reaction shows no ticks, so it gets none.
                if (packet.to == myNodeNum && packet.pki_encrypted && !(data.emoji != 0 && data.reply_id != 0)) {
                    receipts.received(packet.channel, packet.id, peer = packet.from)
                }
            }
        } finally {
            // Anything at all from somebody a new key never reached means they
            // are back in range: try them again rather than leave them talking
            // into a room only the removed member can still read. After the
            // packet, so one that confirms the key is counted first.
            retryHandoversTo(packet.from)
        }
    }

    private suspend fun handleControl(
        packet: MeshPacket,
        payload: ByteArray,
        myNodeNum: Int,
        authenticated: Boolean,
        sealedRoomId: Int? = null,
        sealedGeneration: Int? = null,
    ) {
        val control = runCatching { MeshChatControl.ADAPTER.decode(payload) }.getOrNull() ?: return
        // Identity and roster news only count under the room's current key: an
        // older generation's key is exactly what a removed member still holds.
        val sealedUnderCurrent = sealedRoomId != null && sealedGeneration == roomKeys.generationOf(sealedRoomId)
        control.join_hello?.let { hello -> handleJoinHello(packet, hello, myNodeNum) }

        // Answered only over PKI: a grant is the one message that carries a
        // room's keys, and a channel anyone holds is not where it may arrive.
        control.room_grant?.let { grant ->
            if (packet.pki_encrypted) {
                handleRoomGrant(packet, grant, myNodeNum)
            } else {
                Log.w(TAG, "unencrypted room grant from ${packet.from}; ignored")
            }
        }
        control.roster_event?.let { event ->
            if (sealedRoomId == null || !sealedUnderCurrent) {
                Log.w(TAG, "roster event from ${packet.from} not sealed under the room's current key; ignored")
                return@let
            }
            handleRosterEvent(packet, event, sealedRoomId)
        }
        control.roster_sync?.let { sync ->
            handleRosterSync(packet, sync, myNodeNum, sealedRoomId, sealedUnderCurrent)
        }
        control.sealed_message?.let { sealed -> handleSealed(packet, sealed, myNodeNum) }
        control.sealed_direct?.let { direct -> handleSealedDirect(packet, direct, myNodeNum) }
        control.sealed_direct_refused?.let { refused -> handleDirectRefused(packet, refused, myNodeNum) }

        // Positions, pins and position questions belong to other parts of the
        // app, which judge them against their own rules. They only ever count
        // opened from a room's seal.
        if (sealedRoomId != null && (control.position != null || control.pin != null || control.position_query != null)) {
            _opened.emit(
                OpenedInRoom(
                    packet = packet,
                    roomId = sealedRoomId,
                    sealedUnderCurrent = sealedUnderCurrent,
                    onItsSlot = firepitRoomFor(packet.channel) == sealedRoomId,
                    control = control,
                ),
            )
        }

        control.receipt?.let { receipt ->
            // Only opened from a room's seal. One person's receipts arrive
            // sealed to this phone and are handled with their seal; anything
            // left in the clear could have been written by whoever holds a radio.
            if (!authenticated || sealedRoomId == null) {
                Log.w(TAG, "receipt from ${packet.from} that was not sealed; ignored")
                return@let
            }
            receipts.handle(packet.from, receipt)
        }

        control.key_rotation?.let { rotation ->
            handleKeyRotation(packet, rotation, sealedRoomId, sealedGeneration, myNodeNum)
        }

        control.room_text?.let { room ->
            // Only as a sealed message on the room's own slot: that is where
            // room words live, and nowhere else can show them as the room's.
            if (sealedRoomId == null || firepitRoomFor(packet.channel) != sealedRoomId) {
                Log.w(TAG, "room text from ${packet.from} outside its room; ignored")
                return@let
            }
            mesh.saveSealedText(packet, room.text, room.reply_id.takeIf { it != 0 }, sealedRoomId, room.emoji.takeIf { it != 0 })
            // A reaction shows no ticks, so it gets no receipt.
            if (room.emoji == 0 || room.reply_id == 0) receipts.received(packet.channel, packet.id)
            noteActivity(sealedRoomId)
        }

        control.person_card?.let { card ->
            if (!sealedUnderCurrent) {
                // Unsealed, this is an open invitation to wear someone else's
                // name. PKI alone is not enough either: anyone can encrypt to
                // us, so only somebody holding a room key we share, as it is
                // now, may say who they are.
                Log.w(TAG, "person card from ${packet.from} not sealed under a current room key; ignored")
                return@let
            }
            learnPhoneKey(packet.from, card.phone_key, PhoneKeySource.ANNOUNCED)
            val name = sanitizeMeshText(card.name)
            val tag = sanitizeMeshText(card.tag)
            // A card with no name is somebody who never chose one, or who took
            // theirs back: they are drawn as their radio names itself.
            if (name.isBlank() && tag.isBlank()) {
                personCardDao.forget(packet.from)
                return@let
            }
            personCardDao.upsert(
                PersonCardEntity(
                    nodeNum = packet.from,
                    name = name,
                    tag = tag,
                    colourSlot = card.colour_slot_plus_one.takeIf { it > 0 }?.minus(1),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            Log.i(TAG, "person card from ${packet.from}")
        }
    }

    /**
     * Open it and treat what is inside as if it had arrived in the clear. The
     * key is the room's, so a radio relaying this cannot do the same, and
     * opening it is itself proof the sender holds the key.
     */
    private suspend fun handleSealed(packet: MeshPacket, sealed: SealedMessage, myNodeNum: Int) {
        val privatelyToUs = packet.pki_encrypted && packet.to == myNodeNum
        val currentForRoom = roomKeys.generationOf(sealed.room_id)
        if (!TrustRules.sealedPlacementOk(firepitRoomFor(packet.channel), sealed.room_id, privatelyToUs)) {
            Log.w(TAG, "sealed payload for room ${sealed.room_id} arrived on channel ${packet.channel}; ignored")
            return
        }

        // The generation it was sealed under, not the one we have moved on to:
        // a packet sealed just before a rotation can arrive just after it.
        val generation = sealed.generation.takeIf { it > 0 } ?: RoomKeyStore.FIRST
        val payload = sealed.ciphertext.toByteArray()
        val plain = when (val opening = roomKeys.open(sealed.room_id, generation, packet.from, payload)) {
            is Opening.Read -> opening.plain
            Opening.NoKey -> return
            is Opening.OutOfHours -> {
                Log.w(
                    TAG,
                    "sealed payload for room ${sealed.room_id} from ${packet.from} was sealed in hour " +
                        "${opening.hour}, and it is ${opening.now} here; ignored (an old recording, or a clock out)",
                )
                return
            }
            Opening.Replayed -> {
                Log.w(TAG, "sealed payload for room ${sealed.room_id} from ${packet.from} was opened before; ignored")
                return
            }
            Opening.Outdated -> {
                // Only on the room's own slot and from somebody in it: the version
                // byte proves nothing, and anyone can address a packet to us.
                val inRoom = firepitRoomFor(packet.channel) == sealed.room_id &&
                    memberDao.findEntity(sealed.room_id, packet.from) != null
                if (inRoom) noticeOutdated(sealed.room_id, packet.from)
                return
            }
            Opening.Unreadable -> {
                Log.w(TAG, "sealed payload for room ${sealed.room_id} would not open")
                return
            }
        }

        // Holding the current key is what makes somebody a member. An older
        // generation's key is exactly what a removed member still has, so it
        // reads history but vouches for nobody.
        val sealedUnderCurrent = generation == roomKeys.generationOf(sealed.room_id)
        val receivedAt = System.currentTimeMillis()
        if (sealedUnderCurrent) {
            memberDao.record(sealed.room_id, packet.from, receivedAt)
            memberDao.recordOpenedGeneration(sealed.room_id, packet.from, generation)
        }
        settleHandover(sealed.room_id, packet.from, generation)
        handleControl(
            packet,
            plain,
            myNodeNum,
            authenticated = true,
            sealedRoomId = sealed.room_id,
            sealedGeneration = generation,
        )
        val stillMember = memberDao.findEntity(sealed.room_id, packet.from) != null
        if (sealedUnderCurrent && stillMember) launchScheduledChangeIfDue(sealed.room_id, generation, receivedAt)
    }

    /**
     * One person's words or receipts, sealed from their phone to ours.
     *
     * Only privately to us, and only from somebody whose phone key we hold:
     * opening it proves their phone sealed it, which no radio in between —
     * nor anyone holding theirs — can do.
     */
    private suspend fun handleSealedDirect(packet: MeshPacket, direct: SealedDirect, myNodeNum: Int) {
        if (!(packet.pki_encrypted && packet.to == myNodeNum)) {
            Log.w(TAG, "sealed direct message from ${packet.from} did not come privately to us; ignored")
            return
        }
        val peerKey = mesh.phoneKeyOf(packet.from)
        val sealed = direct.ciphertext.toByteArray()
        val opened = openDirect(packet.from, myNodeNum, peerKey, sealed)
        val plain = when (opened) {
            is DirectOpening.Read -> opened.opening.plain
            DirectOpening.NoPeerKey -> {
                Log.w(TAG, "sealed direct message from ${packet.from} arrived before their phone key was known")
                refuseDirect(packet)
                return
            }
            DirectOpening.Replayed -> {
                Log.w(TAG, "sealed direct message from ${packet.from} was opened before; ignored")
                return
            }
            DirectOpening.OutOfHours -> {
                Log.w(TAG, "sealed direct message from ${packet.from} was outside the open hour window; ignored")
                return
            }
            DirectOpening.Unreadable -> {
                Log.w(TAG, "sealed direct message from ${packet.from} would not open (key known: ${peerKey != null})")
                refuseDirect(packet)
                return
            }
        }
        val inner = runCatching { MeshChatControl.ADAPTER.decode(plain) }.getOrNull() ?: return
        inner.room_text?.let { words ->
            mesh.saveSealedDirectText(packet, words.text, words.reply_id.takeIf { it != 0 }, words.emoji.takeIf { it != 0 })
            if (words.emoji == 0 || words.reply_id == 0) receipts.received(packet.channel, packet.id, peer = packet.from)
        }
        inner.receipt?.let { receipt -> receipts.handle(packet.from, receipt) }
    }

    private suspend fun openDirect(sender: Int, myNodeNum: Int, peerKey: ByteArray?, sealed: ByteArray): DirectOpening {
        if (peerKey == null) return DirectOpening.NoPeerKey
        val tag = DirectSeal.hourTagOf(sealed)
        if (tag != null && !roomKeys.directTagInWindow(tag)) return DirectOpening.OutOfHours
        val rooms = tag?.let(roomKeys::directSecretsForOpening).orEmpty()
        val opening = try {
            phoneKeys.openDirect(peerKey, sealed, DirectSeal.contextOf(sender, myNodeNum), rooms)
        } finally {
            rooms.forEach { it.key.fill(0) }
        } ?: return DirectOpening.Unreadable
        if (!roomKeys.firstDirectSight(sender, opening)) return DirectOpening.Replayed
        opening.room?.let { memberDao.recordOpenedGeneration(it.roomId, sender, it.generation) }
        return DirectOpening.Read(opening)
    }

    /**
     * Tells the sender their sealed message did not open here, so they are not
     * left believing it was read. Most often this phone has not learned their
     * phone key yet. At most once a minute per sender: the reply carries
     * nothing, but it is still a transmission.
     */
    private suspend fun refuseDirect(packet: MeshPacket) {
        val now = System.currentTimeMillis()
        val last = refusedAt[packet.from]
        if (last != null && now - last < REFUSAL_GAP.inWholeMilliseconds) return
        refusedAt[packet.from] = now
        val radioKey = mesh.publicKeyOf(packet.from) ?: return
        runCatching {
            link.send(
                ToRadio(
                    packet = MeshPacketBuilder.meshPacket(
                        to = packet.from,
                        channel = 0,
                        portNum = PortNum.PRIVATE_APP,
                        payload = MeshChatControl(
                            version = InviteCodec.VERSION,
                            sealed_direct_refused = SealedDirectRefused(request_id = packet.id),
                        ).encode().let(ByteString::of),
                        hopLimit = mesh.hopLimitForSending(),
                        pkiEncrypted = true,
                        publicKey = radioKey,
                        priority = MeshPacket.Priority.BACKGROUND,
                    ),
                ),
            )
        }.onFailure { cause -> Log.w(TAG, "could not tell ${packet.from} their message did not open", cause) }
    }

    /**
     * Somebody says a sealed message of ours did not open on their phone. The
     * message is marked so, rather than left reading as delivered, and our card
     * goes again to the rooms we share with them so their phone learns our key.
     */
    private suspend fun handleDirectRefused(packet: MeshPacket, refused: SealedDirectRefused, myNodeNum: Int) {
        if (!(packet.pki_encrypted && packet.to == myNodeNum)) return
        if (!mesh.handleDirectRefusal(refused.request_id, by = packet.from)) return
        val shared = ChannelSlotManager.rooms(mesh.channels.value)
            .filter { roomKeys.canSeal(it.id) && memberDao.findEntity(it.id, packet.from) != null }
        if (shared.isNotEmpty()) shareCard(latestCard ?: NO_NAME, shared)
    }

    /**
     * Somebody is asking to be let in.
     *
     * Every check here is about narrowing what a photographed code is worth.
     * Nothing is handed over: the keys only move once a person says so, which
     * is the difference between a stolen code being a way in and being a
     * request somebody declines.
     */
    private suspend fun handleJoinHello(packet: MeshPacket, hello: JoinHello, myNodeNum: Int) {
        val from = packet.from
        Log.i(TAG, "join hello from $from for invite ${hello.invite_id}")
        if (!admitAttempt(from)) {
            Log.w(TAG, "too many join attempts from $from; ignored")
            return
        }

        // Only a hello the firmware decrypted with the very key it names. The
        // grant goes to that key, so a claimed one would let a spoofed hello
        // point the room's keys at somebody else.
        val bound = TrustRules.helloIsBound(
            pkiEncrypted = packet.pki_encrypted,
            addressedToUs = packet.to == myNodeNum,
            decryptedWith = packet.public_key,
            claimed = hello.joiner_key,
        )
        if (!bound) {
            Log.w(TAG, "join hello from $from was not encrypted under the key it names; ignored")
            return
        }

        val issued = issuedInvites[hello.invite_id] ?: run {
            Log.w(TAG, "join hello for unknown invite ${hello.invite_id}")
            return
        }
        if (issued.usedBy != null) {
            Log.w(TAG, "invite ${hello.invite_id} was already used by ${issued.usedBy}; ignored")
            return
        }

        // A code is shown to somebody standing in front of you. Anything that
        // needed relaying was read somewhere you cannot see.
        if (!PacketOrigin.arrivedDirectly(packet)) {
            val hops = PacketOrigin.hopsTravelled(packet)
            Log.w(TAG, "join hello from $from claims $hops hops; not from the code in our hand")
            return
        }

        val proved = RoomCrypto.matchesRecentToken(
            inviteKey = issued.inviteKey,
            inviterNodeNum = myNodeNum,
            token = hello.token.toByteArray(),
            nowMillis = System.currentTimeMillis(),
        )
        if (!proved) {
            Log.w(TAG, "join hello from $from failed token check")
            return
        }

        val phoneKey = hello.phone_key.takeIf { KeyEnvelope.isValidPublicKey(it.toByteArray()) } ?: run {
            Log.w(TAG, "join hello from $from carried no usable phone key to seal the room key to")
            return
        }

        val waiting = _pendingJoins.value.firstOrNull { it.nodeNum == from }
        if (!TrustRules.mayReplacePending(waiting?.joinerKey, waiting?.phoneKey, hello.joiner_key, phoneKey)) {
            Log.w(TAG, "a second hello from $from named different keys; kept the first")
            return
        }

        _pendingJoins.update { pending ->
            pending.filterNot { it.nodeNum == from } +
                PendingJoin(
                    nodeNum = from,
                    roomId = issued.roomId,
                    inviteId = hello.invite_id,
                    generation = hello.generation,
                    joinerKey = hello.joiner_key,
                    phoneKey = phoneKey,
                    askedAt = waiting?.askedAt ?: System.currentTimeMillis(),
                )
        }
        Log.i(TAG, "$from is asking to join room ${issued.roomId}; waiting on an answer")
    }

    /**
     * Lets [nodeNum] in, handing over the room's keys.
     *
     * The room's own key is sealed to the phone key that came inside their
     * hello, so the radios between us carry it without being able to read it.
     * The radio layer goes to the key the firmware decrypted that hello with,
     * which its own NodeDB therefore already holds: nothing needs adding.
     */
    suspend fun approveJoin(nodeNum: Int) {
        val entered = approvingMutex.withLock { approvingJoins.add(nodeNum) }
        if (!entered) return
        try {
            approveJoinOnce(nodeNum)
        } finally {
            approvingMutex.withLock { approvingJoins.remove(nodeNum) }
        }
    }

    private suspend fun approveJoinOnce(nodeNum: Int) {
        val myNodeNum = mesh.myNodeNum.value ?: throw RoomError.NotConnected
        val request = _pendingJoins.value.firstOrNull { it.nodeNum == nodeNum } ?: return
        val issued = issuedInvites[request.inviteId] ?: run {
            clearPending(nodeNum)
            runCatching {
                sendGrant(
                    to = nodeNum,
                    key = request.joinerKey,
                    grant = RoomGrant(
                        answer = RoomGrant.Answer.DECLINED,
                        invite_id = request.inviteId,
                        room_id = request.roomId,
                    ),
                )
            }.onFailure { cause -> Log.w(TAG, "could not tell $nodeNum their invite expired", cause) }
            Log.w(TAG, "invite ${request.inviteId} expired before approval; not granting")
            throw RoomError.InviteExpired
        }
        val room = ChannelSlotManager.findByRoomId(mesh.channels.value, request.roomId)
            ?: throw RoomError.InviteInvalid
        val psk = admin.getChannel(room.index)?.settings?.psk ?: throw RoomError.NotConnected
        // This hour's key: what they read starts when they are let in.
        val firepitKey = roomKeys.currentKey(request.roomId) ?: roomKeys.generate(request.roomId)
        val generation = roomKeys.generationOf(request.roomId)
        val hedge = KeyEnvelope.inviteHedge(issued.secret, request.roomId, request.inviteId)

        try {
            sendGrant(
                to = nodeNum,
                key = request.joinerKey,
                grant = RoomGrant(
                    answer = RoomGrant.Answer.GRANTED,
                    invite_id = request.inviteId,
                    room_id = request.roomId,
                    room_name = room.name,
                    room_psk = psk,
                    // The generation in use now, not the one the code was drawn
                    // under: it is the key that opens what the room seals next.
                    generation = generation,
                    sealed_key = KeyEnvelope.seal(
                        request.phoneKey.toByteArray(),
                        firepitKey.key,
                        KeyEnvelope.contextOf(request.roomId, generation, nodeNum, firepitKey.hour),
                        hedge,
                    ).toByteString(),
                    key_hour = firepitKey.hour,
                ),
            )
        } finally {
            firepitKey.key.fill(0)
            hedge.fill(0)
        }

        // Spent: a code photographed over somebody's shoulder stops being worth
        // presenting the moment the person it was shown to is let in.
        issuedInvites.remove(request.inviteId)?.wipe()
        clearPending(nodeNum)

        memberDao.record(request.roomId, nodeNum, System.currentTimeMillis(), invitedBy = myNodeNum)
        // This phone just checked this one in front of them, so it may replace
        // whatever we held for that node before and is marked in-person.
        learnPhoneKey(nodeNum, request.phoneKey, PhoneKeySource.IN_PERSON)
        noteActivity(request.roomId)
        Log.i(TAG, "let $nodeNum into room ${request.roomId}")

        announceJoined(request.roomId, nodeNum, myNodeNum, generation, request.phoneKey)
        sendRosterTo(nodeNum, request.roomId)
        shareCardWith(request.roomId)
    }

    /** Turns somebody away, so they are told rather than left waiting. */
    suspend fun declineJoin(nodeNum: Int) {
        val request = _pendingJoins.value.firstOrNull { it.nodeNum == nodeNum } ?: return
        clearPending(nodeNum)
        runCatching {
            sendGrant(
                to = nodeNum,
                key = request.joinerKey,
                grant = RoomGrant(
                    answer = RoomGrant.Answer.DECLINED,
                    invite_id = request.inviteId,
                    room_id = request.roomId,
                ),
            )
        }.onFailure { cause -> Log.w(TAG, "could not tell $nodeNum they were turned away", cause) }
        Log.i(TAG, "turned $nodeNum away from room ${request.roomId}")
    }

    private suspend fun sendGrant(to: Int, key: ByteString, grant: RoomGrant) {
        link.send(
            ToRadio(
                packet = MeshPacketBuilder.meshPacket(
                    to = to,
                    channel = 0,
                    portNum = PortNum.PRIVATE_APP,
                    payload = MeshChatControl(
                        version = InviteCodec.VERSION,
                        room_grant = grant,
                    ).encode().let(ByteString::of),
                    pkiEncrypted = true,
                    publicKey = key,
                    wantAck = true,
                ),
            ),
        )
    }

    private fun clearPending(nodeNum: Int) {
        _pendingJoins.update { pending -> pending.filterNot { it.nodeNum == nodeNum } }
    }

    /**
     * Tells the room who was vouched for, so every roster shows the same chain,
     * and where to seal them a future key. Sealed under the room's own key: on
     * the channel alone, anybody holding a member's radio could vouch too.
     */
    private suspend fun announceJoined(
        roomId: Int,
        joiner: Int,
        myNodeNum: Int,
        generation: Int,
        phoneKey: ByteString,
    ) {
        val slot = ChannelSlotManager.findByRoomId(mesh.channels.value, roomId)?.index ?: return
        val sealed = sealFor(
            roomId,
            myNodeNum,
            MeshChatControl(
                version = InviteCodec.VERSION,
                roster_event = RosterEvent(
                    kind = RosterEvent.Kind.JOINED,
                    node_num = joiner,
                    invited_by = myNodeNum,
                    generation = generation,
                    phone_key = phoneKey,
                ),
            ),
        ) ?: return
        link.send(
            ToRadio(
                packet = MeshPacketBuilder.meshPacket(
                    to = MeshConstants.BROADCAST_NODENUM,
                    channel = slot,
                    portNum = PortNum.PRIVATE_APP,
                    payload = MeshChatControl(sealed_message = sealed).encode().let(ByteString::of),
                    hopLimit = mesh.hopLimitForSending(),
                    wantAck = true,
                    priority = MeshPacket.Priority.BACKGROUND,
                ),
            ),
        )
    }

    /**
     * The answer to our own request.
     *
     * Only believed from the node we actually asked, and only while we are
     * still waiting: an unsolicited grant is somebody trying to put a room on
     * our radio that we never asked for.
     */
    private suspend fun handleRoomGrant(packet: MeshPacket, grant: RoomGrant, myNodeNum: Int) {
        val awaited = _awaiting.value ?: run {
            Log.w(TAG, "grant from ${packet.from} for a room we did not ask about; ignored")
            return
        }
        if (grant.invite_id != awaited.inviteId || grant.room_id != awaited.roomId) {
            Log.w(TAG, "grant from ${packet.from} does not answer what we asked; ignored")
            return
        }

        // Both ids are in the code, so photographing it is enough to name them.
        // Answering as the node we scanned is not: the hello seeded that node's
        // key, so only its holder can produce a packet that decrypts as them.
        if (packet.from != awaited.inviter) {
            Log.w(TAG, "grant for room ${grant.room_id} came from ${packet.from}, not ${awaited.inviter}; ignored")
            return
        }

        if (grant.answer == RoomGrant.Answer.DECLINED) {
            _awaiting.value = awaited.copy(declined = true)
            Log.i(TAG, "we were turned away from room ${grant.room_id}")
            return
        }

        rotationLock(grant.room_id).withLock {
        val generation = grant.generation.takeIf { it > 0 } ?: RoomKeyStore.FIRST
        val held = ChannelSlotManager.findByRoomId(mesh.channels.value, grant.room_id)
        val acceptable = TrustRules.mayTakeGrant(
            alreadyHeld = held != null,
            senderIsMember = memberDao.findEntity(grant.room_id, packet.from) != null,
            grantGeneration = generation,
            currentGeneration = roomKeys.generationOf(grant.room_id),
            awaitingScannedInvite = true,
        )
        if (!acceptable) {
            _awaiting.value = null
            Log.w(TAG, "grant for room ${grant.room_id}, which we already hold, would not move it forward; ignored")
            return
        }

        val psk = grant.room_psk.takeIf { it.size == RoomCrypto.PSK_SIZE } ?: run {
            Log.w(TAG, "grant for room ${grant.room_id} carried no usable channel key; ignored")
            return
        }
        val hedge = awaited.inviteSecret.toByteArray().takeIf { it.size == InviteCodec.INVITE_SECRET_SIZE }?.let {
            KeyEnvelope.inviteHedge(it, grant.room_id, grant.invite_id)
        } ?: run {
            Log.w(TAG, "grant for room ${grant.room_id} has no in-person invite secret; ignored")
            return
        }
        val firepitKey = phoneKeys.open(
            grant.sealed_key.toByteArray(),
            KeyEnvelope.contextOf(grant.room_id, generation, myNodeNum, grant.key_hour),
            hedge,
        )?.takeIf { it.size == RoomCipher.KEY_SIZE } ?: run {
            hedge.fill(0)
            Log.w(TAG, "grant for room ${grant.room_id} carried no sealing key we could open; ignored")
            return
        }
        hedge.fill(0)

        val slot = held?.index
            ?: ChannelSlotManager.nextFreeSlot(mesh.channels.value)
            ?: run {
                Log.w(TAG, "no free slot for room ${grant.room_id}")
                return
            }

        // Before the channel write, so the slot is never briefly taken for an
        // ordinary Meshtastic one.
        try {
            roomKeys.remember(grant.room_id, HourKey(grant.key_hour, firepitKey), generation)
        } finally {
            firepitKey.fill(0)
        }
        admin.setChannel(
            channelFor(
                index = slot,
                name = grant.room_name,
                psk = psk.toByteArray(),
                roomId = grant.room_id,
            ),
        )

        val now = System.currentTimeMillis()
        mesh.myNodeNum.value?.let { me ->
            val invitedBy = packet.from.takeUnless { roomKeyMade.isMadeByMe(grant.room_id) }
            memberDao.record(grant.room_id, me, now, invitedBy = invitedBy)
        }
        memberDao.record(grant.room_id, packet.from, now, invitedBy = packet.from)
        roomActivity.recordActivity(grant.room_id, now)
        roomKeyMade.record(grant.room_id, generation, now)
        roomKeyMade.clearDue(grant.room_id)
        // Holding the key the room moved to ends being left behind in it.
        mesh.refreshRoomKinds()

        _awaiting.value = null
        Log.i(TAG, "let into room ${grant.room_id} in slot $slot")
        shareCardWith(grant.room_id)
        }
    }

    /**
     * Caps how often one node may ask.
     *
     * Each attempt costs a handful of HMACs, and a stranger who cannot pass the
     * token check has no reason to keep trying.
     */
    private fun admitAttempt(nodeNum: Int): Boolean {
        val now = System.currentTimeMillis()
        val recent = joinAttempts.getOrDefault(nodeNum, emptyList())
            .filter { now - it < ATTEMPT_WINDOW_MS }
        if (recent.size >= MAX_ATTEMPTS) {
            joinAttempts[nodeNum] = recent
            return false
        }
        joinAttempts[nodeNum] = recent + now
        return true
    }

    /**
     * Hands a fresh joiner the roster, so they do not have to wait for every
     * member to speak before seeing who is around.
     *
     * Directed at the joiner rather than answering a broadcast question: on
     * LoRa, "who is here?" answered by everyone is a storm on every join.
     */
    private suspend fun sendRosterTo(joiner: Int, roomId: Int) {
        val known = memberDao.observeRoom(roomId).first()
            .filter { it.nodeNum != joiner }
            .sortedByDescending { it.lastHeard ?: 0 }
        if (known.isEmpty()) return

        val entries = known.take(MAX_ROSTER_ENTRIES).map { member ->
            RosterEntry(node_num = member.nodeNum, invited_by = member.invitedBy ?: 0)
        }
        val sync = MeshChatControl(
            version = InviteCodec.VERSION,
            roster_sync = RosterSync(
                room_id = roomId,
                entries = entries,
                truncated = known.size > entries.size,
            ),
        )
        // Under the key the grant just handed over, so the two radios carrying
        // it learn nothing about who is in the room.
        val myNodeNum = mesh.myNodeNum.value ?: return
        val sealed = sealFor(roomId, myNodeNum, sync) ?: return

        val publicKey = mesh.publicKeyOf(joiner) ?: run {
            Log.w(TAG, "no public key for $joiner yet; skipping roster sync")
            return
        }
        link.send(
            ToRadio(
                packet = MeshPacketBuilder.meshPacket(
                    to = joiner,
                    channel = 0,
                    portNum = PortNum.PRIVATE_APP,
                    payload = MeshChatControl(sealed_message = sealed).encode().let(ByteString::of),
                    wantAck = true,
                    pkiEncrypted = true,
                    publicKey = publicKey,
                    priority = MeshPacket.Priority.BACKGROUND,
                ),
            ),
        )
        Log.i(TAG, "sent ${entries.size} roster entries to $joiner")
    }

    /**
     * Somebody's view of the room. Stored without a last-heard time, because we
     * have not heard these members ourselves — only been told about them.
     *
     * Only the inviter who let us in may tell us, only privately, and only
     * while they are still a member. Anyone else naming a room id is somebody
     * trying to put themselves on the roster, which is the list new keys are
     * handed to — and an inviter since removed is exactly such a somebody.
     */
    private suspend fun handleRosterSync(
        packet: MeshPacket,
        sync: RosterSync,
        myNodeNum: Int,
        sealedRoomId: Int?,
        sealedUnderCurrent: Boolean,
    ) {
        val roomId = sync.room_id.takeIf { it != 0 } ?: return
        if (ChannelSlotManager.findByRoomId(mesh.channels.value, roomId) == null) return
        val acceptable = TrustRules.rosterSyncAcceptable(
            privatelyToUs = packet.pki_encrypted && packet.to == myNodeNum,
            sender = packet.from,
            ourInviter = memberDao.findEntity(roomId, myNodeNum)?.invitedBy,
            senderIsMember = memberDao.findEntity(roomId, packet.from) != null,
            sealedUnderCurrent = sealedUnderCurrent && sealedRoomId == roomId,
        )
        if (!acceptable) {
            Log.w(TAG, "roster sync for $roomId from ${packet.from}, who did not let us in; ignored")
            return
        }

        val now = System.currentTimeMillis()
        val me = mesh.myNodeNum.value
        sync.entries
            .filter { it.node_num != 0 && it.node_num != me }
            .forEach { entry ->
                memberDao.recordReported(roomId, entry.node_num, now, entry.invited_by.takeIf { it != 0 })
            }
        Log.i(TAG, "roster sync from ${packet.from}: ${sync.entries.size} entries, truncated=${sync.truncated}")
    }

    /**
     * The inviter is vouching for somebody. Only believed sealed under the
     * room's own key, on the room's own channel, with the sender claiming
     * themselves as the inviter: anyone in the room could otherwise fabricate a
     * trust chain, and anyone holding a member's radio could join the roster.
     */
    private suspend fun handleRosterEvent(packet: MeshPacket, event: RosterEvent, sealedRoomId: Int) {
        val roomId = firepitRoomFor(packet.channel)?.takeIf { it == sealedRoomId } ?: return
        if (event.kind == RosterEvent.Kind.KEY_ROTATED) {
            handleRotationNotice(packet, event, roomId)
            return
        }
        if (event.kind == RosterEvent.Kind.LEFT) {
            handleLeftNotice(packet, event, roomId)
            return
        }
        if (event.kind != RosterEvent.Kind.JOINED) return
        if (event.invited_by != packet.from) {
            Log.w(TAG, "roster event from ${packet.from} claims inviter ${event.invited_by}; ignored")
            return
        }
        // One naming this phone says nothing we do not know, and must never
        // change who let us in.
        val joiner = event.node_num.takeIf { it != 0 && it != mesh.myNodeNum.value } ?: return
        val isNews = memberDao.findEntity(roomId, joiner) == null
        // Vouched by the sender. It may replace only a key not checked in person.
        val learned = learnPhoneKey(joiner, event.phone_key, PhoneKeySource.VOUCHED)
        memberDao.record(
            roomId,
            joiner,
            System.currentTimeMillis(),
            invitedBy = event.invited_by.takeIf { isNews || learned.replaced },
        )
        noteActivity(roomId)
        // Only the inviter has introduced themselves so far. Without this the
        // newcomer sees radio names for everyone else already in the room.
        if (isNews) greet(roomId)
    }

    private suspend fun handleLeftNotice(packet: MeshPacket, event: RosterEvent, roomId: Int) {
        if (event.node_num != packet.from) {
            Log.w(TAG, "left event from ${packet.from} names ${event.node_num}; ignored")
            return
        }
        val leaver = event.node_num.takeIf { it != 0 } ?: return
        if (memberDao.findEntity(roomId, leaver) == null) return
        memberDao.remove(roomId, leaver)
        handovers.delete(roomId, leaver)
        sentHandoverGenerations.remove(roomId to leaver)
        val slot = ChannelSlotManager.slotOf(mesh.channels.value, roomId) ?: return
        noticeInRoom(slot, "${leftName(leaver)} left the room", roomId)
        val myNodeNum = mesh.myNodeNum.value
        if (
            myNodeNum != null &&
            keyChangePreferences.choice.value != RoomKeyChange.NEVER &&
            roomKeyMade.isMadeByMe(roomId)
        ) {
            roomKeyMade.markDue(roomId, roomKeys.generationOf(roomId))
        }
    }

    private suspend fun leftName(nodeNum: Int): String =
        personCardDao.find(nodeNum)?.name?.takeIf { it.isNotBlank() } ?: MeshConstants.formatNodeId(nodeNum)

    /**
     * A member says the room has moved to a new key.
     *
     * It arrives before our own copy of that key, or instead of it when ours
     * never reaches us. Either way nothing more is said under the old key:
     * the only other people holding it are whoever was removed. The room is
     * marked, sending in it stops, and a line says why. Our own copy of the
     * key, if it comes, clears the mark.
     */
    private suspend fun handleRotationNotice(packet: MeshPacket, event: RosterEvent, roomId: Int) {
        val current = roomKeys.generationOf(roomId)
        val acceptable = TrustRules.rotationNoticeAcceptable(
            // Only called for events sealed under the current key; see handleControl.
            sealedUnderCurrent = true,
            senderIsMember = memberDao.findEntity(roomId, packet.from) != null,
            noticeGeneration = event.generation,
            currentGeneration = current,
        )
        if (!acceptable) {
            Log.w(TAG, "rotation notice for $roomId from ${packet.from} does not move it forward; ignored")
            return
        }
        roomKeys.markSuperseded(roomId, event.generation)
        mesh.refreshRoomKinds()
        val slot = ChannelSlotManager.slotOf(mesh.channels.value, roomId) ?: return
        if (!event.quiet) {
            noticeInRoom(
                slot,
                "This room moved to a new key. Nothing more will be sent here until yours arrives; " +
                    "if it doesn't, ask a member to invite you again.",
                roomId,
            )
        }
        Log.i(TAG, "room $roomId moved on to generation ${event.generation}; waiting for our key")
    }

    private fun forgetStaleInvites(nowMillis: Long) {
        issuedInvites.forEach { (id, issued) ->
            if (nowMillis - issued.issuedAt > INVITE_LEDGER_TTL_MS && issuedInvites.remove(id, issued)) {
                issued.wipe()
            }
        }
    }

    private fun channelFor(index: Int, name: String, psk: ByteArray, roomId: Int) = Channel(
        index = index,
        role = Channel.Role.SECONDARY,
        settings = ChannelSettings(
            name = name,
            psk = psk.toByteString(),
            id = roomId,
            // Firepit never bridges rooms to MQTT.
            uplink_enabled = false,
            downlink_enabled = false,
            // Joining a room is not consent to be followed by it. Sharing is
            // turned on per room, for a chosen length of time.
            module_settings = ModuleSettings(position_precision = PositionPrecision.DISABLED),
        ),
    )

    private companion object {
        /** No real node has zero, so it marks a line the room itself wrote. */
        const val NOTICE_NODE = 0

        const val TAG = "FirepitRooms"

        /** Curve25519 public key length; anything else cannot encrypt to a node. */
        const val PUBLIC_KEY_SIZE = 32

        /** What a PKI direct message leaves for the payload once the firmware adds its tag and nonce. */
        const val PKI_PAYLOAD_BUDGET = MeshConstants.DATA_PAYLOAD_LEN - MeshConstants.PKC_OVERHEAD
        const val MAX_HANDOVER_GENERATION_RANGE = 8

        /** Enough for a fumbled scan, not enough to grind the token check. */
        const val MAX_ATTEMPTS = 5
        const val ATTEMPT_WINDOW_MS = 60_000L

        /** Window the room's replies to one join are scattered across. */
        val GREETING_SPREAD = 30.seconds

        /** Past this, a join hello is too late to be tied to the invite it used. */
        const val INVITE_LEDGER_TTL_MS = 10 * 60 * 1000L

        /**
         * Roster entries per sync. Each costs up to 14 bytes, and the sync is
         * sealed inside a PKI direct message, which leaves 221 bytes less the
         * envelope. ProtocolContractTest checks the full sync fits.
         */
        const val MAX_ROSTER_ENTRIES = 10

        /**
         * How long to wait for a member's own ack of a new key. A direct
         * message is retried by the firmware three times, and the ack crosses
         * the mesh back again.
         */
        val HANDOVER_ACK_TIMEOUT = 45.seconds

        /** Least time between tries at handing a missed key to somebody heard again. */
        val HANDOVER_RETRY = 10.minutes

        /** Gap between handing successive members a new key, so they do not all transmit at once. */
        val HANDOVER_SPACING = 2.seconds

        /**
         * Times a member's radio may take a key without their app being heard
         * to use it, before it is only sent again on proof the app lacks it.
         */
        const val MAX_UNCONFIRMED_HANDOVERS = 3

        /**
         * How often old hours' keys are looked for and destroyed. Well inside
         * the hour, so a key outlives its use by minutes, not most of an hour.
         */
        val KEY_ERASE_EVERY = 10.minutes

        /** Least time between telling one sender their sealed messages will not open here. */
        val REFUSAL_GAP = 1.minutes

        /** A radio that has not listed its channels by now is not going to. */
        val CHANNELS_TIMEOUT = 30.seconds
    }
}

/** What a rotation managed to hand over, and to whom it did not. */
data class RotationResult(
    val generation: Int,
    val reached: Set<Int>,
    val missed: Set<Int>,
)

/** A payload opened from a room's seal, for the part of the app that owns it. */
data class OpenedInRoom(
    val packet: MeshPacket,
    val roomId: Int,
    /** Sealed under the room's current key, which is what makes the sender a member now. */
    val sealedUnderCurrent: Boolean,
    /** True when it came on the room's own slot rather than privately. */
    val onItsSlot: Boolean,
    val control: MeshChatControl,
)
