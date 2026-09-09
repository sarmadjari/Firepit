package com.getfirepit.core.protocol.phoneapi

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.ToRadio

/**
 * In-memory radio that behaves like the BLE PhoneAPI: [read] hands back one
 * queued frame at a time and returns null once drained, and writing
 * `want_config_id` queues a config download.
 *
 * Holds raw frames rather than messages so malformed input can be injected.
 */
class FakeRadioTransport(
    /** Frames the radio will serve in response to a config download. */
    private val configDownload: (configId: Int) -> List<FromRadio>,
) : RadioTransport {

    private val queue = ArrayDeque<ByteArray>()
    private val _dataAvailable = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    private val counter = AtomicInteger()

    val written = mutableListOf<ToRadio>()
    var notificationsEnabled: Boolean = false
        private set

    override val dataAvailable: Flow<Int> = _dataAvailable.asSharedFlow()

    override suspend fun enableNotifications() {
        notificationsEnabled = true
    }

    override suspend fun write(frame: ByteArray) {
        val message = ToRadio.ADAPTER.decode(frame)
        written += message
        message.want_config_id?.let { configId ->
            queue += configDownload(configId).map { it.encode() }
        }
    }

    override suspend fun read(): ByteArray? = queue.removeFirstOrNull()

    /** Pushes unsolicited frames and rings FromNum, as the radio does for mesh traffic. */
    suspend fun deliver(vararg messages: FromRadio) {
        queue += messages.map { it.encode() }
        _dataAvailable.emit(counter.incrementAndGet())
    }

    /** Queues a frame without ringing FromNum, so only a post-write drain can find it. */
    fun deliverSilently(message: FromRadio) {
        queue += message.encode()
    }

    /** Undecodable bytes ahead of a valid frame: one bad frame must be skipped, not fatal. */
    suspend fun deliverGarbageThen(message: FromRadio) {
        queue += byteArrayOf(0x0A, 0x7F, 0xFF.toByte())
        queue += message.encode()
        _dataAvailable.emit(counter.incrementAndGet())
    }
}
