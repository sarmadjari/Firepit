package com.getfirepit.core.data

import android.util.Log
import com.getfirepit.core.crypto.InviteCodec
import com.getfirepit.core.database.DeletedPinEntity
import com.getfirepit.core.database.MapPinDao
import com.getfirepit.core.database.find
import com.getfirepit.core.database.observeLive
import com.getfirepit.core.database.save
import com.getfirepit.core.model.MapPin
import com.getfirepit.core.protocol.MeshConstants
import com.getfirepit.core.protocol.TrustRules
import com.getfirepit.protocol.meshchat.MeshChatControl
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.Waypoint

/**
 * Pins on the map, sealed under the room's key like its words.
 *
 * A pin says where somebody's tent is, or where to meet, so it is as private as
 * anything typed: the channel key would leave it readable by whoever holds a
 * member's radio, and would let them plant or move pins under any name. Sealed,
 * only members see a pin and only a member can change one; the lock then keeps
 * it to its owner. The cost is that stock Meshtastic apps no longer see pins.
 *
 * There is no delete. Removing a pin means sending it again with an expiry in
 * the past, which every member then drops.
 */
@Singleton
class WaypointRepository @Inject constructor(
    private val mesh: MeshRepository,
    private val rooms: RoomRepository,
    private val pinDao: MapPinDao,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    fun observePins(): Flow<List<MapPin>> = pinDao.observeLive()

    fun start() {
        scope.launch {
            rooms.openedInRooms.collect { opened ->
                val pin = opened.control.pin ?: return@collect
                runCatching { handlePin(opened, pin) }
                    .onFailure { cause -> Log.w(TAG, "bad pin", cause) }
            }
        }
    }

    suspend fun drop(channel: Int, latitudeI: Int, longitudeI: Int, name: String, description: String = "") {
        val myNodeNum = mesh.myNodeNum.value ?: error("Not connected to a radio")
        val roomId = roomOf(channel)
        val pin = MapPin(
            id = newPinId(),
            channel = channel,
            latitudeI = latitudeI,
            longitudeI = longitudeI,
            name = limitName(name),
            description = limitDescription(description),
            // Locked to us so another member cannot move somebody else's pin
            // out from under them.
            lockedTo = myNodeNum,
            createdBy = myNodeNum,
            receivedAt = System.currentTimeMillis(),
            roomId = roomId,
        )
        send(pin.toWaypoint(), roomId)
        pinDao.save(pin)
        rooms.noteActivity(roomId)
        Log.i(TAG, "dropped pin ${pin.id} in room $roomId")
    }

    /**
     * Renames a pin everywhere by sending it again under the same id, which
     * every member treats as an edit.
     */
    suspend fun rename(pin: MapPin, name: String) {
        val myNodeNum = mesh.myNodeNum.value
        require(pin.canEdit(myNodeNum)) { "This pin is locked to whoever placed it" }

        val renamed = pin.copy(name = limitName(name))
        send(renamed.toWaypoint(), roomOf(pin))
        pinDao.save(renamed)
        Log.i(TAG, "renamed pin ${pin.id}")
    }

    /**
     * Removes a pin everywhere by sending it again, already expired.
     *
     * Sent first: deleting locally and then failing to send would leave the
     * pin on every other phone with no copy left here to expire it again. Sent
     * more than once because nothing confirms a room broadcast arrived
     * everywhere, and this is the one message whose loss cannot be repaired.
     */
    suspend fun remove(pin: MapPin) {
        val myNodeNum = mesh.myNodeNum.value
        require(pin.canEdit(myNodeNum)) { "This pin is locked to whoever placed it" }

        val roomId = roomOf(pin)
        val expired = pin.toWaypoint().copy(expire = EXPIRED)
        send(expired, roomId)

        pinDao.delete(pin.id)
        pinDao.remember(DeletedPinEntity(pin.id, pin.channel, System.currentTimeMillis()))
        Log.i(TAG, "removed pin ${pin.id}")

        scope.launch {
            repeat(EXPIRY_REPEATS - 1) {
                delay(EXPIRY_REPEAT_GAP)
                runCatching { send(expired, roomId) }
                    .onFailure { cause -> Log.w(TAG, "expiry repeat failed", cause) }
            }
        }
    }

    private suspend fun handlePin(opened: OpenedInRoom, waypoint: Waypoint) {
        val packet: MeshPacket = opened.packet
        val latitude = waypoint.latitude_i ?: return
        val longitude = waypoint.longitude_i ?: return
        if (latitude == 0 && longitude == 0) return

        // Only under the room's current key, on its own slot: that is what
        // makes the sender a member and binds their node number. A pin's lock
        // and its room are then its owner's: nobody else may move it, rename
        // it, expire it or carry it into another room by resending its id.
        val existing = pinDao.find(waypoint.id)
        val allowed = TrustRules.pinUpdateAllowed(
            sealedInRoom = opened.roomId.takeIf { opened.sealedUnderCurrent && opened.onItsSlot },
            sender = packet.from,
            claimedLock = waypoint.locked_to,
            existingLock = existing?.lockedTo,
            // An older pin not yet filed under a room counts as the room in its
            // slot now, and as no room at all when there is none: never "any".
            existingRoom = existing?.let { it.roomId.takeIf { id -> id != 0 } ?: mesh.roomIdForChannel(it.channel) ?: 0 },
        )
        if (!allowed) {
            Log.w(TAG, "ignoring pin ${waypoint.id} from ${packet.from} in room ${opened.roomId}")
            return
        }

        // Somebody who missed our expiry is still holding this one. Take it off
        // our map again and re-expire it, so the deletion keeps spreading.
        if (pinDao.wasDeleted(waypoint.id)) {
            Log.i(TAG, "ignoring resurrected pin ${waypoint.id} from ${packet.from}")
            if (waypoint.expire.toLong() !in 1 until System.currentTimeMillis() / 1000L) {
                scope.launch { runCatching { send(waypoint.copy(expire = EXPIRED), opened.roomId) } }
            }
            return
        }

        val pin = MapPin(
            id = waypoint.id,
            channel = packet.channel,
            latitudeI = latitude,
            longitudeI = longitude,
            name = limitName(waypoint.name),
            description = limitDescription(waypoint.description),
            expire = waypoint.expire.toLong(),
            lockedTo = waypoint.locked_to,
            icon = waypoint.icon.takeIf { it != 0 }?.let { String(Character.toChars(it)) },
            // Whoever dropped it stays its author, whoever last edited it.
            createdBy = existing?.createdBy ?: packet.from,
            receivedAt = System.currentTimeMillis(),
            roomId = opened.roomId,
        )

        if (pin.isExpired()) {
            pinDao.delete(pin.id)
            Log.i(TAG, "pin ${pin.id} expired by ${packet.from}")
        } else {
            pinDao.save(pin)
            rooms.noteActivity(opened.roomId)
        }
    }

    /** Sealed in the room, so it goes where the room is and nowhere else. */
    private suspend fun send(waypoint: Waypoint, roomId: Int) {
        val sent = rooms.sendSealed(
            roomId,
            MeshChatControl(version = InviteCodec.VERSION, pin = waypoint),
            priority = MeshPacket.Priority.RELIABLE,
        )
        check(sent) { "Pins can only be shared in a room this phone holds the key for" }
        Log.i(TAG, "sent pin ${waypoint.id} expire=${waypoint.expire} lockedTo=${waypoint.locked_to} room=$roomId")
    }

    /** The room a slot carries now; pins only ever live in one. */
    private fun roomOf(channel: Int): Int =
        mesh.roomIdForChannel(channel) ?: error("Pins can only be shared in a Firepit room")

    /** A pin's room: the one it was filed under, or for an older pin, the one in its slot now. */
    private fun roomOf(pin: MapPin): Int = pin.roomId.takeIf { it != 0 } ?: roomOf(pin.channel)

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

    private fun limitName(name: String) = MeshConstants.truncateToBytes(sanitizeMeshText(name), NAME_LIMIT)

    private fun limitDescription(description: String) =
        MeshConstants.truncateToBytes(sanitizeMeshText(description), DESCRIPTION_LIMIT)

    private companion object {
        const val TAG = "FirepitPins"

        /**
         * Bytes, not characters: the sealed pin has to fit one packet, and
         * ProtocolContractTest checks it does at exactly these limits.
         */
        const val NAME_LIMIT = 30
        const val DESCRIPTION_LIMIT = 100

        /** Epoch second 1: comfortably in the past, and not zero, which means "never". */
        const val EXPIRED = 1

        /** Nothing confirms a room broadcast reached everyone, so the deletion is said again. */
        const val EXPIRY_REPEATS = 3
        val EXPIRY_REPEAT_GAP = 10.seconds
    }
}
