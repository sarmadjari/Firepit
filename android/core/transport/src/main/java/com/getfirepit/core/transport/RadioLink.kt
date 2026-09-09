package com.getfirepit.core.transport

import com.getfirepit.core.protocol.phoneapi.PhoneApiSession
import com.getfirepit.core.protocol.phoneapi.RadioSnapshot
import com.getfirepit.core.protocol.phoneapi.SessionState
import com.juul.kable.Peripheral
import com.juul.kable.State
import android.util.Log
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.ToRadio

sealed interface LinkState {
    data object Disconnected : LinkState
    data class Connecting(val attempt: Int) : LinkState

    /** Connected; the radio is streaming its config. */
    data object Downloading : LinkState
    data class Ready(val snapshot: RadioSnapshot) : LinkState

    /** Link dropped; a retry is scheduled. */
    data class Reconnecting(val attempt: Int, val cause: String) : LinkState
}

/** The radio went away — reset, powered off, or out of range. */
private class RadioDisconnected : Exception("Radio disconnected")

/**
 * Keeps one radio connected: connect, run the PhoneAPI session, and reconnect
 * with backoff when the link drops.
 *
 * One instance per physical radio. A radio accepts only one PhoneAPI client at
 * a time, so two links to the same node will fight.
 */
class RadioLink(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private fun setState(next: LinkState) {
        Log.i(TAG, "link: ${next::class.simpleName} ${describe(next)}")
        _state.value = next
    }

    private val currentSession = MutableStateFlow<PhoneApiSession?>(null)

    /** Steady-state traffic from whichever session is currently live. */
    @Suppress("OPT_IN_USAGE")
    val inbound: Flow<FromRadio> = currentSession.flatMapLatest { session ->
        session?.inbound ?: flowOf()
    }

    private var job: Job? = null

    fun connect(radio: DiscoveredRadio) {
        job?.cancel()
        job = scope.launch { maintainConnection(radio) }
    }

    /**
     * Announces the disconnect so the radio frees its PhoneAPI slot at once
     * instead of waiting for a timeout — the next connection would otherwise
     * be refused.
     */
    suspend fun disconnect() {
        runCatching { currentSession.value?.sendDisconnect() }
        job?.cancel()
        job = null
        currentSession.value = null
        setState(LinkState.Disconnected)
    }

    suspend fun send(message: ToRadio) {
        val session = currentSession.value ?: error("Not connected")
        session.send(message)
    }

    private suspend fun maintainConnection(radio: DiscoveredRadio) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val peripheral = Peripheral(radio.advertisement) {
                onServicesDiscovered { requestMtu(MeshtasticGatt.PREFERRED_MTU) }
                // Direct connect is faster on the first try; fall back to
                // autoConnect afterwards, which waits indefinitely and costs
                // far less battery when the radio is out of range.
                autoConnectIf { attempt > 0 }
            }

            try {
                setState(LinkState.Connecting(attempt))
                val connectionScope = peripheral.connect()
                attempt = 0

                val session = PhoneApiSession(BleRadioTransport(peripheral, connectionScope))
                currentSession.value = session
                connectionScope.launch { relayState(session) }

                // Kable's observe() Flow stays alive across disconnects instead
                // of failing, and an idle session issues no I/O, so a dropped
                // link would otherwise go unnoticed indefinitely. Watch the
                // peripheral's own state instead.
                coroutineScope {
                    val lostWatcher = launch {
                        peripheral.state.first { it is State.Disconnected }
                        throw RadioDisconnected()
                    }
                    session.run()
                    lostWatcher.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                setState(LinkState.Reconnecting(attempt, e.message ?: e::class.simpleName.orEmpty()))
            } finally {
                currentSession.value = null
                runCatching { peripheral.close() }
            }

            delay(backoffFor(attempt))
        }
    }

    private suspend fun relayState(session: PhoneApiSession) {
        session.state.map { sessionState ->
            when (sessionState) {
                SessionState.Idle -> null
                SessionState.Downloading -> LinkState.Downloading
                is SessionState.Ready -> LinkState.Ready(sessionState.snapshot)
            }
        }.collect { linkState -> if (linkState != null) setState(linkState) }
    }

    private companion object {
        const val TAG = "FirepitLink"

        val FIRST_BACKOFF = 1.seconds
        val MAX_BACKOFF = 30.seconds

        fun describe(state: LinkState): String = when (state) {
            is LinkState.Ready -> "node=${state.snapshot.myNodeNum} channels=${state.snapshot.channels.size}"
            is LinkState.Reconnecting -> "attempt=${state.attempt} cause=${state.cause}"
            is LinkState.Connecting -> "attempt=${state.attempt}"
            else -> ""
        }

        fun backoffFor(attempt: Int): Duration = when {
            attempt <= 0 -> Duration.ZERO
            else -> minOf(FIRST_BACKOFF * (1 shl (attempt - 1).coerceAtMost(5)), MAX_BACKOFF)
        }
    }
}
