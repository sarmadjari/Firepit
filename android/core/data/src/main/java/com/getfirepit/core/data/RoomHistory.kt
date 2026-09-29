package com.getfirepit.core.data

import android.util.Log
import com.getfirepit.core.database.ChannelStateDao
import com.getfirepit.core.database.MapPinDao
import com.getfirepit.core.database.MessageDao
import com.getfirepit.core.database.RoomActivityDao
import com.getfirepit.core.database.followRoom
import com.getfirepit.core.database.parkOtherRooms
import com.getfirepit.core.database.parkUnfiled
import com.getfirepit.core.database.placeRoom
import com.getfirepit.core.database.restoreUnfiled
import com.getfirepit.core.database.setMuted
import com.getfirepit.core.database.stampSlot
import com.getfirepit.core.protocol.ChannelSlotManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps each room's history, pins, read position and mute with the room,
 * whichever radio carries it.
 *
 * A slot number is only a place on one radio. Another radio can carry another
 * room in the same slot, and the screen shows history by slot, so without this
 * one room's messages would appear under another's name. Every stored message
 * and pin records its room, and whenever a radio reports its channels each
 * room's history is moved to the slot the room is in now. History of a room
 * this radio does not carry is set aside until one that does connects, and a
 * channel with no id of its own — a Meshtastic one — keeps its history per
 * slot, set aside while a room holds that slot.
 */
@Singleton
class RoomHistory @Inject constructor(
    private val mesh: MeshRepository,
    private val messageDao: MessageDao,
    private val pinDao: MapPinDao,
    private val channelState: ChannelStateDao,
    private val roomActivity: RoomActivityDao,
    private val sessionStore: SessionStore,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * Held while slots are being rewritten, so history is never placed against
     * a half-finished layout: leaving a room writes several slots in turn, and
     * each write is visible at once.
     */
    private val rearranging = Mutex()

    /** Runs [block] — a sequence of slot writes — with placement held off until it is done. */
    suspend fun <T> whileRearranging(block: suspend () -> T): T = rearranging.withLock { block() }

    fun start() {
        scope.launch {
            mesh.channels
                .filter { it.isNotEmpty() }
                .map { channels -> ChannelSlotManager.rooms(channels).associate { it.index to it.id } }
                .distinctUntilChanged()
                .collect {
                    // Read afresh under the lock, which a rearrangement may have
                    // held until the layout this was queued for no longer exists.
                    rearranging.withLock {
                        runCatching { place() }.onFailure { Log.w(TAG, "could not place history", it) }
                    }
                }
        }
    }

    /** Mutes or unmutes the conversation in [channel], remembering it against its room too. */
    suspend fun setMuted(channel: Int, muted: Boolean) {
        val roomId = roomIn(channel)
        channelState.setMuted(channel, muted, roomId)
        if (roomId != 0) roomActivity.setMuted(roomId, muted, System.currentTimeMillis())
    }

    /** Which room a slot holds now, or 0 for a channel with no id of its own. */
    fun roomIn(channel: Int): Int =
        ChannelSlotManager.rooms(mesh.channels.value).firstOrNull { it.index == channel }?.id ?: 0

    private suspend fun place() {
        val rooms = ChannelSlotManager.rooms(mesh.channels.value)
        val now = System.currentTimeMillis()
        // Only once there are rooms to file under: a radio with none says
        // nothing about where older history belongs.
        val identified = rooms.filter { it.id != 0 }
        if (!sessionStore.historyFiledByRoom && identified.isNotEmpty()) {
            identified.forEach { room ->
                messageDao.stampSlot(room.index, room.id)
                pinDao.stampSlot(room.index, room.id)
                channelState.find(room.index)?.let { state ->
                    channelState.stampSlot(room.index, room.id)
                    if (state.muted) roomActivity.setMuted(room.id, true, now)
                }
            }
            sessionStore.historyFiledByRoom = true
        }

        identified.forEach { room ->
            messageDao.placeRoom(room.id, room.index)
            pinDao.placeRoom(room.id, room.index)
        }
        (ChannelSlotManager.FIRST_ROOM_SLOT..ChannelSlotManager.LAST_ROOM_SLOT).forEach { slot ->
            val roomId = rooms.firstOrNull { it.index == slot }?.id ?: 0
            messageDao.parkOtherRooms(slot, keep = roomId)
            pinDao.parkOtherRooms(slot, keep = roomId)
            if (roomId != 0) {
                messageDao.parkUnfiled(slot)
                pinDao.parkUnfiled(slot)
            } else {
                messageDao.restoreUnfiled(slot)
                pinDao.restoreUnfiled(slot)
            }
            channelState.followRoom(slot, roomId, now, roomMuted = roomId != 0 && roomActivity.isMuted(roomId))
        }
    }

    private companion object {
        const val TAG = "FirepitHistory"
    }
}
