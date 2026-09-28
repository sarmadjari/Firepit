package com.getfirepit.core.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.getfirepit.core.protocol.PrimaryChannel
import com.getfirepit.core.protocol.RadioPrivacy
import com.getfirepit.core.protocol.RangeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.proto.Channel

/**
 * How far our traffic travels, and who decides what slot 0 looks like.
 *
 * The primary channel cannot be removed — the firmware uses it to set the
 * radio's frequency, and `channel.proto` says every other enabled channel must
 * be SECONDARY. What Firepit can do is refuse to take it over uninvited.
 *
 * A radio may already be somebody's Meshtastic node, on a mesh with their
 * friends. Rewriting slot 0 would silently drop it off that mesh, and they
 * would have no way to know why. So nothing is written until the person says
 * so: either by choosing a range here, or by joining a Firepit room from an
 * invite, which carries the mode and cannot work without it.
 *
 * Until then [primaryIsPublic] reports the truth — that the node's name and
 * battery are readable by any Meshtastic radio — rather than quietly fixing it.
 */
@Singleton
class RangeRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val mesh: MeshRepository,
    private val admin: NodeAdminClient,
    private val backup: PrimaryBackup,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val preferences =
        context.getSharedPreferences("firepit_range", Context.MODE_PRIVATE)

    // One write at a time: a mode change racing the reconnect check would
    // otherwise have them both read the old slot and write different names.
    private val mutex = Mutex()

    private val _mode = MutableStateFlow(RangeMode.named(preferences.getString(KEY_MODE, null)))
    val mode: StateFlow<RangeMode> = _mode.asStateFlow()

    /**
     * True while the connected radio's primary still carries a key every
     * Meshtastic radio holds. Shown rather than hidden: until it is rewritten,
     * this node's identity is public.
     */
    private val _primaryIsPublic = MutableStateFlow(false)
    val primaryIsPublic: StateFlow<Boolean> = _primaryIsPublic.asStateFlow()

    /** Whether the person has said what this radio's primary should be. */
    private val _privacy = MutableStateFlow(
        RadioPrivacy.named(preferences.getString(KEY_PRIVACY, null)),
    )
    val privacy: StateFlow<RadioPrivacy> = _privacy.asStateFlow()

    /**
     * True when the radio is connected, its identity is public, and nobody has
     * chosen yet — the one moment worth interrupting for.
     */
    val needsChoice: StateFlow<Boolean> = combine(
        mesh.isConnected,
        _privacy,
        _primaryIsPublic,
    ) { connected, privacy, public ->
        connected && privacy == RadioPrivacy.UNDECIDED && public
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * True when the radio's own primary was recorded and can be put back.
     *
     * False once it has been, and for a radio Firepit never took over — there
     * is nothing to return it to.
     */
    val canRestore: StateFlow<Boolean> = combine(
        mesh.isConnected,
        mesh.myNodeNum,
        _privacy,
    ) { connected, nodeNum, privacy ->
        connected && nodeNum != null && privacy == RadioPrivacy.FIREPIT && backup.has(nodeNum)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Starts the per-connection check. Safe to call once per process. */
    fun start() {
        scope.launch {
            mesh.isConnected.collect { connected ->
                if (!connected) {
                    _primaryIsPublic.value = false
                    return@collect
                }
                // Nothing can be read back until the config download lands.
                val ready = withTimeoutOrNull(CHANNELS_TIMEOUT) {
                    mesh.channels.first { it.isNotEmpty() }
                }
                if (ready == null) {
                    Log.w(TAG, "radio never listed its channels; leaving the primary alone")
                    return@collect
                }
                runCatching { inspect() }
                    .onFailure { cause -> Log.w(TAG, "could not read the primary channel", cause) }
            }
        }
    }

    /**
     * Makes this radio's identity private, keeping whatever range is already
     * chosen. What the person is actually answering when asked.
     */
    suspend fun makePrivate() = choose(_mode.value)

    /**
     * Moves this node to [choice], and takes the primary over if it has not
     * been already. Remembered even when the radio is not connected, so the
     * next connection applies it.
     *
     * Everyone in a group must be on the same mode or they cannot hear each
     * other at all, which is why invites carry it.
     */
    suspend fun choose(choice: RangeMode) {
        preferences.edit {
            putString(KEY_MODE, choice.name)
            putString(KEY_PRIVACY, RadioPrivacy.FIREPIT.name)
        }
        _mode.value = choice
        _privacy.value = RadioPrivacy.FIREPIT
        if (!mesh.isConnected.value) return
        reconcile(choice)
    }

    /**
     * Leaves the radio's own primary exactly as it is, or puts it back if
     * Firepit already replaced it.
     *
     * For somebody who already uses this node on their own mesh: Firepit rooms
     * are secondary channels and work regardless, so their existing setup keeps
     * working. Restoring is the whole reason the old slot was recorded — an
     * offer to keep something public has to be able to undo having made it
     * private, or it is not an offer.
     */
    suspend fun keepPublic() {
        val nodeNum = mesh.myNodeNum.value
        val original = nodeNum?.takeIf { mesh.isConnected.value }?.let(backup::saved)
        if (original != null) {
            // Thrown on rather than swallowed: recording the choice while the
            // radio still carries Firepit's channel would be a lie.
            mutex.withLock { admin.setChannel(original) }
            backup.forget(nodeNum)
            _primaryIsPublic.value = true
            Log.i(TAG, "put the radio's own primary channel back")
        }
        preferences.edit { putString(KEY_PRIVACY, RadioPrivacy.OPEN.name) }
        _privacy.value = RadioPrivacy.OPEN
        if (original == null) Log.i(TAG, "leaving the radio's own primary channel alone")
    }

    /**
     * Adopts the mode an invite was issued under.
     *
     * A joiner on a different mode is on a different frequency and would never
     * hear the room at all, so this is applied rather than offered: accepting
     * the invite is the consent.
     */
    suspend fun alignWith(choice: RangeMode) {
        if (_privacy.value == RadioPrivacy.FIREPIT &&
            choice == _mode.value &&
            !_primaryIsPublic.value
        ) {
            return
        }
        Log.i(TAG, "aligning range to ${choice.name} to match the invite")
        choose(choice)
    }

    /**
     * Reads slot 0 back and reports whether it is still public.
     *
     * Writes only for a radio whose primary we were told to take over, and then
     * through the same path the settings screen uses — an earlier version had
     * two routes to this decision that disagreed about what to do when the slot
     * could not be read, so a choice could silently never apply.
     */
    private suspend fun inspect() {
        if (_privacy.value == RadioPrivacy.FIREPIT) {
            reconcile(_mode.value)
            return
        }
        mutex.withLock {
            _primaryIsPublic.value = PrimaryChannel.isPublic(admin.getChannel(PrimaryChannel.SLOT))
        }
    }

    private suspend fun reconcile(choice: RangeMode) = mutex.withLock {
        val current = admin.getChannel(PrimaryChannel.SLOT)
        if (current != null && PrimaryChannel.matches(current, choice)) {
            _primaryIsPublic.value = false
            Log.i(TAG, "primary channel already set for ${choice.name}")
            return@withLock
        }
        // An unconfirmed slot might still be the factory one, and the cost of a
        // redundant write is a flash page against a public identity.
        if (current == null) Log.w(TAG, "primary slot would not read back; writing it regardless")
        write(choice, current)
    }

    private suspend fun write(choice: RangeMode, replacing: Channel?) {
        // Recorded before the overwrite, and only if it is somebody else's:
        // one of our own channels is not worth returning a radio to.
        val nodeNum = mesh.myNodeNum.value
        if (nodeNum != null && replacing != null &&
            PrimaryChannel.modeOf(replacing) == null && replacing.carriesAKey()
        ) {
            backup.remember(nodeNum, replacing)
        }

        admin.setChannel(PrimaryChannel.channelFor(choice))
        _primaryIsPublic.value = false
        Log.i(
            TAG,
            "primary channel set for ${choice.name}; the radio retunes to the frequency slot " +
                "derived from \"${PrimaryChannel.nameFor(choice)}\"",
        )
    }

    private companion object {
        const val TAG = "FirepitRange"
        const val KEY_MODE = "range_mode"
        const val KEY_PRIVACY = "radio_privacy"

        /** A radio that has not listed its channels by now is not going to. */
        val CHANNELS_TIMEOUT = 30.seconds
    }
}
