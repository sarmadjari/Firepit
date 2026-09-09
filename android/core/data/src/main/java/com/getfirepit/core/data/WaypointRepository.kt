package com.getfirepit.core.data

import android.util.Log
import com.getfirepit.core.database.MapPinDao
import com.getfirepit.core.database.observeLive
import com.getfirepit.core.database.save
import com.getfirepit.core.model.BROADCAST_NODE_NUM
import com.getfirepit.core.model.MapPin
import com.getfirepit.core.protocol.MeshPacketBuilder
import com.getfirepit.core.protocol.OutboundPacer
import com.getfirepit.core.transport.RadioLink
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import okio.ByteString
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.Waypoint

/**
 * Pins on the map, carried as native Meshtastic waypoints so other clients see
 * them too.
 *
 * There is no delete on the wire. Removing a pin means re-broadcasting it with
 * an expiry in the past, which every client then drops.
 */
@Singleton
class WaypointRepository @Inject constructor(
    private val link: RadioLink,
    private val mesh: MeshRepository,
    private val pinDao: MapPinDao,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val pacer = OutboundPacer(System::currentTimeMillis)

    fun observePins(): Flow<List<MapPin>> = pinDao.observeLive()

    fun start() {
        scope.launch {
            link.inbound.collect { message ->
                message.packet?.let { packet ->
                    if (packet.decoded?.portnum == PortNum.WAYPOINT_APP) {
                        runCatching { handleWaypoint(packet) }
                            .onFailure { cause -> Log.w(TAG, "bad waypoint", cause) }
                    }
                }
            }
        }
    }

    suspend fun drop(channel: Int, latitudeI: Int, longitudeI: Int, name: String, description: String = "") {
        val myNodeNum = mesh.myNodeNum.value ?: error("Not connected to a radio")
        val pin = MapPin(
            id = newPinId(),
            channel = channel,
            latitudeI = latitudeI,
            longitudeI = longitudeI,
            name = sanitizeMeshText(name).take(NAME_LIMIT),
            description = sanitizeMeshText(description).take(DESCRIPTION_LIMIT),
            // Locked to us so a stranger on the channel cannot move somebody
            // else's pin out from under them.
            lockedTo = myNodeNum,
            createdBy = myNodeNum,
            receivedAt = System.currentTimeMillis(),
        )
        pinDao.save(pin)
        broadcast(pin.toWaypoint(), channel)
        Log.i(TAG, "dropped pin ${pin.id} on channel $channel")
    }

    /**
     * Removes a pin everywhere by re-sending it already expired. Local state is
     * cleared immediately so the map does not wait for our own broadcast.
     */
    suspend fun remove(pin: MapPin) {
        val myNodeNum = mesh.myNodeNum.value
        require(pin.canEdit(myNodeNum)) { "This pin is locked to whoever placed it" }

        pinDao.delete(pin.id)
        broadcast(pin.toWaypoint().copy(expire = EXPIRED), pin.channel)
        Log.i(TAG, "removed pin ${pin.id}")
    }

    private suspend fun handleWaypoint(packet: MeshPacket) {
        val data = packet.decoded ?: return
        val waypoint = Waypoint.ADAPTER.decode(data.payload)
        val latitude = waypoint.latitude_i ?: return
        val longitude = waypoint.longitude_i ?: return
        if (latitude == 0 && longitude == 0) return

        val pin = MapPin(
            id = waypoint.id,
            channel = packet.channel,
            latitudeI = latitude,
            longitudeI = longitude,
            name = sanitizeMeshText(waypoint.name).take(NAME_LIMIT),
            description = sanitizeMeshText(waypoint.description).take(DESCRIPTION_LIMIT),
            expire = waypoint.expire.toLong(),
            lockedTo = waypoint.locked_to,
            icon = waypoint.icon.takeIf { it != 0 }?.let { String(Character.toChars(it)) },
            createdBy = packet.from,
            receivedAt = System.currentTimeMillis(),
        )

        if (pin.isExpired()) {
            pinDao.delete(pin.id)
            Log.i(TAG, "pin ${pin.id} expired by ${packet.from}")
        } else {
            pinDao.save(pin)
        }
    }

    private suspend fun broadcast(waypoint: Waypoint, channel: Int) {
        pacer.awaitSlot(PortNum.WAYPOINT_APP)
        link.send(
            ToRadio(
                packet = MeshPacketBuilder.meshPacket(
                    to = BROADCAST_NODE_NUM,
                    channel = channel,
                    portNum = PortNum.WAYPOINT_APP,
                    payload = waypoint.encode().let(ByteString::of),
                    wantAck = true,
                ),
            ),
        )
    }

    private fun MapPin.toWaypoint() = Waypoint(
        id = id,
        latitude_i = latitudeI,
        longitude_i = longitudeI,
        expire = expire.toInt(),
        locked_to = lockedTo,
        name = name,
        description = description,
    )

    /** Non-zero so the firmware does not treat it as unset. */
    private fun newPinId(): Int {
        var id = 0
        while (id == 0) id = Random.nextInt()
        return id
    }

    private companion object {
        const val TAG = "FirepitPins"

        /** Proto limits: name 30 chars, description 100. */
        const val NAME_LIMIT = 30
        const val DESCRIPTION_LIMIT = 100

        /** Epoch second 1: comfortably in the past, and not zero, which means "never". */
        const val EXPIRED = 1
    }
}
