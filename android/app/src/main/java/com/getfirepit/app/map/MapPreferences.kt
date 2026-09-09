package com.getfirepit.app.map

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.MapLibre

/**
 * Map behaviour the user controls.
 *
 * Offline-only is applied to MapLibre itself rather than filtered in our code:
 * telling the SDK it has no connection is the only way to be sure nothing
 * fetches a tile, which is the whole point when data costs money.
 */
@Singleton
class MapPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val preferences = context.getSharedPreferences("firepit_map", Context.MODE_PRIVATE)

    private val _offlineOnly = MutableStateFlow(preferences.getBoolean(KEY_OFFLINE_ONLY, false))
    val offlineOnly: StateFlow<Boolean> = _offlineOnly.asStateFlow()

    /** Applied at startup so the choice survives a restart, not just a toggle. */
    fun apply() {
        MapLibre.getInstance(context)
        MapLibre.setConnected(!_offlineOnly.value)
    }

    fun setOfflineOnly(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_OFFLINE_ONLY, enabled) }
        _offlineOnly.value = enabled
        MapLibre.getInstance(context)
        MapLibre.setConnected(!enabled)
    }

    private companion object {
        const val KEY_OFFLINE_ONLY = "offline_only"
    }
}
