package com.getfirepit.core.data

import android.location.Location
import android.util.Log
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.protocol.MeshPacketBuilder
import com.getfirepit.core.protocol.OutboundPacer
import com.getfirepit.core.protocol.PositionPrecision
import com.getfirepit.core.protocol.PositionSharing
import com.getfirepit.core.transport.RadioLink
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** Nodes with a known fix, newest sighting first. */
    fun observePositions(): Flow<List<MeshNode>> =
        mesh.observeNodes().map { nodes -> nodes.filter { it.hasPosition } }

    fun start() {
        scope.launch {
            // Re-check whenever the radio reports its channels, which happens on
            // every connect and after any channel write.
            mesh.channels.collect { channels ->
                if (channels.isNotEmpty() && !PositionSharing.isValid(channels)) {
                    val sharing = PositionSharing.sharingChannels(channels)
                    Log.w(TAG, "position enabled on ${sharing.size} channels; disabling all")
                    disableAll()
                }
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
     */
    suspend fun shareWith(roomId: Int?, precision: Int = PositionPrecision.FULL) {
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

    private suspend fun disableAll() {
        runCatching { shareWith(roomId = null) }
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
        val ModuleSettingsDefault = org.meshtastic.proto.ModuleSettings()
    }
}
