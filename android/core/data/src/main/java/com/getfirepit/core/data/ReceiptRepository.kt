package com.getfirepit.core.data

import android.util.Log
import com.getfirepit.core.crypto.RoomCipher
import com.getfirepit.core.crypto.SealedText
import com.getfirepit.core.database.ReceiptDao
import com.getfirepit.core.database.observe
import com.getfirepit.core.database.recordRead
import com.getfirepit.core.model.BROADCAST_NODE_NUM
import com.getfirepit.core.model.Receipt
import com.getfirepit.core.protocol.MeshPacketBuilder
import com.getfirepit.core.protocol.OutboundPacer
import com.getfirepit.core.protocol.PendingReceipts
import com.getfirepit.core.protocol.ReceiptRules
import com.getfirepit.core.transport.RadioLink
import com.getfirepit.protocol.meshchat.MeshChatControl
import com.getfirepit.protocol.meshchat.Receipt as ReceiptProto
import com.getfirepit.protocol.meshchat.SealedMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val pacer = OutboundPacer(System::currentTimeMillis)
    private val mutex = Mutex()
    private val pending = mutableMapOf<Int, PendingReceipts>()
    private val echo = mutableMapOf<Int, PendingReceipts>()
    private var flushJob: Job? = null

    /** Who has this message, and when they got it. */
    fun observe(messageId: Int): Flow<List<Receipt>> = receiptDao.observe(messageId)

    /** A message arrived. */
    suspend fun received(channel: Int, messageId: Int) {
        mutex.withLock {
            pending[channel] = ReceiptRules.received(pending[channel] ?: PendingReceipts(), messageId)
        }
        schedule(QUIET_WINDOW_MILLIS)
    }

    /**
     * Messages were on screen long enough to count as read.
     *
     * Sent sooner than a delivery receipt: it is the half anyone actually waits
     * for, and arriving ten minutes late makes it useless.
     */
    suspend fun read(channel: Int, messageIds: Set<Int>) {
        if (messageIds.isEmpty()) return
        mutex.withLock {
            pending[channel] = ReceiptRules.opened(pending[channel] ?: PendingReceipts(), messageIds)
        }
        schedule(READ_WINDOW_MILLIS)
    }

    /** Someone told us what they have. */
    suspend fun handle(from: Int, receipt: ReceiptProto, at: Long = System.currentTimeMillis()) {
        receipt.delivered.forEach { receiptDao.recordReceived(it, from, at) }
        receipt.read.forEach { receiptDao.recordRead(it, from, at) }
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

    /** Send what is owed, one packet per conversation. */
    suspend fun flush() {
        val owed = mutex.withLock { pending.filterValues { !it.isEmpty }.toMap() }
        owed.forEach { (channel, outstanding) ->
            val sent = ReceiptRules.batch(outstanding, echo[channel] ?: PendingReceipts())
            if (sent.isEmpty) return@forEach
            if (!send(channel, sent)) return@forEach
            mutex.withLock {
                pending[channel] = ReceiptRules.remaining(pending[channel] ?: PendingReceipts(), sent)
                echo[channel] = ReceiptRules.echoOf(sent)
            }
        }
    }

    private suspend fun send(channel: Int, batch: PendingReceipts): Boolean {
        val myNodeNum = mesh.myNodeNum.value ?: return false
        val roomId = roomIdForChannel(channel)
        val receipt = ReceiptProto(
            room_id = roomId ?: 0,
            delivered = batch.delivered.toList(),
            read = batch.read.toList(),
        )
        val control = MeshChatControl(receipt = receipt)
        val payload = seal(roomId, myNodeNum, control) ?: control.encode().let(ByteString::of)

        val packet = MeshPacketBuilder.meshPacket(
            to = BROADCAST_NODE_NUM,
            channel = channel,
            portNum = PortNum.PRIVATE_APP,
            payload = payload,
            // Nobody is waiting on a receipt about a receipt.
            priority = MeshPacket.Priority.BACKGROUND,
        )
        pacer.awaitSlot(PortNum.PRIVATE_APP)
        return runCatching { link.send(ToRadio(packet = packet)) }
            .onFailure { Log.w(TAG, "receipt send failed on channel $channel", it) }
            .isSuccess
    }

    /**
     * Under the room key where there is one, so a listener cannot learn who is
     * reading whom from traffic it cannot otherwise open.
     */
    private fun seal(roomId: Int?, myNodeNum: Int, control: MeshChatControl): ByteString? {
        val key = roomId?.let { roomKeys.keyFor(it) } ?: return null
        val sealed = RoomCipher.seal(key, control.encode(), SealedText.contextOf(roomId, myNodeNum))
        return MeshChatControl(
            sealed_message = SealedMessage(room_id = roomId, ciphertext = sealed.toByteString()),
        ).encode().let(ByteString::of)
    }

    private fun roomIdForChannel(channel: Int): Int? = mesh.channels.value
        .firstOrNull { it.index == channel && it.isRoom }
        ?.id
        ?.takeIf { it != 0 }

    private companion object {
        const val TAG = "ReceiptRepository"
        const val QUIET_WINDOW_MILLIS = 30_000L
        const val READ_WINDOW_MILLIS = 3_000L
        const val JITTER_MILLIS = 20_000L
    }
}
