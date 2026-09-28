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
import com.getfirepit.core.protocol.PositionPrecision
import com.getfirepit.protocol.meshchat.Invite
import com.getfirepit.protocol.meshchat.Inviter
import com.getfirepit.protocol.meshchat.JoinHello
import com.getfirepit.protocol.meshchat.MeshChatControl
import com.getfirepit.protocol.meshchat.KeyRotation
import com.getfirepit.protocol.meshchat.SealedMessage
import com.getfirepit.protocol.meshchat.RosterEntry
import com.getfirepit.protocol.meshchat.RosterEvent
import com.getfirepit.protocol.meshchat.RosterSync
import com.getfirepit.core.transport.RadioLink
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okio.ByteString
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

    private data class IssuedInvite(val roomId: Int, val inviteKey: ByteArray, val issuedAt: Long)

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
     * The key is read back from the radio rather than cached, so it is held in
     * memory only for as long as the code is on screen.
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
            room_psk = psk.toByteString(),
            position_precision = ROOM_POSITION_PRECISION,
            generation = generation,
            inviter = Inviter(node_num = myNodeNum, user = mesh.myUser),
            invite_id = inviteId,
            issued_at = (nowMillis / 1000L).toInt(),
            window = window,
            token = RoomCrypto.token(inviteKey, myNodeNum, window).toByteString(),
            // Handing over the room key is the whole point of an invite: without
            // it a joiner can hear the room but not read a word of it.
            firepit_key = (roomKeys.keyFor(roomId) ?: roomKeys.generate(roomId)).toByteString(),
        )
    }

    /**
     * Writes the invited room into a free slot, then introduces us to the room.
     *
     * Overwrites in place when the room is already present, which is what a
     * key rotation looks like from the joiner's side.
     */
    suspend fun joinRoom(invite: Invite, nowMillis: Long = System.currentTimeMillis()) {
        mesh.myNodeNum.value ?: throw RoomError.NotConnected
        verify(invite, nowMillis)

        val existing = ChannelSlotManager.findByRoomId(mesh.channels.value, invite.room_id)
        val slot = existing?.index
            ?: ChannelSlotManager.nextFreeSlot(mesh.channels.value)
            ?: throw RoomError.NoFreeSlot

        admin.setChannel(
            channelFor(
                index = slot,
                name = invite.room_name,
                psk = invite.room_psk.toByteArray(),
                roomId = invite.room_id,
            ),
        )
        Log.i(TAG, "joined room ${invite.room_id} in slot $slot")

        invite.firepit_key.toByteArray()
            .takeIf { it.size == RoomCipher.KEY_SIZE }
            ?.let { roomKeys.remember(invite.room_id, it) }

        mesh.myNodeNum.value?.let { me ->
            val now = System.currentTimeMillis()
            memberDao.record(invite.room_id, me, now, invitedBy = invite.inviter?.node_num)
            invite.inviter?.node_num?.let { memberDao.record(invite.room_id, it, now, invitedBy = it) }
        }

        announceJoin(slot, invite)
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

    private fun verify(invite: Invite, nowMillis: Long) {
        val inviter = invite.inviter ?: throw RoomError.InviteInvalid
        if (invite.room_psk.size != RoomCrypto.PSK_SIZE) throw RoomError.InviteInvalid

        // Every invite must be bound to a window. A tokenless one never stops
        // working, so a copy kept from a room that has long since emptied would
        // still carry a live key.
        with(InviteCodec) { if (!invite.isTimeBound()) throw RoomError.InviteExpired }

        val inviteKey = RoomCrypto.inviteKey(invite.room_psk.toByteArray(), invite.room_id, invite.generation)
        val valid = RoomCrypto.isTokenValid(
            inviteKey = inviteKey,
            inviterNodeNum = inviter.node_num,
            token = invite.token.toByteArray(),
            claimedWindow = invite.window,
            scannedAtWindow = RoomCrypto.windowFor(nowMillis),
        )
        if (!valid) throw RoomError.InviteExpired
    }

    /**
     * Two steps, in this order because PKI needs it.
     *
     * The hello is a plain channel broadcast that puts our public key in every
     * member's NodeDB. Only then can the join hello go out as an encrypted
     * direct message — sent the other way round, the inviter could not decrypt
     * it.
     */
    private suspend fun announceJoin(slot: Int, invite: Invite) {
        val user = mesh.myUser ?: return
        link.send(
            ToRadio(
                packet = MeshPacketBuilder.meshPacket(
                    to = MeshConstants.BROADCAST_NODENUM,
                    channel = slot,
                    portNum = PortNum.NODEINFO_APP,
                    payload = user.encode().let(ByteString::of),
                    priority = MeshPacket.Priority.BACKGROUND,
                ),
            ),
        )

        delay(HELLO_PROPAGATION)

        val inviter = invite.inviter ?: return
        val control = MeshChatControl(
            version = InviteCodec.VERSION,
            join_hello = JoinHello(
                invite_id = invite.invite_id,
                token = invite.token,
                generation = invite.generation,
                app_version = InviteCodec.VERSION,
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
                    publicKey = inviter.user?.public_key ?: ByteString.EMPTY,
                    wantAck = true,
                ),
            ),
        )
        Log.i(TAG, "join hello sent to ${inviter.node_num}")
    }

    private suspend fun handlePacket(packet: MeshPacket) {
        val data = packet.decoded ?: return
        val myNodeNum = mesh.myNodeNum.value ?: return
        if (packet.from == myNodeNum) return

        when (data.portnum) {
            // Anyone talking in a room is evidently in it, whatever we were told.
            PortNum.TEXT_MESSAGE_APP, PortNum.TEXT_MESSAGE_COMPRESSED_APP, PortNum.NODEINFO_APP ->
                mesh.roomIdForChannel(packet.channel)?.let { roomId ->
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
            receipts.received(packet.channel, packet.id, peer = packet.from.takeIf { direct })
        }
    }

    private suspend fun handleControl(
        packet: MeshPacket,
        payload: ByteArray,
        myNodeNum: Int,
        authenticated: Boolean,
    ) {
        val control = runCatching { MeshChatControl.ADAPTER.decode(payload) }.getOrNull() ?: return
        control.join_hello?.let { hello -> handleJoinHello(packet.from, hello, myNodeNum) }
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
            ?: mesh.channelKeyFor(sealed.room_id)
            ?: return
        val context = SealedText.contextOf(sealed.room_id, packet.from)
        val plain = SealedText.open(key, sealed.ciphertext.toByteArray(), context) ?: run {
            Log.w(TAG, "sealed payload for room ${sealed.room_id} would not open")
            return
        }
        handleControl(packet, plain, myNodeNum, authenticated = true)
    }

    /**
     * Somebody used one of our invites. Vouch for them, and tell the room so
     * every member's roster shows the same trust chain.
     */
    private suspend fun handleJoinHello(from: Int, hello: JoinHello, myNodeNum: Int) {
        val issued = issuedInvites[hello.invite_id] ?: run {
            Log.w(TAG, "join hello for unknown invite ${hello.invite_id}")
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

        memberDao.record(issued.roomId, from, System.currentTimeMillis(), invitedBy = myNodeNum)
        Log.i(TAG, "vouched for $from in room ${issued.roomId}")

        val slot = ChannelSlotManager.findByRoomId(mesh.channels.value, issued.roomId)?.index ?: return
        val event = MeshChatControl(
            version = InviteCodec.VERSION,
            roster_event = RosterEvent(
                kind = RosterEvent.Kind.JOINED,
                node_num = from,
                invited_by = myNodeNum,
                generation = hello.generation,
            ),
        )
        link.send(
            ToRadio(
                packet = MeshPacketBuilder.meshPacket(
                    to = MeshConstants.BROADCAST_NODENUM,
                    channel = slot,
                    portNum = PortNum.PRIVATE_APP,
                    payload = event.encode().let(ByteString::of),
                    priority = MeshPacket.Priority.BACKGROUND,
                ),
            ),
        )

        sendRosterTo(from, issued.roomId)
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
        val roomId = mesh.roomIdForChannel(packet.channel) ?: return
        if (event.invited_by != packet.from) {
            Log.w(TAG, "roster event from ${packet.from} claims inviter ${event.invited_by}; ignored")
            return
        }
        memberDao.record(roomId, event.node_num, System.currentTimeMillis(), invitedBy = event.invited_by)
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

        val HELLO_PROPAGATION = 5.seconds

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
