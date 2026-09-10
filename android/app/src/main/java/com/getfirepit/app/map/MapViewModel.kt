package com.getfirepit.app.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.data.LocationRepository
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.WaypointRepository
import com.getfirepit.core.model.MapPin
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.protocol.ChannelSlotManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A node drawn on the map. */
data class MapMarker(
    val node: MeshNode,
    val isLive: Boolean,
    val isSelf: Boolean,
) {
    val tag: String get() = node.shortName?.takeIf { it.isNotBlank() } ?: "?"

    /** Below 32 bits the sender truncated their fix, so this is an area. */
    val isApproximate: Boolean get() = (node.positionPrecision ?: 32) < 32

    /**
     * Degrees clockwise from north, or null when they are not going anywhere.
     *
     * Walking pace is about 5 km/h; below the threshold a GPS course is mostly
     * the receiver wandering while still, which would spin the arrow.
     */
    val course: Float?
        get() = node.groundTrack
            ?.takeIf { (node.groundSpeed ?: 0) >= MOVING_KMH }
            ?.let { it / 100f }
            ?.takeIf { it in 0f..360f }

    private companion object {
        const val MOVING_KMH = 3
    }
}

data class MapUiState(
    val connected: Boolean = false,
    val markers: List<MapMarker> = emptyList(),
    val pins: List<MapPin> = emptyList(),
    val rooms: List<RoomChannel> = emptyList(),
    val sharingRoomId: Int? = null,
    val myNodeNum: Int? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val isSharing: Boolean get() = sharingRoomId != null
}

@HiltViewModel
class MapViewModel @Inject constructor(
    private val location: LocationRepository,
    private val waypoints: WaypointRepository,
    private val mesh: MeshRepository,
    private val offlineMaps: OfflineMapRepository,
    mapPreferences: MapPreferences,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val savedAreas = MutableStateFlow<List<OfflineArea>>(emptyList())

    init {
        viewModelScope.launch { savedAreas.value = offlineMaps.areas() }
    }

    val offlineOnly: StateFlow<Boolean> = mapPreferences.offlineOnly

    val areas: StateFlow<List<OfflineArea>> = savedAreas.asStateFlow()

    val uiState: StateFlow<MapUiState> = combine(
        mesh.isConnected,
        location.observePositions(),
        mesh.channels,
        mesh.myNodeNum,
        combine(busy, error, waypoints.observePins()) { busy, error, pins -> Triple(busy, error, pins) },
    ) { connected, nodes, channels, myNodeNum, (busy, error, pins) ->
        MapUiState(
            connected = connected,
            markers = nodes.map { node ->
                MapMarker(
                    node = node,
                    isLive = location.isLive(node),
                    isSelf = node.nodeNum == myNodeNum,
                )
            },
            pins = pins,
            rooms = ChannelSlotManager.rooms(channels),
            sharingRoomId = location.sharingRoomId(),
            myNodeNum = myNodeNum,
            busy = busy,
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState())

    /** Pins go on the room we share with, or the primary channel when sharing is off. */
    fun dropPin(latitudeI: Int, longitudeI: Int, name: String) {
        val channel = uiState.value.let { state ->
            state.rooms.firstOrNull { it.id == state.sharingRoomId }?.index ?: 0
        }
        run("Could not drop the pin") { waypoints.drop(channel, latitudeI, longitudeI, name) }
    }

    fun removePin(pin: MapPin) = run("Could not remove the pin") { waypoints.remove(pin) }

    /** Called while the map is on screen, so the phone's own fix can be shown. */
    fun setMapVisible(visible: Boolean) = location.setMapVisible(visible)

    /** Passing null stops sharing everywhere. */
    fun shareWith(roomId: Int?) = run("Could not change sharing") { location.shareWith(roomId) }

    fun clearError() {
        error.value = null
    }

    fun reportPermissionDenied() {
        error.value = "Firepit needs location permission to share where you are."
    }

    private fun run(fallback: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            runCatching { block() }.onFailure { cause -> error.value = cause.message ?: fallback }
            busy.value = false
        }
    }
}
