package com.getfirepit.app.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.crypto.CodeScanner
import com.getfirepit.core.crypto.InviteCodec
import com.getfirepit.core.crypto.ScannedCode
import com.getfirepit.core.data.RangeRepository
import com.getfirepit.core.model.RoomKind
import okio.ByteString.Companion.toByteString
import com.getfirepit.core.crypto.RoomCrypto
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.RoomRepository
import com.getfirepit.core.data.TracerouteClient
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.data.AwaitedRoom
import com.getfirepit.core.data.PendingJoin
import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.model.RoomMember
import com.getfirepit.core.protocol.ChannelSlotManager
import com.getfirepit.core.protocol.MeshConstants
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class InviteState(
    val payload: String,
    /** Whole seconds left before the code is redrawn, for the countdown ring. */
    val secondsRemaining: Int,
)

data class RoomsUiState(
    val connected: Boolean = false,
    val rooms: List<RoomChannel> = emptyList(),
    val isFull: Boolean = false,
    val busy: Boolean = false,
    val invite: InviteState? = null,
    val joinedRoomName: String? = null,
    val error: String? = null,
)

/** What a path check is doing, for one member at a time. */
sealed interface TraceState {
    data object Idle : TraceState
    data class Running(val nodeNum: Int) : TraceState
    data class Done(val nodeNum: Int, val summary: String) : TraceState
}

/** A roster row: who they are, plus who vouched for them if anyone did. */
data class MemberRow(
    val member: RoomMember,
    val node: MeshNode?,
    val invitedByName: String?,
    val isSelf: Boolean,
) {
    val displayName: String get() = node?.displayName ?: "!%08x".format(member.nodeNum)
    val shortName: String? get() = node?.shortName
}

@HiltViewModel
class RoomsViewModel @Inject constructor(
    private val rooms: RoomRepository,
    private val mesh: MeshRepository,
    private val range: RangeRepository,
    private val traceroute: TracerouteClient,
) : ViewModel() {

    private val _trace = MutableStateFlow<TraceState>(TraceState.Idle)
    val trace: StateFlow<TraceState> = _trace.asStateFlow()

    /**
     * Checks the path to a member.
     *
     * The mesh cannot say who received a message, but it can say how it reaches
     * a node, which is the question behind the asking.
     */
    fun checkPath(nodeNum: Int, name: String) {
        viewModelScope.launch {
            _trace.value = TraceState.Running(nodeNum)
            val result = runCatching { traceroute.trace(nodeNum) }.getOrNull()
            _trace.value = TraceState.Done(
                nodeNum = nodeNum,
                summary = when {
                    result == null -> "No reply from $name. They may be out of range."
                    result.isDirect -> "$name answered directly, no relay."
                    else -> "$name is ${result.hopsOut} hops away, via " +
                        result.towards.joinToString(", ") { hop ->
                            MeshConstants.formatNodeId(hop.nodeNum) +
                                (hop.snr?.let { " (%.1f dB)".format(it) } ?: "")
                        }
                },
            )
        }
    }

    fun clearTrace() {
        _trace.value = TraceState.Idle
    }

    private val busy = MutableStateFlow(false)
    private val invite = MutableStateFlow<InviteState?>(null)
    private val notice = MutableStateFlow<String?>(null)
    private val failure = MutableStateFlow<String?>(null)

    /**
     * Whether to ask about taking the radio's primary channel over.
     *
     * Only once a private room exists: before that nothing is at stake, and the
     * question would be an interruption with no context. After it, "should this
     * radio be private too?" is a sentence that explains itself.
     */
    val askToMakeRadioPrivate: StateFlow<Boolean> = combine(
        range.needsChoice,
        mesh.channels,
    ) { needsChoice, channels ->
        needsChoice && channels.any { it.isRoom && it.kind == RoomKind.FIREPIT }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun makeRadioPrivate() = run("Could not change the radio") { range.makePrivate() }

    /** People asking to be let into one of our rooms. */
    val pendingJoins: StateFlow<List<PendingJoin>> = rooms.pendingJoins

    /** The room we asked to join, while we wait to be let in. */
    val awaiting: StateFlow<AwaitedRoom?> = rooms.awaiting

    fun approveJoin(nodeNum: Int) = run("Could not let them in") { rooms.approveJoin(nodeNum) }

    fun declineJoin(nodeNum: Int) = run("Could not turn them away") { rooms.declineJoin(nodeNum) }

    fun stopWaiting() = rooms.stopWaiting()

    fun keepRadioPublic() =
        run("Could not put the radio's own channel back") { range.keepPublic() }

    /** Held so a second room's rotation replaces the first instead of racing it. */
    private var rotationJob: Job? = null

    val uiState: StateFlow<RoomsUiState> = combine(
        mesh.isConnected,
        mesh.channels,
        busy,
        invite,
        combine(notice, failure) { notice, failure -> notice to failure },
    ) { connected, channels, busy, invite, (notice, failure) ->
        RoomsUiState(
            connected = connected,
            rooms = ChannelSlotManager.rooms(channels),
            isFull = ChannelSlotManager.isFull(channels),
            busy = busy,
            invite = invite,
            joinedRoomName = notice,
            error = failure,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoomsUiState())

    fun clearMessages() {
        failure.value = null
        notice.value = null
    }

    /**
     * The room's roster, newest sighting first, with our own entry pinned to
     * the top so "who else is here" reads at a glance.
     */
    fun members(roomId: Int): Flow<List<MemberRow>> =
        combine(rooms.observeMembers(roomId), mesh.observeNodes(), mesh.myNodeNum) { members, nodes, me ->
            val byNum = nodes.associateBy { it.nodeNum }
            members
                .map { member ->
                    MemberRow(
                        member = member,
                        node = byNum[member.nodeNum],
                        invitedByName = member.invitedBy
                            ?.takeIf { it != member.nodeNum }
                            ?.let { inviter ->
                                byNum[inviter]?.displayName ?: "!%08x".format(inviter)
                            },
                        isSelf = member.nodeNum == me,
                    )
                }
                .sortedByDescending { it.isSelf }
        }

    fun createRoom(name: String) = run("Could not create the room") {
        val room = rooms.createRoom(name)
        notice.value = "Created ${room.name}"
    }

    fun leaveRoom(roomId: Int) = run("Could not leave the room") {
        rooms.leaveRoom(roomId)
    }

    private val _rotation = MutableStateFlow<String?>(null)

    /** What the last removal managed, for telling the user plainly. */
    val rotation: StateFlow<String?> = _rotation.asStateFlow()

    fun clearRotation() {
        _rotation.value = null
    }

    fun removeMember(roomId: Int, nodeNum: Int) = run("Could not remove them") {
        val result = rooms.rotateRoom(roomId, remove = setOf(nodeNum))
        _rotation.value = when {
            result.missed.isEmpty() ->
                "Removed. Everyone still in the room has the new key."
            result.reached.isEmpty() ->
                "Removed, and the room has a new key. Nobody else could be reached to be " +
                    "given it, so invite them again when they are back in range."
            else ->
                "Removed, and the room has a new key. ${result.missed.size} member" +
                    "${if (result.missed.size == 1) "" else "s"} could not be reached; " +
                    "invite them again when they are back in range."
        }
    }

    /**
     * One scanner for both worlds, routed by Firepit's own scheme before any
     * decoding happens. Which format a code is decides how private the room can
     * be, so the notice says which one arrived.
     */
    fun joinFromScan(scanned: String) = run("Could not join") {
        when (val code = CodeScanner.classify(scanned)) {
            is ScannedCode.Firepit -> {
                rooms.joinRoom(code.invite)
                // Not joined yet: the code carries no keys, so this is a
                // request the inviter still has to answer.
                notice.value = null
            }

            is ScannedCode.Meshtastic -> {
                val added = rooms.joinMeshtasticChannels(code.shared)
                notice.value = when {
                    added.size == 1 -> "Added ${added.first().name}. Standard Meshtastic — " +
                        "other Meshtastic apps can read it, and Firepit's own features are off."

                    else -> "Added ${added.size} Meshtastic channels. Other Meshtastic apps " +
                        "can read them, and Firepit's own features are off."
                }
            }

            is ScannedCode.FirepitUnreadable -> error(
                when (code.reason) {
                    ScannedCode.Reason.NEWER_VERSION ->
                        "This Firepit invite was made by a newer version of the app. Update to join."

                    ScannedCode.Reason.MALFORMED ->
                        "This Firepit invite is damaged or has expired — ask for a fresh one."
                },
            )

            ScannedCode.Unrecognised ->
                error("That isn't a Firepit invite or a Meshtastic channel link")
        }
    }

    /**
     * A channel shared with particular people who are not running Firepit. The
     * key is generated here and handed over as a standard Meshtastic link.
     */
    fun addSharedChannel(name: String) = run("Could not add the channel") {
        val room = rooms.addMeshtasticChannel(
            name = name,
            psk = RoomCrypto.generatePsk().toByteString(),
        )
        notice.value = "Added ${room.name}. Share it from the Meshtastic app — " +
            "Firepit only issues its own invites."
    }

    /**
     * Re-issues the code every rotation window so a photographed invite stops
     * working within seconds.
     */
    fun startInviteRotation(roomId: Int) {
        rotationJob?.cancel()
        rotationJob = viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val remaining = remainingSeconds(now)
                runCatching { rooms.buildInvite(roomId, now) }
                    .onSuccess { invite.value = InviteState(InviteCodec.encode(it), remaining) }
                    .onFailure { cause -> failure.value = cause.message }

                // Tick once a second so the ring moves, then rebuild on the boundary.
                repeat(remaining) {
                    delay(1.seconds)
                    invite.value = invite.value?.let { current ->
                        current.copy(secondsRemaining = (current.secondsRemaining - 1).coerceAtLeast(0))
                    }
                }
            }
        }
    }

    fun stopInvite() {
        rotationJob?.cancel()
        rotationJob = null
        invite.value = null
    }

    private fun remainingSeconds(nowMillis: Long): Int {
        val period = RoomCrypto.ROTATION_SECONDS * 1000
        return ((period - nowMillis % period) / 1000).toInt()
            .coerceIn(1, RoomCrypto.ROTATION_SECONDS.toInt())
    }

    private fun run(fallback: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            failure.value = null
            runCatching { block() }.onFailure { cause -> failure.value = cause.message ?: fallback }
            busy.value = false
        }
    }
}
