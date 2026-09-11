package com.getfirepit.core.data

import android.util.Log
import com.getfirepit.core.transport.LinkState
import com.getfirepit.core.transport.RadioLink
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A radio whose clock disagrees with the phone, and by how much. */
data class ClockOffer(val drift: Duration, val behind: Boolean)

/**
 * Offers to give the radio the phone's time.
 *
 * A radio with no GPS and no battery-backed clock stamps every message it hands
 * over with whatever it believes the time is, which reaches the app as a
 * conversation filed under the wrong day. Writing someone's hardware is offered
 * rather than done quietly.
 *
 * The firmware records this as Net quality, which the protos note is below GPS,
 * so a radio with a fix keeps its own better time. It costs no airtime: admin
 * packets are addressed to our own node and never reach the air.
 */
@Singleton
class NodeClock @Inject constructor(
    private val link: RadioLink,
    private val mesh: MeshRepository,
    private val admin: NodeAdminClient,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val _offer = MutableStateFlow<ClockOffer?>(null)
    val offer: StateFlow<ClockOffer?> = _offer.asStateFlow()

    private val declined = MutableStateFlow(false)

    fun start() {
        val ready = link.state.map { it is LinkState.Ready }.distinctUntilChanged()

        scope.launch {
            // A reconnection, or a different radio, is worth asking about again.
            ready.collect { if (!it) declined.value = false }
        }

        scope.launch {
            combine(ready, mesh.clockSkewMillis, declined) { connected, skew, refused ->
                if (!connected || refused || skew == null) null
                else skew.milliseconds.takeIf { drift -> drift.absoluteValue >= TOLERANCE }
            }.collect { drift ->
                _offer.value = drift?.let { ClockOffer(it.absoluteValue, behind = it.isNegative()) }
            }
        }
    }

    /** Accepted: write the phone's time to the radio. */
    suspend fun sync() {
        val now = System.currentTimeMillis() / 1_000
        runCatching { admin.setTime(now.toInt()) }
            .onSuccess { Log.i(TAG, "radio clock set to $now") }
            .onFailure { Log.w(TAG, "could not set the radio clock", it) }
        // Packets already in flight still carry the old stamp, and asking again
        // about a clock we have just set would look like it had not worked.
        declined.value = true
        _offer.value = null
    }

    /** Declined: leave the radio alone until it connects again. */
    fun dismiss() {
        declined.value = true
    }

    private companion object {
        const val TAG = "NodeClock"

        /** Below this the difference cannot put a message under the wrong day. */
        val TOLERANCE = 2.minutes
    }
}
