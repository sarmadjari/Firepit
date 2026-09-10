package com.getfirepit.app.radio

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import com.getfirepit.core.protocol.NodeRole
import com.getfirepit.core.transport.RadioScanner
import com.getfirepit.core.data.SessionStore
import com.getfirepit.core.data.ApplicationScope
import android.util.Log
import android.content.Context
import com.getfirepit.core.transport.DiscoveredRadio
import com.getfirepit.core.transport.LinkState
import com.getfirepit.core.transport.RadioLink
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ties the foreground service's lifetime to the radio session.
 *
 * Connecting from anywhere goes through here, so there is no path that starts a
 * session without the service that keeps it alive.
 */
@Singleton
class RadioSessionController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val link: RadioLink,
    private val scanner: RadioScanner,
    private val sessionStore: SessionStore,
    private val savedRadios: SavedRadioStore,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    init {
        // A device is only tied to its mesh identity once it says who it is,
        // which is what lets the map know which markers are your own hardware.
        scope.launch {
            link.state.collect { state ->
                val nodeNum = (state as? LinkState.Ready)?.snapshot?.myNodeNum ?: return@collect
                sessionStore.lastRadioId?.let { savedRadios.rememberNode(it, nodeNum) }
            }
        }
    }

    fun connect(radio: DiscoveredRadio) {
        sessionStore.lastRadioId = radio.identifier
        // Registered here rather than at the button, so a radio reconnected on
        // launch is administered too. A first radio is Personal, because that is
        // what a first radio is; later ones default to Base until told otherwise.
        if (savedRadios.radios.value.none { it.identifier == radio.identifier }) {
            savedRadios.assign(
                identifier = radio.identifier,
                name = radio.name ?: radio.identifier,
                role = if (savedRadios.personal == null) NodeRole.PERSONAL else NodeRole.BASE,
            )
        }
        // Started first: the service must reach startForeground quickly, and
        // waiting on the connection would risk the system's start timeout.
        //
        // Android refuses a foreground service started from the background, and
        // the reconnect scan can outlive the screen the user opened. Losing the
        // service costs the link once the app is swiped away; crashing costs
        // everything, so the connection goes ahead either way.
        runCatching { RadioService.start(context) }
            .onFailure { cause -> Log.w(TAG, "connected without the background service", cause) }
        link.connect(radio)
    }

    suspend fun disconnect() {
        // Forgotten deliberately, so an explicit disconnect is not undone by
        // the next launch reconnecting on its own.
        sessionStore.lastRadioId = null
        link.disconnect()
        RadioService.stop(context)
    }

    /**
     * Reconnects to the radio that carries you, once it is in range.
     *
     * Prefers the Personal radio over whichever was used last: administering a
     * Base station shouldn't stop your own messages arriving next time you open
     * the app.
     *
     * Scans rather than addressing the device directly so a radio that is off
     * or out of range simply never appears, instead of failing repeatedly.
     */
    fun reconnectLastRadio() {
        val wanted = savedRadios.personal?.identifier ?: sessionStore.lastRadioId ?: return
        scope.launch {
            runCatching {
                withTimeoutOrNull(SCAN_WINDOW) {
                    scanner.scanDistinct()
                        .mapNotNull { found -> found.firstOrNull { it.identifier == wanted } }
                        .first()
                }
            }.getOrNull()?.let { radio ->
                Log.i(TAG, "reconnecting to ${radio.name ?: radio.identifier}")
                connect(radio)
            } ?: Log.i(TAG, "last radio not seen within the scan window")
        }
    }

    private companion object {
        const val TAG = "FirepitSession"

        /** Long enough for a radio in the room, short enough not to drain a pocket. */
        val SCAN_WINDOW = 30.seconds
    }
}
