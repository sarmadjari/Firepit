package com.getfirepit.app.location

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.data.LocationRepository
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.protocol.ChannelSlotManager
import com.getfirepit.core.protocol.PositionSharing
import com.getfirepit.core.protocol.ShareDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Everything about where your position is going, in one place.
 *
 * The map, the settings screen and a room's own page all ask the same question,
 * and a person who turned sharing on in one of them expects to find it in the
 * others. Three copies of this would eventually disagree, and the one that
 * disagreed quietly would be the one still transmitting.
 */
@HiltViewModel
class SharingViewModel @Inject constructor(
    private val location: LocationRepository,
    mesh: MeshRepository,
) : ViewModel() {

    val state: StateFlow<SharingUiState> = combine(
        mesh.channels,
        mesh.myNodeNum,
        location.sharingDeadline,
        mesh.isConnected,
    ) { channels, myNodeNum, deadline, connected ->
        val rooms = ChannelSlotManager.rooms(channels).filter(PositionSharing::canShare)
        // The phone does the sharing, so the choice it recorded is the truth;
        // the radio only tells us whether that room can be reached right now.
        val room = deadline?.let { chosen -> rooms.firstOrNull { it.id == chosen.roomId } }
        SharingUiState(
            connected = myNodeNum != null,
            rooms = rooms,
            roomId = deadline?.roomId,
            roomName = room?.displayName,
            choice = deadline?.choice ?: ShareDuration.DEFAULT,
            endsAt = deadline?.endsAt,
            paused = deadline != null && (!connected || room == null),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SharingUiState())

    fun share(roomId: Int, choice: ShareDuration) {
        viewModelScope.launch {
            runCatching { location.shareWith(roomId, choice) }
                .onFailure { cause -> Log.w(TAG, "could not start sharing", cause) }
        }
    }

    /** Takes effect at once, whether or not a radio is connected: the phone is what sends. */
    fun stop() {
        viewModelScope.launch {
            runCatching { location.stopSharing() }
                .onFailure { cause -> Log.w(TAG, "could not stop sharing", cause) }
        }
    }

    private companion object {
        const val TAG = "FirepitSharing"
    }
}

data class SharingUiState(
    val connected: Boolean = false,
    /** Private rooms only: a Meshtastic channel reaches people nobody chose. */
    val rooms: List<RoomChannel> = emptyList(),
    val roomId: Int? = null,
    val roomName: String? = null,
    val choice: ShareDuration = ShareDuration.DEFAULT,
    val endsAt: Long? = null,
    /**
     * Chosen, but not going anywhere now: the phone is away from its radio, or
     * the radio connected does not carry the room. It resumes on its own.
     */
    val paused: Boolean = false,
) {
    val isSharing: Boolean get() = roomId != null
    val hasRooms: Boolean get() = rooms.isNotEmpty()
}
