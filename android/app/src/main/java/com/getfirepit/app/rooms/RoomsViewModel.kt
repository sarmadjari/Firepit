package com.getfirepit.app.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.crypto.InviteCodec
import com.getfirepit.core.crypto.RoomCrypto
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.RoomRepository
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.model.RoomMember
import com.getfirepit.core.protocol.ChannelSlotManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val invite = MutableStateFlow<InviteState?>(null)
    private val notice = MutableStateFlow<String?>(null)
    private val failure = MutableStateFlow<String?>(null)

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

    fun joinFromScan(scanned: String) = run("Could not join") {
        val decoded = InviteCodec.decode(scanned) ?: error("That isn't a Firepit invite")
        rooms.joinRoom(decoded)
        notice.value = decoded.room_name
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
