package com.getfirepit.core.data

import android.content.Context
import androidx.core.content.edit
import com.getfirepit.core.protocol.ShareDuration
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which room we share our position with, and when that stops, kept across
 * restarts.
 *
 * This is what sharing is: the phone sends a sealed position to this room while
 * it is set and the radio is connected. Kept on disk so a restart neither
 * forgets a share somebody chose nor revives one whose time has passed.
 */
@Singleton
class SharingStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("firepit_sharing", Context.MODE_PRIVATE)

    private val _deadline = MutableStateFlow(read())

    /** Null when nothing is shared, or when it was set to run until turned off. */
    val deadline: StateFlow<SharingDeadline?> = _deadline.asStateFlow()

    fun remember(roomId: Int, choice: ShareDuration, nowMillis: Long) {
        val endsAt = choice.endsAt(nowMillis)
        preferences.edit {
            putInt(KEY_ROOM, roomId)
            putString(KEY_CHOICE, choice.name)
            if (endsAt == null) remove(KEY_ENDS_AT) else putLong(KEY_ENDS_AT, endsAt)
        }
        _deadline.value = read()
    }

    fun clear() {
        preferences.edit { clear() }
        _deadline.value = null
    }

    private fun read(): SharingDeadline? {
        val roomId = preferences.getInt(KEY_ROOM, 0).takeIf { it != 0 } ?: return null
        return SharingDeadline(
            roomId = roomId,
            choice = ShareDuration.named(preferences.getString(KEY_CHOICE, null)),
            endsAt = preferences.getLong(KEY_ENDS_AT, 0L).takeIf { it != 0L },
        )
    }

    private companion object {
        const val KEY_ROOM = "room_id"
        const val KEY_CHOICE = "choice"
        const val KEY_ENDS_AT = "ends_at"
    }
}

data class SharingDeadline(
    val roomId: Int,
    val choice: ShareDuration,
    /** Null means it runs until somebody turns it off. */
    val endsAt: Long?,
) {
    fun hasPassed(nowMillis: Long): Boolean = endsAt != null && nowMillis >= endsAt
}
