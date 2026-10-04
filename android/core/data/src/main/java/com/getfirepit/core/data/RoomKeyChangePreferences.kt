package com.getfirepit.core.data

import android.content.Context
import androidx.core.content.edit
import com.getfirepit.core.protocol.RoomKeyChange
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class RoomKeyChangePreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("firepit_room_keys", Context.MODE_PRIVATE)
    private val _choice = MutableStateFlow(read())
    val choice: StateFlow<RoomKeyChange> = _choice.asStateFlow()

    fun choose(choice: RoomKeyChange) {
        preferences.edit { putString(KEY, choice.name) }
        _choice.value = choice
    }

    private fun read(): RoomKeyChange =
        preferences.getString(KEY, null)?.let { runCatching { RoomKeyChange.valueOf(it) }.getOrNull() }
            ?: RoomKeyChange.DAILY

    private companion object {
        const val KEY = "room_key_change"
    }
}
