package com.getfirepit.core.data

import android.util.Log
import com.getfirepit.core.crypto.RoomCipher
import com.getfirepit.core.crypto.SealedText
import com.getfirepit.core.crypto.InviteCodec
import com.getfirepit.core.crypto.RoomCrypto
import com.getfirepit.core.database.RoomMemberDao
import com.getfirepit.core.database.observeRoom
import com.getfirepit.core.database.record
import com.getfirepit.core.database.recordReported
import com.getfirepit.core.model.ChannelRole
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
import com.getfirepit.core.protocol.MeshConstants
import com.getfirepit.core.protocol.MeshPacketBuilder
import com.getfirepit.core.protocol.MeshtasticChannel
import com.getfirepit.core.protocol.PacketOrigin
import com.getfirepit.core.protocol.PositionPrecision
import com.getfirepit.core.protocol.RangeMode
import com.getfirepit.protocol.meshchat.Invite
import com.getfirepit.protocol.meshchat.Inviter
import com.getfirepit.protocol.meshchat.JoinHello
import com.getfirepit.protocol.meshchat.LoRaProfile
import com.getfirepit.protocol.meshchat.MeshChatControl
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleSettings
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User

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
    private data class IssuedInvite(
        val roomId: Int,
        val inviteKey: ByteArray,
        val issuedAt: Long,
        /** Set once somebody has been let in on it, which spends it. */
        val usedBy: Int? = null,
    )

    /** Who we have seen in [roomId]. See [RoomMember] for what this can and cannot know. */
    fun observeMembers(roomId: Int): Flow<List<RoomMember>> = memberDao.observeRoom(roomId)

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
    /** Starts listening for join and roster traffic. Safe to call once per process. */
    fun start() {
        scope.launch {
            link.inbound.collect { message ->
                message.packet?.let { runCatching { handlePacket(it) }.onFailure { cause -> Log.w(TAG, "roster", cause) } }
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
            positionPrecision = ROOM_POSITION_PRECISION,
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

        val existing = ChannelSlotManager.rooms(mesh.channels.value).firstOrNull { it.name == trimmed }
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
        // rotating token, not this id, is what limits a stolen code.
        val inviteId = issuedInvites.entries
            .firstOrNull { it.value.roomId == roomId }
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
        if (ChannelSlotManager.findByRoomId(mesh.channels.value, invite.room_id) == null &&
            ChannelSlotManager.nextFreeSlot(mesh.channels.value) == null
        ) {
            throw RoomError.NoFreeSlot
        }

        // Before anything is sent: the answer comes back on the frequency this
        // sets, so asking from the wrong one is asking into silence.
        range.alignWith(RangeMode.of(invite.lora?.mesh_mode))

        _awaiting.value = AwaitedRoom(
            roomId = invite.room_id,
            roomName = invite.room_name,
            inviteId = invite.invite_id,
            inviter = invite.inviter?.node_num ?: throw RoomError.InviteInvalid,
        )
        askToJoin(invite)
    }

    /**
     * Sends the hello, encrypted to the key in the invite.
     *
     * Straight to PKI with no NodeInfo broadcast first: the inviter's public
     * key came with the code, so nothing has to propagate before we can speak
     * privately. Our own key rides inside, where only they can read it.
     */
    private suspend fun askToJoin(invite: Invite) {
        val inviter = invite.inviter ?: throw RoomError.InviteInvalid
        val publicKey = inviter.user?.public_key?.takeIf { it.size == PUBLIC_KEY_SIZE }
            ?: throw RoomError.InviteInvalid
        val mine = mesh.myUser?.public_key?.takeIf { it.size == PUBLIC_KEY_SIZE }
            ?: throw RoomError.NotConnected

        // The radio encrypts from its own NodeDB, which is bounded and may never
        // have heard of this inviter. The code carried their key, so hand it
        // over rather than broadcasting and hoping they answer in time.
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

        val generation = roomKeys.generationOf(roomId) + 1
        val psk = RoomCrypto.generatePsk()
        val firepitKey = RoomCipher.generateKey()

        // Our own radio first: if this fails nothing has been given away.
        admin.setChannel(channelFor(room.index, room.name, psk, roomId))
        roomKeys.remember(roomId, firepitKey, generation)

        remove.forEach { memberDao.remove(roomId, it) }

        val rotation = KeyRotation(
            room_id = roomId,
            generation = generation,
            room_psk = psk.toByteString(),
            firepit_key = firepitKey.toByteString(),
            room_name = room.name,
            removed = remove.toList(),
        )

        val keeping = memberDao.nodeNumsIn(roomId).filter { it != myNodeNum }
        val reached = keeping.filter { sendRotation(it, rotation) }

        // On the old key, so the people who did not get the new one hear why
        // the room went quiet instead of being left to guess.
        announceRotation(room.index, myNodeNum, generation)

        noticeInRoom(room.index, rotationNotice(remove))
        Log.i(TAG, "rotated room $roomId to generation $generation, ${reached.size}/${keeping.size} reached")
        return RotationResult(generation, reached.toSet(), (keeping - reached.toSet()).toSet())
    }

    private suspend fun sendRotation(member: Int, rotation: KeyRotation): Boolean {
        val publicKey = mesh.snapshot.value?.nodes?.get(member)?.user?.public_key
        if (publicKey == null || publicKey.size != 32) {
            Log.w(TAG, "no public key for $member; cannot hand over the new room key")
            return false
        }
        return runCatching {
            link.send(
                ToRadio(
                    packet = MeshPacketBuilder.meshPacket(
                        to = member,
                        channel = 0,
                        portNum = PortNum.PRIVATE_APP,
                        payload = MeshChatControl(key_rotation = rotation).encode().let(ByteString::of),
                        pkiEncrypted = true,
                        publicKey = publicKey,
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

    /** Applies a rotation somebody else performed. */
    private suspend fun handleKeyRotation(packet: MeshPacket, rotation: KeyRotation, authenticated: Boolean) {
        if (!authenticated) {
            Log.w(TAG, "unauthenticated key rotation from ${packet.from}; ignored")
            return
        }
        // Only somebody already in the room may change its locks.
        if (memberDao.findEntity(rotation.room_id, packet.from) == null) {
            Log.w(TAG, "key rotation for ${rotation.room_id} from a non-member ${packet.from}; ignored")
            return
        }
        if (rotation.generation <= roomKeys.generationOf(rotation.room_id)) {
            Log.i(TAG, "key rotation for ${rotation.room_id} is not newer than ours; ignored")
            return
        }
        val psk = rotation.room_psk.toByteArray()
        val firepitKey = rotation.firepit_key.toByteArray()
        if (psk.size != RoomCrypto.PSK_SIZE || firepitKey.size != RoomCipher.KEY_SIZE) return

        val slot = ChannelSlotManager.slotOf(mesh.channels.value, rotation.room_id) ?: return
        admin.setChannel(channelFor(slot, rotation.room_name, psk, rotation.room_id))
        roomKeys.remember(rotation.room_id, firepitKey, rotation.generation)
        rotation.removed.forEach { memberDao.remove(rotation.room_id, it) }

        noticeInRoom(slot, rotationNotice(rotation.removed.toSet()))
        Log.i(TAG, "took the new key for room ${rotation.room_id}, generation ${rotation.generation}")
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

        ChannelSlotManager.writesForLeaving(channels, roomId).forEach { write ->
            val channel = write.channel
            admin.setChannel(
                if (channel == null) {
                    Channel(index = write.index, role = Channel.Role.DISABLED, settings = ChannelSettings())
                } else {
                    // Carry the key across with the room; a slot move must not
                    // change what the room is.
                    val settings = admin.getChannel(channel.index)?.settings
                    channelFor(write.index, channel.name, settings?.psk?.toByteArray() ?: ByteArray(0), channel.id)
                },
            )
        }
        // History is stored per slot, and the firmware makes the remaining
        // rooms shuffle down, so it has to be moved with them.
        leavingSlot?.let { messageDao.deleteChannel(it, BROADCAST_NODE_NUM) }
        moves.forEach { (from, to) ->
            messageDao.moveChannel(from, to, BROADCAST_NODE_NUM)
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

        when (data.portnum) {
            // Anyone talking in a room is evidently in it, whatever we were told.
            PortNum.TEXT_MESSAGE_APP, PortNum.TEXT_MESSAGE_COMPRESSED_APP, PortNum.NODEINFO_APP ->
                firepitRoomFor(packet.channel)?.let { roomId ->
                    memberDao.record(roomId, packet.from, System.currentTimeMillis())
                }

            PortNum.PRIVATE_APP -> handleControl(
                packet,
                data.payload.toByteArray(),
                myNodeNum,
                // Anyone on a shared channel can put bytes on it under any name.
                authenticated = packet.pki_encrypted,
            )
            else -> Unit
        }

        if (data.portnum == PortNum.TEXT_MESSAGE_APP) {
            val direct = packet.to == myNodeNum
            // A receipt is a Firepit message. On a standard channel there is
            // nobody to read one and no private way to send it.
            if (direct || firepitRoomFor(packet.channel) != null) {
                receipts.received(packet.channel, packet.id, peer = packet.from.takeIf { direct })
            }
        }
    }

    private suspend fun handleControl(
        packet: MeshPacket,
        payload: ByteArray,
        myNodeNum: Int,
        authenticated: Boolean,
    ) {
        val control = runCatching { MeshChatControl.ADAPTER.decode(payload) }.getOrNull() ?: return
        control.join_hello?.let { hello -> handleJoinHello(packet, hello, myNodeNum) }

        // Answered only over PKI: a grant is the one message that carries a
        // room's keys, and a channel anyone holds is not where it may arrive.
        control.room_grant?.let { grant ->
            if (packet.pki_encrypted) {
                handleRoomGrant(packet, grant)
            } else {
                Log.w(TAG, "unencrypted room grant from ${packet.from}; ignored")
            }
        }
        control.roster_event?.let { event -> handleRosterEvent(packet, event) }
        control.roster_sync?.let { sync -> handleRosterSync(packet, sync) }
        control.sealed_message?.let { sealed -> handleSealed(packet, sealed, myNodeNum) }

        control.receipt?.let { receipt ->
            if (!authenticated) {
                Log.w(TAG, "unauthenticated receipt from ${packet.from}; ignored")
                return@let
            }
            receipts.handle(packet.from, receipt)
        }

        control.key_rotation?.let { rotation -> handleKeyRotation(packet, rotation, authenticated) }

        control.room_text?.let { room ->
            if (!authenticated) {
                Log.w(TAG, "unsealed room text from ${packet.from}; ignored")
                return@let
            }
            mesh.saveSealedText(packet, room.text, room.reply_id.takeIf { it != 0 })
            receipts.received(packet.channel, packet.id)
        }
    }

    /**
     * Open it and treat what is inside as if it had arrived in the clear. The
     * key is the room's, so a radio relaying this cannot do the same, and
     * opening it is itself proof the sender holds the key.
     */
    private suspend fun handleSealed(packet: MeshPacket, sealed: SealedMessage, myNodeNum: Int) {
        // The generation it was sealed under, not the one we have moved on to:
        // history stays readable across a rotation.
        val key = roomKeys.keyFor(sealed.room_id, sealed.generation.takeIf { it > 0 } ?: RoomKeyStore.FIRST)
            ?: return
        val context = SealedText.contextOf(sealed.room_id, packet.from)
        val plain = SealedText.open(key, sealed.ciphertext.toByteArray(), context) ?: run {
            Log.w(TAG, "sealed payload for room ${sealed.room_id} would not open")
            return
        }
        handleControl(packet, plain, myNodeNum, authenticated = true)
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

        val joinerKey = hello.joiner_key.takeIf { it.size == PUBLIC_KEY_SIZE } ?: run {
            Log.w(TAG, "join hello from $from carried no usable key to answer with")
            return
        }

        _pendingJoins.update { pending ->
            pending.filterNot { it.nodeNum == from } +
                PendingJoin(
                    nodeNum = from,
                    roomId = issued.roomId,
                    inviteId = hello.invite_id,
                    generation = hello.generation,
                    joinerKey = joinerKey,
                    askedAt = System.currentTimeMillis(),
                )
        }
        Log.i(TAG, "$from is asking to join room ${issued.roomId}; waiting on an answer")
    }

    /**
     * Lets [nodeNum] in, handing over the room's keys.
     *
     * Encrypted to the key that came inside their sealed hello rather than one
     * looked up in the NodeDB, which any radio can write to by claiming a node
     * number.
     */
    suspend fun approveJoin(nodeNum: Int) {
        val myNodeNum = mesh.myNodeNum.value ?: throw RoomError.NotConnected
        val request = _pendingJoins.value.firstOrNull { it.nodeNum == nodeNum } ?: return
        val room = ChannelSlotManager.findByRoomId(mesh.channels.value, request.roomId)
            ?: throw RoomError.InviteInvalid
        val psk = admin.getChannel(room.index)?.settings?.psk ?: throw RoomError.NotConnected
        val firepitKey = roomKeys.keyFor(request.roomId) ?: roomKeys.generate(request.roomId)

        // Same reason as the joiner's side: the key came in their sealed hello,
        // so the radio need not have heard of them to be answered.
        if (mesh.publicKeyOf(nodeNum) == null) {
            runCatching {
                admin.addContact(
                    nodeNum,
                    User(id = MeshConstants.formatNodeId(nodeNum), public_key = request.joinerKey),
                )
            }.onFailure { cause -> Log.w(TAG, "could not add $nodeNum as a contact", cause) }
        }

        sendGrant(
            to = nodeNum,
            key = request.joinerKey,
            grant = RoomGrant(
                answer = RoomGrant.Answer.GRANTED,
                invite_id = request.inviteId,
                room_id = request.roomId,
                room_name = room.name,
                room_psk = psk,
                firepit_key = firepitKey.toByteString(),
                generation = request.generation,
                position_precision = ROOM_POSITION_PRECISION,
            ),
        )

        // Spent: a code photographed over somebody's shoulder stops being worth
        // presenting the moment the person it was shown to is let in.
        issuedInvites.computeIfPresent(request.inviteId) { _, issued -> issued.copy(usedBy = nodeNum) }
        clearPending(nodeNum)

        memberDao.record(request.roomId, nodeNum, System.currentTimeMillis(), invitedBy = myNodeNum)
        Log.i(TAG, "let $nodeNum into room ${request.roomId}")

        announceJoined(request.roomId, nodeNum, myNodeNum, request.generation)
        sendRosterTo(nodeNum, request.roomId)
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

    /** Tells the room who was vouched for, so every roster shows the same chain. */
    private suspend fun announceJoined(roomId: Int, joiner: Int, myNodeNum: Int, generation: Int) {
        val slot = ChannelSlotManager.findByRoomId(mesh.channels.value, roomId)?.index ?: return
        link.send(
            ToRadio(
                packet = MeshPacketBuilder.meshPacket(
                    to = MeshConstants.BROADCAST_NODENUM,
                    channel = slot,
                    portNum = PortNum.PRIVATE_APP,
                    payload = MeshChatControl(
                        version = InviteCodec.VERSION,
                        roster_event = RosterEvent(
                            kind = RosterEvent.Kind.JOINED,
                            node_num = joiner,
                            invited_by = myNodeNum,
                            generation = generation,
                        ),
                    ).encode().let(ByteString::of),
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
    private suspend fun handleRoomGrant(packet: MeshPacket, grant: RoomGrant) {
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

        val psk = grant.room_psk.takeIf { it.size == RoomCrypto.PSK_SIZE } ?: run {
            Log.w(TAG, "grant for room ${grant.room_id} carried no usable channel key; ignored")
            return
        }
        val firepitKey = grant.firepit_key.toByteArray().takeIf { it.size == RoomCipher.KEY_SIZE } ?: run {
            Log.w(TAG, "grant for room ${grant.room_id} carried no usable sealing key; ignored")
            return
        }

        val slot = ChannelSlotManager.findByRoomId(mesh.channels.value, grant.room_id)?.index
            ?: ChannelSlotManager.nextFreeSlot(mesh.channels.value)
            ?: run {
                Log.w(TAG, "no free slot for room ${grant.room_id}")
                return
            }

        // Before the channel write, so the slot is never briefly taken for an
        // ordinary Meshtastic one.
        roomKeys.remember(grant.room_id, firepitKey)
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
     */
    private suspend fun handleRosterSync(packet: MeshPacket, sync: RosterSync) {
        val roomId = sync.room_id.takeIf { it != 0 } ?: return
        if (ChannelSlotManager.findByRoomId(mesh.channels.value, roomId) == null) return

        val now = System.currentTimeMillis()
        sync.entries
            .filter { it.node_num != 0 }
            .forEach { entry ->
                memberDao.recordReported(roomId, entry.node_num, now, entry.invited_by.takeIf { it != 0 })
            }
        Log.i(TAG, "roster sync from ${packet.from}: ${sync.entries.size} entries, truncated=${sync.truncated}")
    }

    /**
     * The inviter is vouching for somebody. Only believed when it arrives on
     * the room's own channel and the sender claims themselves as the inviter:
     * anyone in the room could otherwise fabricate a trust chain.
     */
    private suspend fun handleRosterEvent(packet: MeshPacket, event: RosterEvent) {
        val roomId = firepitRoomFor(packet.channel) ?: return
        if (event.kind != RosterEvent.Kind.JOINED) return
        if (event.invited_by != packet.from) {
            Log.w(TAG, "roster event from ${packet.from} claims inviter ${event.invited_by}; ignored")
            return
        }
        val joiner = event.node_num.takeIf { it != 0 } ?: return
        memberDao.record(roomId, joiner, System.currentTimeMillis(), invitedBy = event.invited_by)
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
            module_settings = ModuleSettings(position_precision = ROOM_POSITION_PRECISION),
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

        /** Enough for a fumbled scan, not enough to grind the token check. */
        const val MAX_ATTEMPTS = 5
        const val ATTEMPT_WINDOW_MS = 60_000L

        /** Past this, a join hello is too late to be tied to the invite it used. */
        const val INVITE_LEDGER_TTL_MS = 10 * 60 * 1000L

        /**
         * Roster entries per sync. Each costs ~14 bytes of the 233-byte data
         * payload, so this leaves headroom rather than risking an oversized packet.
         */
        const val MAX_ROSTER_ENTRIES = 14
    }
}

/** What a rotation managed to hand over, and to whom it did not. */
data class RotationResult(
    val generation: Int,
    val reached: Set<Int>,
    val missed: Set<Int>,
)
