package com.getfirepit.app.radio

import android.content.Context
import android.util.Log
import com.getfirepit.core.data.ApplicationScope
import com.getfirepit.core.data.PrimaryBackup
import com.getfirepit.core.data.SessionStore
import com.getfirepit.core.protocol.NodeRole
import com.getfirepit.core.protocol.SavedRadio
import com.getfirepit.core.protocol.phoneapi.RadioSnapshot
import com.getfirepit.core.transport.DiscoveredRadio
import com.getfirepit.core.transport.LinkState
import com.getfirepit.core.transport.RadioLink
import com.getfirepit.core.transport.RadioScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    private val primaryBackup: PrimaryBackup,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _identityDoubt = MutableStateFlow<String?>(null)

    /**
     * The saved radio whose address answered with a different identity, or
     * null. Anything can answer at a Bluetooth address; a radio that suddenly
     * has another node number or key is either reset, or not this one.
     */
    val identityDoubt: StateFlow<String?> = _identityDoubt.asStateFlow()

    init {
        // A device is only tied to its mesh identity once it says who it is,
        // which is what lets the map know which markers are your own hardware.
        scope.launch {
            link.state.collect { state ->
                val snapshot = (state as? LinkState.Ready)?.snapshot
                val nodeNum = snapshot?.myNodeNum
                if (nodeNum == null) {
                    _identityDoubt.value = null
                    return@collect
                }
                val id = sessionStore.lastRadioId ?: return@collect
                val consistent = savedRadios.rememberNode(id, nodeNum, identityKeyOf(snapshot, nodeNum))
                _identityDoubt.value = id.takeUnless { consistent }
                if (!consistent) Log.w(TAG, "radio at $id answered as a different node or key than the one saved")
            }
        }
    }

    /** The person says the radio answering is theirs, reset or reflashed. */
    fun trustConnectedRadio() {
        val snapshot = (link.state.value as? LinkState.Ready)?.snapshot ?: return
        val nodeNum = snapshot.myNodeNum ?: return
        val id = sessionStore.lastRadioId ?: return
        savedRadios.trust(id, nodeNum, identityKeyOf(snapshot, nodeNum))
        _identityDoubt.value = null
    }

    /**
     * Stops administering [radio] for good: it is no longer reconnected on
     * launch, and the copy of its original primary channel goes with it.
     */
    fun forget(radio: SavedRadio) {
        if (sessionStore.lastRadioId == radio.identifier) sessionStore.lastRadioId = null
        radio.nodeNum?.let(primaryBackup::forget)
        savedRadios.forget(radio.identifier)
    }

    private fun identityKeyOf(snapshot: RadioSnapshot, nodeNum: Int): String? =
        (snapshot.security?.public_key ?: snapshot.nodes[nodeNum]?.user?.public_key)
            ?.takeIf { it.size > 0 }
            ?.base64()

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
