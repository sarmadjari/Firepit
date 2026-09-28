package com.getfirepit.app.settings

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.RoomRepository
import com.getfirepit.core.database.MessageDao
import com.getfirepit.core.database.newestPerChannel
import com.getfirepit.core.protocol.MessageRetention
import com.getfirepit.core.protocol.RoomLifetime
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How long this phone keeps messages.
 *
 * Only this phone. Everyone else holds their own copy and their own setting,
 * and nothing on a mesh can reach across to delete theirs.
 */
@Singleton
class RetentionStore @Inject constructor(
    @ApplicationContext context: Context,
    private val messageDao: MessageDao,
    private val rooms: RoomRepository,
    private val mesh: MeshRepository,
) {
    private val preferences = context.getSharedPreferences("firepit_retention", Context.MODE_PRIVATE)

    private val _choice = MutableStateFlow(MessageRetention.named(preferences.getString(KEY, null)))
    val choice: StateFlow<MessageRetention> = _choice.asStateFlow()

    private val _roomLifetime = MutableStateFlow(RoomLifetime.named(preferences.getString(ROOM_KEY, null)))
    val roomLifetime: StateFlow<RoomLifetime> = _roomLifetime.asStateFlow()

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

    /** Runs at launch and on change, so a phone left closed still catches up. */
    suspend fun sweep(nowMillis: Long = System.currentTimeMillis()) {
        val deleted = messageDao.deleteOlderThan(_choice.value.cutoff(nowMillis))
        if (deleted > 0) Log.i(TAG, "deleted $deleted messages past the retention window")
        forgetSilentRooms(nowMillis)
    }

    /**
     * Leaves rooms nobody has spoken in for longer than the chosen lifetime.
     *
     * Runs after the message sweep, so a room whose last words have just been
     * deleted is judged on having none rather than on what it used to hold.
     */
    private suspend fun forgetSilentRooms(nowMillis: Long) {
        val lifetime = _roomLifetime.value
        if (lifetime.silence == null) return

        val channels = mesh.channels.value.filter { it.isRoom }
        if (channels.isEmpty()) return

        val newest = messageDao.newestPerChannel()
        val byRoom = channels.associate { it.id to (newest[it.index] ?: 0L) }

        RoomLifetime.silentRooms(byRoom, lifetime, nowMillis).forEach { roomId ->
            Log.i(TAG, "leaving room $roomId, silent past the chosen lifetime")
            runCatching { rooms.leaveRoom(roomId) }
                .onFailure { cause -> Log.w(TAG, "could not leave silent room $roomId", cause) }
        }
    }

    private companion object {
        const val TAG = "FirepitRetention"
        const val KEY = "retention"
        const val ROOM_KEY = "room_lifetime"
    }
}
