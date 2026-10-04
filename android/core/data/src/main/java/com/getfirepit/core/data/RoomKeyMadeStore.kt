package com.getfirepit.core.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** When this phone made each room generation, outside the Room schema. */
@Singleton
class RoomKeyMadeStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("firepit_room_keys_made", Context.MODE_PRIVATE)

    data class Record(val generation: Int, val madeAt: Long)

    fun record(roomId: Int): Record? {
        val encoded = preferences.getString(key(roomId), null) ?: return null
        val parts = encoded.split(":")
        return Record(parts.getOrNull(0)?.toIntOrNull() ?: return null, parts.getOrNull(1)?.toLongOrNull() ?: return null)
    }

    fun record(roomId: Int, generation: Int, at: Long) {
        preferences.edit { putString(key(roomId), "$generation:$at") }
    }

    fun markDue(roomId: Int, generation: Int) {
        preferences.edit { putBoolean(dueKey(roomId), true) }
        if (record(roomId) == null) record(roomId, generation, System.currentTimeMillis())
    }

    fun isDueNow(roomId: Int): Boolean = preferences.getBoolean(dueKey(roomId), false)

    fun clearDue(roomId: Int) {
        preferences.edit { remove(dueKey(roomId)) }
    }

    fun markMadeByMe(roomId: Int) {
        preferences.edit { putBoolean(makerKey(roomId), true) }
    }

    fun isMadeByMe(roomId: Int): Boolean = preferences.getBoolean(makerKey(roomId), false)

    fun clearMadeByMe(roomId: Int) {
        preferences.edit { remove(makerKey(roomId)) }
    }

    fun forget(roomId: Int) {
        preferences.edit {
            remove(key(roomId))
            remove(dueKey(roomId))
            remove(makerKey(roomId))
        }
    }

    private fun key(roomId: Int) = "room_$roomId"
    private fun dueKey(roomId: Int) = "due_$roomId"
    private fun makerKey(roomId: Int) = "maker_$roomId"

}
