package com.getfirepit.app.radio

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import com.getfirepit.core.transport.RadioScanner
import com.getfirepit.core.data.SessionStore
import com.getfirepit.core.data.ApplicationScope
import android.util.Log
import android.content.Context
import com.getfirepit.core.transport.DiscoveredRadio
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
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    fun connect(radio: DiscoveredRadio) {
        sessionStore.lastRadioId = radio.identifier
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
     * Reconnects to the radio last used, once it is in range.
     *
     * Scans rather than addressing the device directly so a radio that is off
     * or out of range simply never appears, instead of failing repeatedly.
     */
    fun reconnectLastRadio() {
        val wanted = sessionStore.lastRadioId ?: return
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
