package com.getfirepit.core.data

import android.content.Context
import androidx.core.content.edit
import com.getfirepit.core.protocol.BeaconRate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.meshtastic.proto.Config

/** App-owned location cadence, copied once from the radio for existing users. */
@Singleton
class LocationSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("firepit_location_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<LocationSettings> = _settings.asStateFlow()

    fun initializeFromRadio(position: Config.PositionConfig?) {
        if (preferences.getBoolean(KEY_INITIALIZED, false)) return
        if (position == null) return
        val rate = BeaconRate.of(position.position_broadcast_secs) ?: return
        val seconds = rate.seconds
        preferences.edit {
            putBoolean(KEY_INITIALIZED, true)
            putInt(KEY_RATE_SECONDS, seconds)
            putBoolean(KEY_WHEN_MOVED, position?.position_broadcast_smart_enabled == true)
            putInt(KEY_SMART_DISTANCE, position?.broadcast_smart_minimum_distance?.takeIf { it > 0 } ?: 100)
            putInt(KEY_SMART_INTERVAL, position?.broadcast_smart_minimum_interval_secs?.takeIf { it > 0 } ?: 30)
        }
        _settings.value = read()
    }

    fun setRate(rate: BeaconRate) {
        preferences.edit {
            putBoolean(KEY_INITIALIZED, true)
            putInt(KEY_RATE_SECONDS, rate.seconds)
        }
        _settings.value = read()
    }

    fun setWhenMoved(enabled: Boolean) {
        preferences.edit {
            putBoolean(KEY_INITIALIZED, true)
            putBoolean(KEY_WHEN_MOVED, enabled)
        }
        _settings.value = read()
    }

    private fun read(): LocationSettings =
        LocationSettings(
            rateSeconds = preferences.getInt(KEY_RATE_SECONDS, BeaconRate.FIRMWARE_DEFAULT_SECONDS),
            whenMoved = preferences.getBoolean(KEY_WHEN_MOVED, false),
            smartDistanceMetres = preferences.getInt(KEY_SMART_DISTANCE, 100),
            smartIntervalSeconds = preferences.getInt(KEY_SMART_INTERVAL, 30),
        )

    private companion object {
        const val KEY_INITIALIZED = "initialized"
        const val KEY_RATE_SECONDS = "rate_seconds"
        const val KEY_WHEN_MOVED = "when_moved"
        const val KEY_SMART_DISTANCE = "smart_distance_metres"
        const val KEY_SMART_INTERVAL = "smart_interval_seconds"
    }
}

data class LocationSettings(
    val rateSeconds: Int,
    val whenMoved: Boolean,
    val smartDistanceMetres: Int,
    val smartIntervalSeconds: Int,
)
