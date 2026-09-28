package com.getfirepit.app.location

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
    ) { channels, myNodeNum, deadline ->
        val rooms = ChannelSlotManager.rooms(channels).filter(PositionSharing::canShare)
        val sharing = PositionSharing.sharingChannels(channels).firstOrNull()
        SharingUiState(
            connected = myNodeNum != null,
            rooms = rooms,
            roomId = sharing?.id?.takeIf { it != 0 },
            roomName = sharing?.displayName,
            choice = deadline?.choice ?: ShareDuration.DEFAULT,
            // Only meaningful alongside a room the radio is actually sharing
            // with, so a stale note never reads as live sharing.
            endsAt = deadline?.endsAt?.takeIf { sharing != null },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SharingUiState())

    fun share(roomId: Int, choice: ShareDuration) {
        viewModelScope.launch { location.shareWith(roomId, choice) }
    }

    fun stop() {
        viewModelScope.launch { location.stopSharing() }
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
) {
    val isSharing: Boolean get() = roomId != null
    val hasRooms: Boolean get() = rooms.isNotEmpty()
}
