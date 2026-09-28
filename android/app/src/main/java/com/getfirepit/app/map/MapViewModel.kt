package com.getfirepit.app.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.data.LocationRepository
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.RoomRepository
import com.getfirepit.core.data.WaypointRepository
import com.getfirepit.core.model.MapPin
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.model.PersonCard
import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.protocol.ChannelSlotManager
import com.getfirepit.core.protocol.NodeRole
import com.getfirepit.core.protocol.PositionSharing
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.getfirepit.app.radio.SavedRadioStore
import com.getfirepit.app.settings.PersonStore
import com.getfirepit.core.protocol.Person
import com.getfirepit.core.protocol.SavedRadio
import com.getfirepit.core.protocol.SavedRadios
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
    /** Who is carrying the radio, when this phone knows. Otherwise what the radio calls itself. */
    val name: String,
    /** The 2-character disc label, from the person before the radio. */
    val tag: String,
    /** Set only for our own infrastructure, which is drawn as its role rather than a tag. */
    val role: NodeRole? = null,
    /** A colour picked by hand, or null to derive one from the node number. */
    val colourSlot: Int? = null,
) {

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
    private val savedRadios: SavedRadioStore,
    private val people: PersonStore,
    rooms: RoomRepository,
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
        combine(
            mesh.isConnected,
            rooms.observePersonCards(),
        ) { connected, cards ->
            Lens(connected, cards)
        },
        location.observePositions(),
        mesh.channels,
        mesh.myNodeNum,
        combine(
            busy,
            error,
            waypoints.observePins(),
            savedRadios.radios,
            people.person,
        ) { busy, error, pins, radios, person -> Aside(busy, error, pins, radios, person) },
    ) { lens, nodes, channels, myNodeNum, aside ->
        val hidden = SavedRadios.hiddenNodes(aside.radios)
        MapUiState(
            connected = lens.connected,
            markers = nodes.filterNot { it.nodeNum in hidden }.map { node ->
                val isSelf = node.nodeNum == myNodeNum
                val person = aside.person.takeIf { isSelf }
                val card = lens.cards[node.nodeNum]
                MapMarker(
                    node = node,
                    isLive = location.isLive(node),
                    isSelf = isSelf,
                    name = person?.name?.takeIf { it.isNotBlank() }
                        ?: card?.name?.takeIf { it.isNotBlank() }
                        ?: if (isSelf) "You" else node.displayName,
                    tag = person?.tag?.takeIf { it.isNotBlank() }
                        ?: card?.tag?.takeIf { it.isNotBlank() }
                        ?: node.shortName?.takeIf { it.isNotBlank() }
                        ?: "?",
                    // A base or a router is a thing, not a person: its initials
                    // say nothing a reader wants, so it wears its role instead.
                    // A card outranks it — only a person can send one.
                    role = aside.radios
                        .firstOrNull { it.nodeNum == node.nodeNum }
                        ?.role
                        ?.takeIf { it != NodeRole.PERSONAL }
                        ?.takeIf { card == null && !isSelf },
                    colourSlot = person?.colourSlot,
                )
            },
            pins = aside.pins,
            rooms = ChannelSlotManager.rooms(channels),
            sharingRoomId = location.sharingRoomId(),
            myNodeNum = myNodeNum,
            busy = aside.busy,
            error = aside.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState())

    /**
     * Pins carry a place and a name, so they go where a position would: one
     * private room, never the primary and never a shared Meshtastic channel.
     *
     * The room we already share location with when there is one, since that is
     * the group looking at the same map; otherwise the only private room there
     * is. Anything else would be a guess about who should see it.
     */
    fun dropPin(latitudeI: Int, longitudeI: Int, name: String) {
        val state = uiState.value
        val shareable = state.rooms.filter(PositionSharing::canShare)
        val room = shareable.firstOrNull { it.id == state.sharingRoomId }
            ?: shareable.singleOrNull()

        if (room == null) {
            error.value = if (shareable.isEmpty()) {
                "Pins go to one private room. Create or join a private room first — a pin " +
                    "carries a place and a name, so it is never put on a public channel."
            } else {
                "Choose which room to share your map with first, so the pin has somewhere " +
                    "to go."
            }
            return
        }
        run("Could not drop the pin") {
            waypoints.drop(room.index, latitudeI, longitudeI, name)
        }
    }

    fun removePin(pin: MapPin) = run("Could not remove the pin") { waypoints.remove(pin) }

    /** Called while the map is on screen, so the phone's own fix can be shown. */
    fun setMapVisible(visible: Boolean) = location.setMapVisible(visible)

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

/** Combine takes five sources at most; these five travel together. */
private data class Aside(
    val busy: Boolean,
    val error: String?,
    val pins: List<MapPin>,
    val radios: List<SavedRadio>,
    val person: Person?,
)

/** Likewise: what the map is looking at, rather than what it is looking for. */
private data class Lens(
    val connected: Boolean,
    val cards: Map<Int, PersonCard>,
)
