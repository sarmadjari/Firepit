package com.getfirepit.app.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.data.LocationRepository
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.PositionAnswer
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
import kotlinx.coroutines.Job
import com.getfirepit.app.radio.SavedRadioStore
import com.getfirepit.app.settings.PersonStore
import com.getfirepit.core.protocol.Person
import com.getfirepit.core.protocol.SavedRadio
import com.getfirepit.core.protocol.SavedRadios
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition

/** What asking somebody's radio for its position is doing, one person at a time. */
sealed interface LocationAsk {
    data object Idle : LocationAsk
    data class Asking(val nodeNum: Int) : LocationAsk
    data class Answered(val nodeNum: Int, val said: String) : LocationAsk

    /** Working through everyone whose pin has gone old. */
    data class Sweeping(val done: Int, val total: Int) : LocationAsk
    data class Swept(val said: String) : LocationAsk

    val isBusy: Boolean get() = this is Asking || this is Sweeping
}

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
    /**
     * How long ago they were at this spot, in minutes. Null when nothing in
     * the fix says when it was taken, which is the one case where no honest
     * age can be shown.
     */
    val fixAgeMinutes: Long? = null,
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

/**
 * How much of the mesh the map draws.
 *
 * A public mesh puts strangers on your map by default. Most of the time the
 * only nodes worth looking at are the ones you share a room with and the
 * hardware you put out yourself.
 */
enum class MapFilter(val label: String) {
    ALL("All nodes"),
    OURS("Our nodes"),
}

data class MapUiState(
    val connected: Boolean = false,
    val markers: List<MapMarker> = emptyList(),
    val pins: List<MapPin> = emptyList(),
    val rooms: List<RoomChannel> = emptyList(),
    val sharingRoomId: Int? = null,
    val myNodeNum: Int? = null,
    /** Everyone in a room with us, which is everyone we have a private way to ask. */
    val roomMembers: Set<Int> = emptySet(),
    val filter: MapFilter = MapFilter.ALL,
    /** How many were left out by [filter], so a thinned map says so. */
    val hiddenByFilter: Int = 0,
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
    private val filter = MutableStateFlow(MapFilter.ALL)
    private val savedAreas = MutableStateFlow<List<OfflineArea>>(emptyList())

    init {
        viewModelScope.launch { savedAreas.value = offlineMaps.areas() }
    }

    val offlineOnly: StateFlow<Boolean> = mapPreferences.offlineOnly

    /**
     * Where the map was last looking. Folding, unfolding or rotating rebuilds the
     * map's view, and it picks up here rather than back at the world (UX §6.11.5).
     * Held in memory only: never saved or sent.
     */
    var camera: CameraPosition? = null

    /** The camera has been to everyone already, so it is not pulled there again on its own. */
    var framed by mutableStateOf(false)

    val areas: StateFlow<List<OfflineArea>> = savedAreas.asStateFlow()

    private val _ask = MutableStateFlow<LocationAsk>(LocationAsk.Idle)

    private var sweep: Job? = null

    /** What asking somebody for their position is doing, one at a time. */
    val ask: StateFlow<LocationAsk> = _ask.asStateFlow()

    /**
     * Asks [nodeNum]'s radio where it is.
     *
     * One at a time: the answer takes up to a minute to arrive, and a second
     * question in the meantime buys nothing but airtime.
     */
    fun askWhereTheyAre(nodeNum: Int, name: String) {
        if (_ask.value.isBusy) return
        viewModelScope.launch {
            _ask.value = LocationAsk.Asking(nodeNum)
            val answer = runCatching { location.requestPosition(nodeNum) }
                .getOrElse { PositionAnswer.Silent }
            _ask.value = LocationAsk.Answered(nodeNum, saidOf(answer, name))
        }
    }

    /**
     * Asks the people in your rooms for a fresh position.
     *
     * Only they can be asked: the question travels under a room's key or not
     * at all, so everything else the map is drawing — public mesh traffic, our
     * own base stations — is out of reach by design and left out of the count.
     *
     * Anyone whose pin is already current is skipped and said so, rather than
     * spending airtime on an answer we have.
     *
     * Nothing is waited for: answers move the pins as they arrive, and the
     * radio's own rate limit already spaces the questions ten seconds apart.
     */
    fun askEveryone() {
        if (_ask.value.isBusy) return
        val state = uiState.value
        val theirs = state.markers.filter { !it.isSelf && it.node.nodeNum in state.roomMembers }
        if (theirs.isEmpty()) {
            _ask.value = LocationAsk.Swept("Nobody from your rooms is on the map yet.")
            return
        }

        val stale = theirs.filter { (it.fixAgeMinutes ?: Long.MAX_VALUE) >= FRESH_MINUTES }
        val current = theirs.size - stale.size
        if (stale.isEmpty()) {
            _ask.value = LocationAsk.Swept(
                "Everyone in your rooms already has a current pin. " +
                    "Tap somebody to ask them anyway.",
            )
            return
        }

        sweep = viewModelScope.launch {
            var asked = 0
            var outOfReach = 0
            stale.forEachIndexed { index, marker ->
                _ask.value = LocationAsk.Sweeping(index + 1, stale.size)
                val answer = runCatching { location.askForPosition(marker.node.nodeNum) }
                    .getOrElse { PositionAnswer.NoSharedRoom }
                when (answer) {
                    PositionAnswer.Asked -> asked++
                    PositionAnswer.NotConnected -> {
                        _ask.value = LocationAsk.Swept("Connect your own radio first.")
                        return@launch
                    }
                    else -> outOfReach++
                }
            }
            _ask.value = LocationAsk.Swept(sweptWords(asked, outOfReach, current))
        }
    }

    fun clearAsk() {
        sweep?.cancel()
        sweep = null
        _ask.value = LocationAsk.Idle
    }

    private fun sweptWords(asked: Int, outOfReach: Int, current: Int): String = buildString {
        when (asked) {
            0 -> append("Nobody could be asked")
            1 -> append("Asked 1 person; their pin moves when they answer")
            else -> append("Asked $asked people; pins move as they answer")
        }
        if (current > 0) append(". $current already current")
        if (outOfReach > 0) append(". $outOfReach out of reach")
        append(".")
    }

    private fun saidOf(answer: PositionAnswer, name: String): String = when (answer) {
        PositionAnswer.Answered -> "$name answered. Their pin is where they are now."
        PositionAnswer.Asked -> "Asked $name. Their pin moves when they answer."
        PositionAnswer.Silent ->
            "No answer from $name. They are out of range, away from their radio, or not " +
                "sharing with a room you are both in — the pin still shows where they were last seen."
        PositionAnswer.NotConnected -> "Connect your own radio first."
        PositionAnswer.NoSharedRoom ->
            "$name is not in one of your Firepit rooms, so there is no private way to ask."
    }

    // A fix ages without anything else changing, so the map needs a reason to
    // redraw. Stops with the last subscriber, along with the state it feeds.
    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    val uiState: StateFlow<MapUiState> = combine(
        combine(
            mesh.isConnected,
            filter,
            rooms.observeGroupNodes(),
            rooms.observePersonCards(),
            ticker,
        ) { connected, filter, group, cards, now ->
            Lens(connected, filter, group, cards, now)
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
        // Ours is the room roster plus the hardware we put out ourselves; a
        // Base nobody has heard from yet is still one of ours.
        val ours = buildSet {
            myNodeNum?.let(::add)
            addAll(lens.group)
            aside.radios.mapNotNullTo(this) { it.nodeNum }
        }
        val onMap = nodes.filterNot { it.nodeNum in hidden }
        val shown = when (lens.filter) {
            MapFilter.ALL -> onMap
            MapFilter.OURS -> onMap.filter { it.nodeNum in ours }
        }
        MapUiState(
            connected = lens.connected,
            markers = shown.map { node ->
                // A card only reaches people we share a room with, so anyone
                // else is still drawn as the radio names itself.
                val isSelf = node.nodeNum == myNodeNum
                val person = aside.person.takeIf { isSelf }
                val card = lens.cards[node.nodeNum]
                // When the fix was taken, not when we last heard the radio. A
                // dot answers where somebody is, and a chatty radio that has
                // not sent a position in days is still a days-old dot. A clock
                // ahead of ours would read as the future, so it reads as now.
                val fixAge = (node.positionTime ?: node.lastHeard)
                    ?.let { (lens.now - it).coerceAtLeast(0) / 60_000 }
                MapMarker(
                    node = node,
                    isLive = fixAge != null && fixAge < LIVE_MINUTES,
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
                    fixAgeMinutes = fixAge,
                )
            },
            pins = aside.pins,
            rooms = ChannelSlotManager.rooms(channels),
            sharingRoomId = location.sharingRoomId(),
            myNodeNum = myNodeNum,
            roomMembers = lens.group,
            filter = lens.filter,
            hiddenByFilter = onMap.size - shown.size,
            busy = aside.busy,
            error = aside.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState())

    fun setFilter(choice: MapFilter) {
        filter.value = choice
    }

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

    private companion object {
        /** Well under a minute, so a label never sits on a stale figure for long. */
        const val TICK_MS = 20_000L

        /** How fresh a fix has to be for the dot to claim somebody is there now. */
        const val LIVE_MINUTES = 15L

        /**
         * Under this, asking again would not move the pin far enough to be
         * worth the airtime. Walking pace is about 80 metres a minute, so this
         * is roughly the width of the crowd you are trying to find them in.
         */
        const val FRESH_MINUTES = 2L
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
    val filter: MapFilter,
    val group: Set<Int>,
    val cards: Map<Int, PersonCard>,
    val now: Long,
)
