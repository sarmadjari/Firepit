package com.getfirepit.app.settings

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.getfirepit.app.map.OfflineMapRepository
import com.getfirepit.core.data.ApplicationScope
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.RoomRepository
import com.getfirepit.core.database.MapPinDao
import com.getfirepit.core.database.MessageDao
import com.getfirepit.core.database.NodeDao
import com.getfirepit.core.database.PeerKeyDao
import com.getfirepit.core.database.PersonCardDao
import com.getfirepit.core.database.RoomActivityDao
import com.getfirepit.core.model.RoomKind
import com.getfirepit.core.protocol.ChannelSlotManager
import com.getfirepit.core.protocol.MessageRetention
import com.getfirepit.core.protocol.RoomLifetime
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * How long this phone keeps what it learns.
 *
 * Messages are the obvious part, but not the only one: where everybody was,
 * who they said they were, their keys, the pins, and the map tiles looked at
 * are each a record of the trip. All of it ages out with the same window, and
 * none of it outlives the rooms it came from.
 *
 * Only this phone. Everyone else holds their own copy and their own setting,
 * and nothing on a mesh can reach across to delete theirs.
 */
@Singleton
class RetentionStore @Inject constructor(
    @ApplicationContext context: Context,
    private val messageDao: MessageDao,
    private val nodeDao: NodeDao,
    private val pinDao: MapPinDao,
    private val personCardDao: PersonCardDao,
    private val peerKeyDao: PeerKeyDao,
    private val roomActivity: RoomActivityDao,
    private val offlineMaps: OfflineMapRepository,
    private val rooms: RoomRepository,
    private val mesh: MeshRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val preferences = context.getSharedPreferences("firepit_retention", Context.MODE_PRIVATE)

    private val _choice = MutableStateFlow(MessageRetention.named(preferences.getString(KEY, null)))
    val choice: StateFlow<MessageRetention> = _choice.asStateFlow()

    private val _roomLifetime = MutableStateFlow(RoomLifetime.named(preferences.getString(ROOM_KEY, null)))
    val roomLifetime: StateFlow<RoomLifetime> = _roomLifetime.asStateFlow()

    /**
     * Sweeps now and then every few hours for as long as the app runs. The
     * radio keeps the app alive in the background for days, and a sweep that
     * only ran when a screen opened would never catch up with that.
     */
    fun start() {
        scope.launch {
            while (true) {
                runCatching { sweep() }.onFailure { cause -> Log.w(TAG, "retention sweep failed", cause) }
                delay(SWEEP_EVERY)
            }
        }
    }

    suspend fun chooseRoomLifetime(lifetime: RoomLifetime) {
        preferences.edit { putString(ROOM_KEY, lifetime.name) }
        _roomLifetime.value = lifetime
        sweep()
    }

    suspend fun choose(retention: MessageRetention) {
        preferences.edit { putString(KEY, retention.name) }
        _choice.value = retention
        sweep()
    }

    suspend fun sweep(nowMillis: Long = System.currentTimeMillis()) {
        val cutoff = _choice.value.cutoff(nowMillis)
        val deleted = messageDao.deleteOlderThan(cutoff)
        if (deleted > 0) Log.i(TAG, "deleted $deleted messages past the retention window")

        val positions = nodeDao.forgetPositionsBefore(cutoff)
        val strangers = mesh.myNodeNum.value?.let { nodeDao.forgetStrangersBefore(cutoff, keep = it) } ?: 0
        val pins = pinDao.deleteExpired(nowMillis / 1000L)
        pinDao.forgetDeletedBefore(nowMillis - TOMBSTONE_LIFETIME.inWholeMilliseconds)
        // Leaving a room removes its roster, and with it these.
        val cards = personCardDao.forgetOutsideRooms()
        val keys = peerKeyDao.forgetOutsideRooms()
        if (positions + strangers + pins + cards + keys > 0) {
            Log.i(TAG, "forgot $positions positions, $strangers nodes, $pins pins, $cards cards, $keys keys")
        }

        // Once per window: clearing costs the tiles being fetched again.
        val window = nowMillis - cutoff
        val sinceCleared = nowMillis - preferences.getLong(KEY_TILES_CLEARED, 0L)
        if (sinceCleared >= window && offlineMaps.forgetBrowsedTiles()) {
            preferences.edit { putLong(KEY_TILES_CLEARED, nowMillis) }
        }

        forgetSilentRooms(nowMillis)
    }

    /**
     * Everything this phone has kept about what was said and where anyone
     * was, gone at once. Rooms and their keys stay: leaving a room is its own
     * decision, and takes them with it.
     */
    suspend fun eraseHistory() {
        messageDao.deleteAll()
        pinDao.deleteAll()
        pinDao.forgetAllDeleted()
        nodeDao.forgetAllPositions()
        personCardDao.deleteAll()
        offlineMaps.forgetBrowsedTiles()
        Log.i(TAG, "erased this phone's history")
    }

    /**
     * Leaves rooms nobody has spoken in for longer than the chosen lifetime.
     *
     * Judged on when each room last had anything said, pinned or shared in
     * it, which is recorded as it happens: the messages themselves may already
     * be gone to the retention window, which is never longer than a lifetime.
     * A room with no record yet — held from before this was kept — starts its
     * clock now rather than being judged on nothing.
     */
    private suspend fun forgetSilentRooms(nowMillis: Long) {
        val lifetime = _roomLifetime.value
        if (lifetime.silence == null) return

        val held = ChannelSlotManager.rooms(mesh.channels.value)
            .filter { it.id != 0 && (it.kind == RoomKind.FIREPIT || it.kind.isStalledRoom) }
        if (held.isEmpty()) return

        val known = roomActivity.all().associateBy { it.roomId }
        held.filter { it.id !in known }.forEach { roomActivity.joined(it.id, nowMillis) }
        val lastActivity = held.associate { room ->
            room.id to (known[room.id]?.let { maxOf(it.joinedAt, it.lastActivityAt) } ?: nowMillis)
        }

        RoomLifetime.silentRooms(lastActivity, lifetime, nowMillis).forEach { roomId ->
            Log.i(TAG, "leaving room $roomId, silent past the chosen lifetime")
            runCatching { rooms.leaveRoom(roomId, announce = false) }
                .onFailure { cause -> Log.w(TAG, "could not leave silent room $roomId", cause) }
        }
    }

    private companion object {
        const val TAG = "FirepitRetention"
        const val KEY = "retention"
        const val ROOM_KEY = "room_lifetime"
        const val KEY_TILES_CLEARED = "tiles_cleared_at"

        val SWEEP_EVERY = 6.hours

        /** A deletion only needs remembering while somebody could still resend the pin. */
        val TOMBSTONE_LIFETIME = 30.days
    }
}
