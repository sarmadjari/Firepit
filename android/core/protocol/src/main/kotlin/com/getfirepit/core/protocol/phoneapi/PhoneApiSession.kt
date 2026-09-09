package com.getfirepit.core.protocol.phoneapi

import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.ToRadio

sealed interface SessionState {
    data object Idle : SessionState

    /** Handshake in flight: the radio is streaming its config. */
    data object Downloading : SessionState

    data class Ready(val snapshot: RadioSnapshot) : SessionState
}

/** Radio reported `rebooted`, so the config we hold is stale and must be re-read. */
private class RadioRebooted : Exception("Radio rebooted")

/**
 * The transport stopped delivering notifications. Treated as a lost link rather
 * than a clean end, so the caller reconnects with backoff instead of spinning.
 */
class TransportClosed : Exception("Radio transport closed")

/**
 * Drives the PhoneAPI conversation over a connected [RadioTransport]:
 * handshake, config download, then a steady-state drain loop.
 *
 * Does not own the physical connection. If the transport throws, the exception
 * propagates so a connection manager can reconnect and start a fresh session.
 */
class PhoneApiSession(
    private val transport: RadioTransport,
    private val configIds: () -> Int = ::randomConfigId,
    private val downloadTimeout: Duration = 60.seconds,
    private val pollInterval: Duration = 50.milliseconds,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<FromRadio>(extraBufferCapacity = 256)

    /** Steady-state traffic. Config-download messages also pass through here. */
    val inbound: SharedFlow<FromRadio> = _inbound.asSharedFlow()

    private val drainRequests = Channel<Unit>(Channel.CONFLATED)

    /**
     * Runs until cancelled or the transport fails. Re-runs the handshake
     * whenever the radio reports it rebooted, which happens after most
     * `set_config` writes.
     */
    suspend fun run() {
        transport.enableNotifications()
        try {
            while (coroutineContext.isActive) {
                _state.value = SessionState.Ready(handshake())
                if (!pump()) throw TransportClosed()
            }
        } finally {
            _state.value = SessionState.Idle
        }
    }

    suspend fun send(message: ToRadio) {
        transport.write(message.encode())
        // Responses to our own writes would otherwise sit in the radio's queue
        // until some unrelated notification arrives.
        drainRequests.trySend(Unit)
    }

    /** Lets the radio reset its PhoneAPI state immediately instead of timing out. */
    suspend fun sendDisconnect() {
        transport.write(ToRadio(disconnect = true).encode())
    }

    private suspend fun handshake(): RadioSnapshot {
        _state.value = SessionState.Downloading
        val configId = configIds()
        val accumulator = RadioSnapshotAccumulator()
        transport.write(ToRadio(want_config_id = configId).encode())

        withTimeout(downloadTimeout) {
            while (true) {
                val message = readNext()
                if (message == null) {
                    // FromNum notifications are gated behind the radio's
                    // packet-sending state, so the download must be polled.
                    delay(pollInterval)
                    continue
                }
                if (accumulator.accept(message, configId)) return@withTimeout
            }
        }
        return accumulator.build()
    }

    /** @return true if the radio rebooted, false if the transport stopped emitting. */
    private suspend fun pump(): Boolean = try {
        merge(
            transport.dataAvailable.map { },
            drainRequests.receiveAsFlow(),
        ).collect { drain() }
        false
    } catch (_: RadioRebooted) {
        true
    }

    private suspend fun drain() {
        while (true) {
            val message = readNext() ?: return
            if (message.rebooted == true) throw RadioRebooted()
        }
    }

    /** One decoded message, or null only when the radio's queue is genuinely empty. */
    private suspend fun readNext(): FromRadio? {
        while (true) {
            val frame = transport.read()
            if (frame == null || frame.isEmpty()) return null
            val message = try {
                FromRadio.ADAPTER.decode(frame)
            } catch (_: IOException) {
                // Everything off the mesh is untrusted; skip the bad frame and
                // keep draining rather than mistaking it for an empty queue.
                continue
            }
            _inbound.emit(message)
            return message
        }
    }
}

/** Must be non-zero: the radio uses 0 to mean "no config download in progress". */
private fun randomConfigId(): Int = Random.nextInt().let { if (it == 0) 1 else it }
