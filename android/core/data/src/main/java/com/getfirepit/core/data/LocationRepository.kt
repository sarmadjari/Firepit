package com.getfirepit.core.data

import android.location.Location
import android.util.Log
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.protocol.MeshPacketBuilder
import com.getfirepit.core.protocol.OutboundPacer
import com.getfirepit.core.protocol.PositionPrecision
import com.getfirepit.core.protocol.PositionSharing
import com.getfirepit.core.protocol.ShareDuration
import com.getfirepit.core.transport.RadioLink
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okio.ByteString
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.ToRadio

/**
 * Position sharing, and the guarantee that it happens on one channel only.
 *
 * The radio transmits a position on every channel whose precision is non-zero.
 * Two enabled channels therefore means two audiences, one of which the user
 * never chose. The invariant is re-asserted on every connection rather than
 * trusted to whatever the UI last wrote, because the radio can be changed by
 * another app, another phone, or a factory reset between sessions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LocationRepository @Inject constructor(
    private val mesh: MeshRepository,
    private val admin: NodeAdminClient,
    private val link: RadioLink,
    private val phoneLocation: PhoneLocationSource,
    private val sharingStore: SharingStore,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val pacer = OutboundPacer(System::currentTimeMillis)

    /**
     * Set while the map is on screen.
     *
     * Seeing yourself on a map is a local question with no privacy consequence,
     * so it must not require opting into broadcasting. Sharing is the separate,
     * deliberate act below.
     */
    private val mapVisible = MutableStateFlow(false)

    fun setMapVisible(visible: Boolean) {
        mapVisible.value = visible
    }

    /** The room we currently share position with, or null when sharing is off. */
    fun sharingRoomId(): Int? = PositionSharing.sharingChannels(mesh.channels.value)
        .firstOrNull()
        ?.id
        ?.takeIf { it != 0 }

    /** When sharing stops on its own, or null when nothing stops it. */
    val sharingDeadline: StateFlow<SharingDeadline?> get() = sharingStore.deadline

    /** Nodes with a known fix, newest sighting first. */
    fun observePositions(): Flow<List<MeshNode>> =
        mesh.observeNodes().map { nodes -> nodes.filter { it.hasPosition } }

    fun start() {
        scope.launch {
            // Re-check whenever the radio reports its channels, which happens on
            // every connect and after any channel write.
            mesh.channels.collect { channels ->
                if (channels.isEmpty()) return@collect
                if (!PositionSharing.isValid(channels)) {
                    val sharing = PositionSharing.sharingChannels(channels)
                    Log.w(TAG, "position enabled on ${sharing.size} channels; disabling all")
                    disableAll()
                    return@collect
                }
                // The moment the radio becomes reachable, not 30 s later: a
                // deadline that ran out while the app was closed is already late.
                enforceDeadline()
            }
        }

        // The radio transmits with or without this app, so a deadline that only
        // ticked while we were running would be no deadline at all.
        scope.launch {
            while (true) {
                enforceDeadline()
                delay(DEADLINE_CHECK)
            }
        }

        scope.launch {
            // The GPS runs while the map is open or while sharing, and at no
            // other time. Nothing is transmitted unless a room is chosen.
            val sharing = mesh.channels.map { channels ->
                PositionSharing.sharingChannels(channels).isNotEmpty()
            }
            combine(mapVisible, sharing) { visible, sharing -> visible to sharing }
                .distinctUntilChanged()
                .flatMapLatest { (visible, sharing) ->
                    if (visible || sharing) {
                        phoneLocation.updates().map { location -> location to sharing }
                    } else {
                        emptyFlow()
                    }
                }
                .collect { (location, sharing) ->
                    storeOwnPosition(location)
                    if (sharing) publish(location)
                }
        }
    }

    /**
     * Records the phone's fix as our own node's position.
     *
     * Written straight to local storage rather than waiting for the radio to
     * tell us where we are: we already know, and the radio may never say.
     */
    private suspend fun storeOwnPosition(location: Location) {
        val myNodeNum = mesh.myNodeNum.value
        if (myNodeNum == null) {
            Log.w(TAG, "got a fix but no node number yet; connect the radio first")
            return
        }
        mesh.setOwnPosition(
            nodeNum = myNodeNum,
            latitudeI = (location.latitude * 1e7).toInt(),
            longitudeI = (location.longitude * 1e7).toInt(),
            altitude = location.altitude.toInt().takeIf { location.hasAltitude() },
            timeMillis = location.time,
        )
        Log.i(TAG, "own position stored from ${location.provider}, accuracy ${location.accuracy}m")
    }

    /**
     * Hands the phone's fix to the radio as its own position.
     *
     * Sent to our own node with hop limit 0, so it never goes on air itself.
     * The radio then broadcasts it on whichever channel has precision set,
     * applying the truncation for that channel.
     */
    private suspend fun publish(location: Location) {
        val myNodeNum = mesh.myNodeNum.value ?: return

        val position = Position(
            latitude_i = (location.latitude * 1e7).toInt(),
            longitude_i = (location.longitude * 1e7).toInt(),
            altitude = location.altitude.toInt().takeIf { location.hasAltitude() },
            time = (location.time / 1000L).toInt(),
            location_source = Position.LocSource.LOC_EXTERNAL,
            ground_speed = location.speed.toInt().takeIf { location.hasSpeed() },
            precision_bits = PositionPrecision.FULL,
        )

        pacer.awaitSlot(PortNum.POSITION_APP)
        runCatching {
            link.send(
                ToRadio(
                    packet = MeshPacketBuilder.localPacket(
                        myNodeNum = myNodeNum,
                        portNum = PortNum.POSITION_APP,
                        payload = position.encode().let(ByteString::of),
                    ),
                ),
            )
        }.onFailure { cause -> Log.w(TAG, "could not publish phone position", cause) }
    }

    /**
     * Shares position with [roomId] and nowhere else, or stops entirely when
     * it is null. Writes are derived from the radio's current state, so calling
     * this when nothing needs changing sends nothing.
     *
     * [PositionSharing] refuses a room that may not carry a position, so an
     * instruction naming one turns sharing off rather than honouring it.
     *
     * Private on purpose. Everything that turns sharing *on* must go through
     * [shareWith], which records when it stops; a caller that reached this
     * directly would be starting something with no end.
     */
    private suspend fun applySharing(roomId: Int?, precision: Int) {
        val writes = PositionSharing.writesToShareOnly(mesh.channels.value, roomId, precision)
        if (writes.isEmpty()) return

        writes.forEach { write ->
            val channel = admin.getChannel(write.index) ?: return@forEach
            val settings = channel.settings ?: return@forEach
            admin.setChannel(
                channel.copy(
                    settings = settings.copy(
                        module_settings = (settings.module_settings ?: ModuleSettingsDefault)
                            .copy(position_precision = write.precision),
                    ),
                ),
            )
        }
        Log.i(TAG, "position sharing set to room $roomId across ${writes.size} channel writes")
    }

    /**
     * Shares with [roomId] for a chosen length of time, or stops when it is null.
     *
     * The deadline is written down before the radio is, so a crash between the
     * two leaves a stopping point recorded for something that never started —
     * which costs nothing — rather than sharing with nothing to stop it.
     */
    suspend fun shareWith(
        roomId: Int?,
        choice: ShareDuration,
        precision: Int = PositionPrecision.FULL,
    ) {
        if (roomId == null) {
            stopSharing()
            return
        }
        sharingStore.remember(roomId, choice, System.currentTimeMillis())
        applySharing(roomId, precision)
    }

    /** Stops sharing if its time has run out. Safe to call as often as you like. */
    private suspend fun enforceDeadline() {
        val deadline = sharingStore.deadline.value ?: return
        if (!deadline.hasPassed(System.currentTimeMillis())) return
        Log.i(TAG, "sharing with room ${deadline.roomId} has run out; stopping")
        disableAll()
    }

    /** Stops sharing and forgets the deadline. */
    suspend fun stopSharing() {
        // The deadline is forgotten only once the radio has actually been told.
        // A radio we cannot see is still transmitting, and clearing first would
        // turn a share that was meant to end into one nothing will ever end.
        if (mesh.channels.value.isEmpty()) {
            Log.w(TAG, "cannot stop sharing yet: the radio has not reported its channels")
            return
        }
        applySharing(roomId = null, precision = PositionPrecision.FULL)
        sharingStore.clear()
    }

    private suspend fun disableAll() {
        runCatching { stopSharing() }
            .onFailure { cause -> Log.e(TAG, "could not disable position sharing", cause) }
    }

    /**
     * True when a node's fix is recent enough to draw as live.
     *
     * Uses `last_heard` rather than the position timestamp: 2.8 relays suppress
     * repeats of an identical position for hours, so a stationary node's fix
     * looks stale long before the node is.
     */
    fun isLive(node: MeshNode, now: Long = System.currentTimeMillis(), window: Duration = LIVE_WINDOW): Boolean =
        node.lastHeard?.let { now - it <= window.inWholeMilliseconds } == true

    private companion object {
        const val TAG = "FirepitLocation"
        val LIVE_WINDOW = 15.minutes

        /** Fine-grained enough that "for 1 hour" is not visibly a lie. */
        val DEADLINE_CHECK = 30.seconds
        val ModuleSettingsDefault = org.meshtastic.proto.ModuleSettings()
    }
}
