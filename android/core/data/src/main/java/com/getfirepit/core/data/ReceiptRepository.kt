package com.getfirepit.core.data

import android.util.Log
import com.getfirepit.core.crypto.DirectSeal
import com.getfirepit.core.database.MessageDao
import com.getfirepit.core.database.ReceiptDao
import com.getfirepit.core.database.RoomMemberDao
import com.getfirepit.core.database.find
import com.getfirepit.core.database.observe
import com.getfirepit.core.database.recordRead
import com.getfirepit.core.model.BROADCAST_NODE_NUM
import com.getfirepit.core.model.Receipt
import com.getfirepit.core.protocol.MeshPacketBuilder
import com.getfirepit.core.protocol.OutboundPacer
import com.getfirepit.core.protocol.PendingReceipts
import com.getfirepit.core.protocol.ReceiptCarriage
import com.getfirepit.core.protocol.ReceiptRules
import com.getfirepit.core.protocol.TrustRules
import com.getfirepit.core.transport.RadioLink
import com.getfirepit.protocol.meshchat.MeshChatControl
import com.getfirepit.protocol.meshchat.Receipt as ReceiptProto
import com.getfirepit.protocol.meshchat.SealedDirect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Who has a message, who has read it, and when.
 *
 * Receipts are collected rather than sent one per message: a packet carries
 * forty ids, so telling a room about a morning's reading costs one transmission
 * instead of forty. A message the sender marked urgent skips the wait.
 */
@Singleton
class ReceiptRepository @Inject constructor(
    private val link: RadioLink,
    private val mesh: MeshRepository,
    private val roomKeys: RoomKeyStore,
    private val receiptDao: ReceiptDao,
    private val messageDao: MessageDao,
    private val memberDao: RoomMemberDao,
    private val phoneKeys: PhoneKeyStore,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val pacer = OutboundPacer(System::currentTimeMillis)
    private val mutex = Mutex()
    private val pending = mutableMapOf<Conversation, PendingReceipts>()
    private val echo = mutableMapOf<Conversation, PendingReceipts>()
    private val reported = mutableMapOf<Conversation, MutableSet<Int>>()
    private var flushJob: Job? = null

    /** A channel, or the one person a direct message came from. */
    private data class Conversation(val channel: Int, val peer: Int?)

    /**
     * Whether this conversation has receipts at all.
     *
     * The Firepit room id is what decides it: a standard Meshtastic channel
     * carries none. [ReceiptRules.tracks] holds the rule; this only supplies
     * what the radio and key store know.
     */
    private fun tracked(conversation: Conversation): Boolean =
        ReceiptRules.tracks(firepitRoomFor(conversation.channel), conversation.peer)

    /** The room a channel carries, only when we hold the key that seals it. */
    private fun firepitRoomFor(channel: Int): Int? =
        mesh.roomIdForChannel(channel)?.takeIf { roomKeys.holds(it) }

    /** Who has this message, and when they got it. */
    fun observe(messageId: Int): Flow<List<Receipt>> = receiptDao.observe(messageId)

    /** The same, for everything on screen at once. */
    fun observeAll(messageIds: List<Int>): Flow<Map<Int, List<Receipt>>> =
        receiptDao.observeForAll(messageIds).map { rows ->
            rows.groupBy({ it.messageId }) { Receipt(it.nodeNum, it.state, it.at) }
        }

    /** A message arrived. */
    suspend fun received(channel: Int, messageId: Int, peer: Int? = null) {
        val key = Conversation(channel, peer)
        if (!tracked(key)) return
        mutex.withLock {
            if (messageId in reported.getOrPut(key) { mutableSetOf() }) return@withLock
            pending[key] = ReceiptRules.received(pending[key] ?: PendingReceipts(), messageId)
        }
        schedule(QUIET_WINDOW_MILLIS)
    }

    /**
     * Messages were on screen long enough to count as read.
     *
     * Sent sooner than a delivery receipt: it is the half anyone actually waits
     * for, and arriving ten minutes late makes it useless.
     *
     * The caller passes everything on screen each time the list changes, so ids
     * already reported are dropped here. Without that a room would re-send the
     * same receipts on every arrival, which is the flood batching exists to
     * avoid.
     */
    suspend fun read(channel: Int, messageIds: Set<Int>, peer: Int? = null) {
        if (messageIds.isEmpty()) return
        val key = Conversation(channel, peer)
        if (!tracked(key)) return
        mutex.withLock {
            val fresh = messageIds - reported.getOrPut(key) { mutableSetOf() }
            if (fresh.isEmpty()) return@withLock
            pending[key] = ReceiptRules.opened(pending[key] ?: PendingReceipts(), fresh)
        }
        schedule(READ_WINDOW_MILLIS)
    }

    /**
     * Someone told us what they have.
     *
     * Only believed about our own messages, and only from someone who was
     * meant to see them: the person a direct message went to, or a member of
     * the room it was sent in. Packet ids are in every header, so without this
     * anybody could put themselves on a message's "read by" list.
     */
    suspend fun handle(from: Int, receipt: ReceiptProto, at: Long = System.currentTimeMillis()) {
        receipt.delivered.filter { mayReport(it, from) }.forEach {
            receiptDao.recordReceived(it, from, at)
            mesh.confirmDirectAttempt(it, from)
        }
        receipt.read.filter { mayReport(it, from) }.forEach {
            receiptDao.recordRead(it, from, at)
            mesh.confirmDirectAttempt(it, from)
        }
    }

    private suspend fun mayReport(messageId: Int, from: Int): Boolean {
        val message = messageDao.find(messageId) ?: return false
        val isRoomMember = message.toNodeNum == BROADCAST_NODE_NUM &&
            firepitRoomFor(message.channel)?.let { memberDao.findEntity(it, from) } != null
        return TrustRules.receiptAllowed(message.isOutgoing, message.toNodeNum, from, isRoomMember)
    }

    /**
     * A window rather than an immediate send, so a burst of arrivals becomes one
     * packet. Restarting it on each event would starve a busy room, so an
     * already-scheduled flush is left to run.
     */
    private fun schedule(windowMillis: Long) {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(windowMillis + Random.nextLong(JITTER_MILLIS))
            runCatching { flush() }.onFailure { Log.w(TAG, "receipt flush failed", it) }
        }
    }

    /**
     * Send what is owed, one packet per conversation.
     *
     * A batch is capped, so anything left over needs another window rather than
     * waiting for the next message to arrive and carry it.
     */
    suspend fun flush() {
        val owed = mutex.withLock { pending.filterValues { !it.isEmpty }.toMap() }
        owed.forEach { (conversation, outstanding) ->
            val sent = ReceiptRules.batch(outstanding, echo[conversation] ?: PendingReceipts())
            if (sent.isEmpty) return@forEach
            if (!send(conversation, sent)) {
                // Nothing about this conversation will change, so holding the
                // ids only grows a list nobody will ever read, and re-offering
                // them would repeat the attempt on every message that arrives.
                mutex.withLock {
                    pending.remove(conversation)
                    remember(conversation, outstanding)
                }
                return@forEach
            }
            mutex.withLock {
                pending[conversation] =
                    ReceiptRules.remaining(pending[conversation] ?: PendingReceipts(), sent)
                echo[conversation] = ReceiptRules.echoOf(sent)
                remember(conversation, sent)
            }
        }
        if (mutex.withLock { pending.any { !it.value.isEmpty } }) schedule(QUIET_WINDOW_MILLIS)
    }

    /** Ids already announced, kept bounded: a receipt is worth saying once. */
    private fun remember(conversation: Conversation, sent: PendingReceipts) {
        val seen = reported.getOrPut(conversation) { mutableSetOf() }
        seen += sent.delivered
        seen += sent.read
        if (seen.size > REPORTED_MEMORY) {
            reported[conversation] = seen.drop(seen.size - REPORTED_MEMORY).toMutableSet()
        }
    }

    /**
     * Sent only where it can be sent privately: sealed under a room key, or
     * sealed to the one person's phone and encrypted to their radio as well.
     *
     * On an ordinary channel a receipt would announce to everyone in earshot
     * what this phone has been reading, which is worse than having no receipt.
     * Somebody whose phone key we never learned gets none either: it would only
     * tell a stranger this phone is on and reading.
     */
    private suspend fun send(conversation: Conversation, batch: PendingReceipts): Boolean {
        val myNodeNum = mesh.myNodeNum.value ?: return false
        val roomId = firepitRoomFor(conversation.channel)
        val publicKey = conversation.peer?.let(::publicKeyOf)
        val peerPhoneKey = conversation.peer?.let { mesh.phoneKeyOf(it) }
        val receipt = ReceiptProto(
            room_id = roomId ?: 0,
            delivered = batch.delivered.toList(),
            read = batch.read.toList(),
        )
        val control = MeshChatControl(receipt = receipt)

        val carriage = ReceiptRules.carriageFor(
            channel = conversation.channel,
            roomId = roomId,
            peer = conversation.peer,
            hasPeerKey = publicKey != null,
            hasPeerPhoneKey = peerPhoneKey != null,
        )

        val packet = when (carriage) {
            is ReceiptCarriage.SealedRoom -> {
                val payload = seal(carriage.roomId, myNodeNum, control) ?: return false
                MeshPacketBuilder.meshPacket(
                    to = BROADCAST_NODE_NUM,
                    channel = carriage.channel,
                    portNum = PortNum.PRIVATE_APP,
                    payload = payload,
                    hopLimit = mesh.hopLimitForSending(),
                    // Like words, so the header does not tell a receipt from them.
                    wantAck = true,
                    // Nobody is waiting on a receipt about a receipt.
                    priority = MeshPacket.Priority.BACKGROUND,
                )
            }

            is ReceiptCarriage.SealedDirect -> {
                val directRoom = mesh.directRoomSecretFor(carriage.nodeNum)
                val sealed = try {
                    phoneKeys.sealDirect(
                        requireNotNull(peerPhoneKey),
                        control.encode(),
                        DirectSeal.contextOf(myNodeNum, carriage.nodeNum),
                        directRoom,
                    )
                } finally {
                    directRoom?.key?.fill(0)
                }
                MeshPacketBuilder.meshPacket(
                    to = carriage.nodeNum,
                    channel = conversation.channel,
                    portNum = PortNum.PRIVATE_APP,
                    payload = MeshChatControl(
                        sealed_direct = SealedDirect(ciphertext = sealed.toByteString()),
                    ).encode().let(ByteString::of),
                    hopLimit = mesh.hopLimitForSending(),
                    wantAck = true,
                    pkiEncrypted = true,
                    publicKey = requireNotNull(publicKey),
                    priority = MeshPacket.Priority.BACKGROUND,
                ).also { packet ->
                    mesh.recordDirectAttempt(
                        packetId = packet.id,
                        peer = carriage.nodeNum,
                        room = directRoom,
                        channel = conversation.channel,
                        text = null,
                        replyId = null,
                    )
                }
            }

            ReceiptCarriage.None -> {
                Log.i(TAG, "no private way to send a receipt on channel ${conversation.channel}")
                return false
            }
        }

        pacer.awaitSlot(PortNum.PRIVATE_APP)
        return runCatching { link.send(ToRadio(packet = packet)) }
            .onFailure { Log.w(TAG, "receipt send failed", it) }
            .isSuccess
    }

    private fun publicKeyOf(node: Int): ByteString? = mesh.snapshot.value
        ?.nodes?.get(node)?.user?.public_key
        // Exactly the size PKI needs: a malformed key from the mesh would
        // otherwise fail every receipt flush, for every conversation after it.
        ?.takeIf { it.size == TrustRules.RADIO_KEY_SIZE }

    /**
     * Under the room's own key, which the radio never holds.
     *
     * Null when there is no such key, which means the channel is an ordinary
     * Meshtastic one. Receipts are a Firepit concept: on a shared channel they
     * would be unreadable noise to every other client, and would announce to
     * everyone in earshot what this phone has been reading.
     */
    private fun seal(roomId: Int, myNodeNum: Int, control: MeshChatControl): ByteString? {
        // The generation travels with it: without that the receiver reaches for
        // generation 1 and every receipt goes unreadable once a room rotates.
        val sealed = roomKeys.seal(roomId, myNodeNum, control.encode()) ?: return null
        return MeshChatControl(sealed_message = sealed).encode().let(ByteString::of)
    }

    private companion object {
        const val TAG = "ReceiptRepository"
        const val QUIET_WINDOW_MILLIS = 30_000L
        const val READ_WINDOW_MILLIS = 3_000L
        const val REPORTED_MEMORY = 500
        const val JITTER_MILLIS = 20_000L
    }
}
