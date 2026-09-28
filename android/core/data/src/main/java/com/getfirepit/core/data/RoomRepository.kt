package com.getfirepit.core.data

import android.util.Log
import com.getfirepit.core.crypto.KeyEnvelope
import com.getfirepit.core.crypto.RoomCipher
import com.getfirepit.core.crypto.SealedText
import com.getfirepit.core.crypto.InviteCodec
import com.getfirepit.core.crypto.RoomCrypto
import com.getfirepit.core.database.MapPinDao
import com.getfirepit.core.database.PeerKeyDao
import com.getfirepit.core.database.PeerKeyEntity
import com.getfirepit.core.database.RoomMemberDao
import com.getfirepit.core.database.PersonCardDao
import com.getfirepit.core.database.PersonCardEntity
import com.getfirepit.core.database.observeAll
import com.getfirepit.core.database.observeRoom
import com.getfirepit.core.database.record
import com.getfirepit.core.database.recordReported
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
import com.getfirepit.core.protocol.TrustRules
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
import com.getfirepit.core.transport.RadioLink
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
    @param:ApplicationScope private val scope: CoroutineScope,
) {

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
        val issuedAt: Long,
        /** Set once somebody has been let in on it, which spends it. */
        val usedBy: Int? = null,
    )

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
            roomKeys.keyFor(room.id) != null && nodeNum in memberDao.nodeNumsIn(room.id)
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
     * [control] sealed under one generation of [roomId]'s own key, ready to
     * travel. Null when this phone holds no such key, which means the slot is
     * not a Firepit room.
     */
    private fun sealFor(
        roomId: Int,
        myNodeNum: Int,
        control: MeshChatControl,
        generation: Int = roomKeys.generationOf(roomId),
    ): SealedMessage? {
        val key = roomKeys.keyFor(roomId, generation) ?: return null
        val sealed = SealedText.seal(key, control.encode(), SealedText.contextOf(roomId, myNodeNum))
        return SealedMessage(room_id = roomId, ciphertext = sealed.toByteString(), generation = generation)
    }

    /**
     * Keeps a phone key per [TrustRules.shouldStorePhoneKey]: learned on first
     * sight, replaced only by a join a person approved.
     */
    private suspend fun learnPhoneKey(nodeNum: Int, key: ByteString, vouched: Boolean) {
        if (!KeyEnvelope.isValidPublicKey(key.toByteArray())) return
        val known = peerKeyDao.find(nodeNum)?.phoneKey?.decodeBase64()
        if (!TrustRules.shouldStorePhoneKey(known, key, vouched)) {
            if (known != null && known != key) {
                Log.w(TAG, "a different phone key was offered for $nodeNum; kept the one first seen")
            }
            return
        }
        peerKeyDao.upsert(PeerKeyEntity(nodeNum, key.base64(), System.currentTimeMillis()))
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
    }

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
        memberDao.record(roomId, myNodeNum, System.currentTimeMillis(), invitedBy = myNodeNum)

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
        val room = ChannelSlotManager.findByRoomId(mesh.channels.value, roomId) ?: throw RoomError.InviteInvalid
        val settings = admin.getChannel(room.index)?.settings ?: throw RoomError.NotConnected
        val psk = settings.psk.toByteArray()

        val generation = 1
        val inviteKey = RoomCrypto.inviteKey(psk, roomId, generation)
        val window = RoomCrypto.windowFor(nowMillis)

        // Stable for as long as this room keeps being offered, so a hello that
        // arrives several rotations later still points at the right room. The
        // rotating token, not this id, is what limits a stolen code. A spent id
        // is never reissued: that is what makes one code let one person in.
        val inviteId = issuedInvites.entries
            .firstOrNull { it.value.roomId == roomId && it.value.usedBy == null }
            ?.key
            ?: RoomCrypto.generateRoomId()
        issuedInvites[inviteId] = IssuedInvite(roomId, inviteKey, nowMillis)
        forgetStaleInvites(nowMillis)

        return Invite(
            version = InviteCodec.VERSION,
            room_id = roomId,
            room_name = room.name,
            position_precision = ROOM_POSITION_PRECISION,
            generation = generation,
            inviter = Inviter(node_num = myNodeNum, user = mesh.myUser),
            invite_id = inviteId,
            issued_at = (nowMillis / 1000L).toInt(),
            window = window,
            token = RoomCrypto.token(inviteKey, myNodeNum, window).toByteString(),
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
        )
        askToJoin(invite)
    }

    /** This radio's key, short enough to read aloud to the person being asked. */
    fun ownFingerprint(): String? =
        mesh.myUser?.public_key?.takeIf { it.size == PUBLIC_KEY_SIZE }?.let { KeyFingerprint.of(it.base64()) }

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
     * is really everyone else moving on without them. The new keys go to each
     * remaining member as a direct message encrypted to their node, which is
     * the only part of this that is actually private; the removed member simply
     * never receives one.
     *
     * Members who are offline get nothing and will find the room silent. They
     * are reported back to the caller so they can be invited again rather than
     * left wondering.
     */
    suspend fun rotateRoom(roomId: Int, remove: Set<Int> = emptySet()): RotationResult {
        val myNodeNum = mesh.myNodeNum.value ?: throw RoomError.NotConnected
        val room = ChannelSlotManager.findByRoomId(mesh.channels.value, roomId)
            ?: throw RoomError.InviteInvalid
        // Rotation hands out a new Firepit key over PKI; a standard Meshtastic
        // channel has no such key and no way to receive one.
        if (roomKeys.keyFor(roomId) == null) throw RoomError.NotAFirepitRoom

        val previous = roomKeys.generationOf(roomId)
        val generation = previous + 1
        val psk = RoomCrypto.generatePsk()
        val firepitKey = RoomCipher.generateKey()

        // Our own radio first: if this fails nothing has been given away.
        admin.setChannel(channelFor(room.index, room.name, psk, roomId))
        roomKeys.remember(roomId, firepitKey, generation)

        remove.forEach { memberDao.remove(roomId, it) }

        val handover = Handover(
            roomId = roomId,
            roomName = room.name,
            previous = previous,
            generation = generation,
            psk = psk,
            firepitKey = firepitKey,
            removed = remove.toList(),
        )
        val keeping = memberDao.nodeNumsIn(roomId).filter { it != myNodeNum }
        val reached = keeping.filter { sendRotation(it, myNodeNum, handover) }

        // On the old key, so the people who did not get the new one hear why
        // the room went quiet instead of being left to guess.
        announceRotation(room.index, myNodeNum, generation)

        noticeInRoom(room.index, rotationNotice(remove))
        Log.i(TAG, "rotated room $roomId to generation $generation, ${reached.size}/${keeping.size} reached")
        return RotationResult(generation, reached.toSet(), (keeping - reached.toSet()).toSet())
    }

    /** Everything a rotation hands each remaining member, before it is sealed to them. */
    private class Handover(
        val roomId: Int,
        val roomName: String,
        val previous: Int,
        val generation: Int,
        val psk: ByteArray,
        val firepitKey: ByteArray,
        val removed: List<Int>,
    )

    /**
     * Hands one member the new keys.
     *
     * The new room key is sealed to their phone, so the radio in between cannot
     * read it, and the whole rotation is sealed under the key it replaces, so
     * only somebody who holds the room can move it on. Members whose phone key
     * we never learned cannot be handed one, and count as missed.
     */
    private suspend fun sendRotation(member: Int, myNodeNum: Int, handover: Handover): Boolean {
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

        val rotation = KeyRotation(
            room_id = handover.roomId,
            generation = handover.generation,
            room_psk = handover.psk.toByteString(),
            room_name = handover.roomName,
            removed = handover.removed,
            sealed_key = KeyEnvelope.seal(
                phoneKey,
                handover.firepitKey,
                KeyEnvelope.contextOf(handover.roomId, handover.generation, member),
            ).toByteString(),
        )
        val sealed = sealFor(
            handover.roomId,
            myNodeNum,
            MeshChatControl(version = InviteCodec.VERSION, key_rotation = rotation),
            generation = handover.previous,
        ) ?: return false
        val payload = MeshChatControl(sealed_message = sealed).encode()
        if (payload.size > PKI_PAYLOAD_BUDGET) {
            Log.w(TAG, "rotation for $member is ${payload.size} bytes, too big to send privately")
            return false
        }

        return runCatching {
            link.send(
                ToRadio(
                    packet = MeshPacketBuilder.meshPacket(
                        to = member,
                        channel = 0,
                        portNum = PortNum.PRIVATE_APP,
                        payload = payload.toByteString(),
                        pkiEncrypted = true,
                        publicKey = radioKey,
                        wantAck = true,
                    ),
                ),
            )
        }.onFailure { Log.w(TAG, "could not send the new key to $member", it) }.isSuccess
    }

    private suspend fun announceRotation(slot: Int, myNodeNum: Int, generation: Int) {
        runCatching {
            link.send(
                ToRadio(
                    packet = MeshPacketBuilder.meshPacket(
                        to = MeshConstants.BROADCAST_NODENUM,
                        channel = slot,
                        portNum = PortNum.PRIVATE_APP,
                        payload = MeshChatControl(
                            roster_event = RosterEvent(
                                kind = RosterEvent.Kind.KEY_ROTATED,
                                node_num = myNodeNum,
                                generation = generation,
                            ),
                        ).encode().let(ByteString::of),
                        priority = MeshPacket.Priority.BACKGROUND,
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
            KeyEnvelope.contextOf(roomId, rotation.generation, myNodeNum),
        )?.takeIf { it.size == RoomCipher.KEY_SIZE }
        if (psk.size != RoomCrypto.PSK_SIZE || firepitKey == null) {
            Log.w(TAG, "key rotation for $roomId from ${packet.from} would not open; ignored")
            return
        }

        val slot = ChannelSlotManager.slotOf(mesh.channels.value, roomId) ?: return
        admin.setChannel(channelFor(slot, rotation.room_name, psk, roomId))
        roomKeys.remember(roomId, firepitKey, rotation.generation)
        rotation.removed.forEach { memberDao.remove(roomId, it) }

        noticeInRoom(slot, rotationNotice(rotation.removed.toSet()))
        Log.i(TAG, "took the new key for room $roomId, generation ${rotation.generation}")
    }

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
    private suspend fun noticeInRoom(slot: Int, text: String) {
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
            ),
            myNodeNum,
        )
    }

    /** Frees the room's slot and closes the gap so the rooms stay consecutive. */
    suspend fun leaveRoom(roomId: Int) {
        val channels = mesh.channels.value
        val leavingSlot = ChannelSlotManager.slotOf(channels, roomId)
        val moves = ChannelSlotManager.slotMovesForLeaving(channels, roomId)
        // A write names where a room is going; its key has to be read from
        // where it is now, or every room above the one left would take on the
        // key of the room below it.
        val movingFrom = moves.associate { (from, to) -> to to from }

        // Every slot that moves is read before any is written. A read that
        // timed out used to write the room back with an empty key, which the
        // firmware takes as "use the primary's" — a published key — while the
        // app went on calling it a sealed room.
        val planned = ChannelSlotManager.writesForLeaving(channels, roomId).map { write ->
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
        planned.forEach { admin.setChannel(it) }
        // History and pins are stored per slot, and the firmware makes the
        // remaining rooms shuffle down, so they have to move with them. A pin
        // left on its old slot would refuse every later edit from its owner.
        leavingSlot?.let { slot ->
            messageDao.deleteChannel(slot, BROADCAST_NODE_NUM)
            pinDao.deleteChannel(slot)
        }
        moves.forEach { (from, to) ->
            messageDao.moveChannel(from, to, BROADCAST_NODE_NUM)
            pinDao.moveChannel(from, to)
        }

        Log.i(TAG, "left room $roomId")
        memberDao.deleteRoom(roomId)
        roomKeys.forget(roomId)
        issuedInvites.entries.removeAll { it.value.roomId == roomId }
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
        mesh.roomIdForChannel(channel)?.takeIf { roomKeys.keyFor(it) != null }

    private suspend fun handlePacket(packet: MeshPacket) {
        val data = packet.decoded ?: return
        val myNodeNum = mesh.myNodeNum.value ?: return
        if (packet.from == myNodeNum) return

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
            // message was kept.
            if (packet.to == myNodeNum && packet.pki_encrypted) {
                receipts.received(packet.channel, packet.id, peer = packet.from)
            }
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
        control.roster_sync?.let { sync -> handleRosterSync(packet, sync, myNodeNum) }
        control.sealed_message?.let { sealed -> handleSealed(packet, sealed, myNodeNum) }

        control.receipt?.let { receipt ->
            if (!authenticated) {
                Log.w(TAG, "unauthenticated receipt from ${packet.from}; ignored")
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
            mesh.saveSealedText(packet, room.text, room.reply_id.takeIf { it != 0 })
            receipts.received(packet.channel, packet.id)
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
            learnPhoneKey(packet.from, card.phone_key, vouched = false)
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
        if (!TrustRules.sealedPlacementOk(firepitRoomFor(packet.channel), sealed.room_id, privatelyToUs)) {
            Log.w(TAG, "sealed payload for room ${sealed.room_id} arrived on channel ${packet.channel}; ignored")
            return
        }

        // The generation it was sealed under, not the one we have moved on to:
        // history stays readable across a rotation.
        val generation = sealed.generation.takeIf { it > 0 } ?: RoomKeyStore.FIRST
        val key = roomKeys.keyFor(sealed.room_id, generation) ?: return
        val context = SealedText.contextOf(sealed.room_id, packet.from)
        val plain = SealedText.open(key, sealed.ciphertext.toByteArray(), context) ?: run {
            Log.w(TAG, "sealed payload for room ${sealed.room_id} would not open")
            return
        }

        // Holding the current key is what makes somebody a member. An older
        // generation's key is exactly what a removed member still has, so it
        // reads history but vouches for nobody.
        if (generation == roomKeys.generationOf(sealed.room_id)) {
            memberDao.record(sealed.room_id, packet.from, System.currentTimeMillis())
        }
        handleControl(
            packet,
            plain,
            myNodeNum,
            authenticated = true,
            sealedRoomId = sealed.room_id,
            sealedGeneration = generation,
        )
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
        val myNodeNum = mesh.myNodeNum.value ?: throw RoomError.NotConnected
        val request = _pendingJoins.value.firstOrNull { it.nodeNum == nodeNum } ?: return
        val room = ChannelSlotManager.findByRoomId(mesh.channels.value, request.roomId)
            ?: throw RoomError.InviteInvalid
        val psk = admin.getChannel(room.index)?.settings?.psk ?: throw RoomError.NotConnected
        val firepitKey = roomKeys.keyFor(request.roomId) ?: roomKeys.generate(request.roomId)
        val generation = roomKeys.generationOf(request.roomId)

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
                position_precision = ROOM_POSITION_PRECISION,
                sealed_key = KeyEnvelope.seal(
                    request.phoneKey.toByteArray(),
                    firepitKey,
                    KeyEnvelope.contextOf(request.roomId, generation, nodeNum),
                ).toByteString(),
            ),
        )

        // Spent: a code photographed over somebody's shoulder stops being worth
        // presenting the moment the person it was shown to is let in.
        issuedInvites.computeIfPresent(request.inviteId) { _, issued -> issued.copy(usedBy = nodeNum) }
        clearPending(nodeNum)

        memberDao.record(request.roomId, nodeNum, System.currentTimeMillis(), invitedBy = myNodeNum)
        // A person just checked this one in front of them, so it may replace
        // whatever we held for that node before.
        learnPhoneKey(nodeNum, request.phoneKey, vouched = true)
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

        val generation = grant.generation.takeIf { it > 0 } ?: RoomKeyStore.FIRST
        val held = ChannelSlotManager.findByRoomId(mesh.channels.value, grant.room_id)
        val acceptable = TrustRules.mayTakeGrant(
            alreadyHeld = held != null,
            senderIsMember = memberDao.findEntity(grant.room_id, packet.from) != null,
            grantGeneration = generation,
            currentGeneration = roomKeys.generationOf(grant.room_id),
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
        val firepitKey = phoneKeys.open(
            grant.sealed_key.toByteArray(),
            KeyEnvelope.contextOf(grant.room_id, generation, myNodeNum),
        )?.takeIf { it.size == RoomCipher.KEY_SIZE } ?: run {
            Log.w(TAG, "grant for room ${grant.room_id} carried no sealing key we could open; ignored")
            return
        }

        val slot = held?.index
            ?: ChannelSlotManager.nextFreeSlot(mesh.channels.value)
            ?: run {
                Log.w(TAG, "no free slot for room ${grant.room_id}")
                return
            }

        // Before the channel write, so the slot is never briefly taken for an
        // ordinary Meshtastic one.
        roomKeys.remember(grant.room_id, firepitKey, generation)
        admin.setChannel(
            channelFor(
                index = slot,
                name = grant.room_name,
                psk = psk.toByteArray(),
                roomId = grant.room_id,
            ),
        )

        val now = System.currentTimeMillis()
        mesh.myNodeNum.value?.let { me -> memberDao.record(grant.room_id, me, now, invitedBy = packet.from) }
        memberDao.record(grant.room_id, packet.from, now, invitedBy = packet.from)

        _awaiting.value = null
        Log.i(TAG, "let into room ${grant.room_id} in slot $slot")
        shareCardWith(grant.room_id)
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
                    payload = sync.encode().let(ByteString::of),
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
    private suspend fun handleRosterSync(packet: MeshPacket, sync: RosterSync, myNodeNum: Int) {
        val roomId = sync.room_id.takeIf { it != 0 } ?: return
        if (ChannelSlotManager.findByRoomId(mesh.channels.value, roomId) == null) return
        val acceptable = TrustRules.rosterSyncAcceptable(
            privatelyToUs = packet.pki_encrypted && packet.to == myNodeNum,
            sender = packet.from,
            ourInviter = memberDao.findEntity(roomId, myNodeNum)?.invitedBy,
            senderIsMember = memberDao.findEntity(roomId, packet.from) != null,
        )
        if (!acceptable) {
            Log.w(TAG, "roster sync for $roomId from ${packet.from}, who did not let us in; ignored")
            return
        }

        val now = System.currentTimeMillis()
        sync.entries
            .filter { it.node_num != 0 }
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
        if (event.kind != RosterEvent.Kind.JOINED) return
        if (event.invited_by != packet.from) {
            Log.w(TAG, "roster event from ${packet.from} claims inviter ${event.invited_by}; ignored")
            return
        }
        val joiner = event.node_num.takeIf { it != 0 } ?: return
        val isNews = memberDao.findEntity(roomId, joiner) == null
        memberDao.record(roomId, joiner, System.currentTimeMillis(), invitedBy = event.invited_by)
        // Vouched: whoever sent this compared fingerprints with the newcomer in
        // person. It may replace a key we held, which is how somebody who comes
        // back with a new phone stays reachable by the next rotation. Anyone
        // abusing it gains nothing to read — a rotation still travels PKI to
        // the member's own radio — and can only make one member miss a key.
        learnPhoneKey(joiner, event.phone_key, vouched = true)
        // Only the inviter has introduced themselves so far. Without this the
        // newcomer sees radio names for everyone else already in the room.
        if (isNews && joiner != mesh.myNodeNum.value) greet(roomId)
    }

    private fun forgetStaleInvites(nowMillis: Long) {
        issuedInvites.entries.removeAll { nowMillis - it.value.issuedAt > INVITE_LEDGER_TTL_MS }
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

        /** Full precision: finding each other in a crowd is the point (decision U-2). */
        const val ROOM_POSITION_PRECISION = 32

        /** Curve25519 public key length; anything else cannot encrypt to a node. */
        const val PUBLIC_KEY_SIZE = 32

        /** What a PKI direct message leaves for the payload once the firmware adds its tag and nonce. */
        const val PKI_PAYLOAD_BUDGET = MeshConstants.DATA_PAYLOAD_LEN - MeshConstants.PKC_OVERHEAD

        /** Enough for a fumbled scan, not enough to grind the token check. */
        const val MAX_ATTEMPTS = 5
        const val ATTEMPT_WINDOW_MS = 60_000L

        /** Window the room's replies to one join are scattered across. */
        val GREETING_SPREAD = 30.seconds

        /** Past this, a join hello is too late to be tied to the invite it used. */
        const val INVITE_LEDGER_TTL_MS = 10 * 60 * 1000L

        /**
         * Roster entries per sync. Each costs ~14 bytes of the 233-byte data
         * payload, so this leaves headroom rather than risking an oversized packet.
         */
        const val MAX_ROSTER_ENTRIES = 14

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
