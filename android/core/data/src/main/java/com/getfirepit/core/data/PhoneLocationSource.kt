package com.getfirepit.core.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.getSystemService
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The phone's own GPS.
 *
 * Uses the platform LocationManager rather than Play Services: an off-grid mesh
 * app should not require Google services to know where it is, and this works on
 * de-Googled devices.
 *
 * Nothing here starts until something collects, so no fix is ever requested
 * while location sharing is off.
 */
@Singleton
class PhoneLocationSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    @SuppressLint("MissingPermission")
    fun updates(interval: Duration = DEFAULT_INTERVAL): Flow<Location> = callbackFlow {
        val manager = context.getSystemService<LocationManager>()
            ?: run {
                close()
                return@callbackFlow
            }

        val listener = LocationListenerCompat { location -> trySend(location) }
        val request = LocationRequestCompat.Builder(interval.inWholeMilliseconds)
            .setQuality(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)
            .build()

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        Log.i("FirepitGps", "requesting updates from: $providers")
        if (providers.isEmpty()) {
            close()
            return@callbackFlow
        }

        providers.forEach { provider ->
            runCatching {
                LocationManagerCompat.requestLocationUpdates(
                    manager,
                    provider,
                    request,
                    listener,
                    Looper.getMainLooper(),
                )
            }
        }

        // A last known fix means the map has something to show immediately
        // instead of an empty screen while the first update arrives.
        providers.firstNotNullOfOrNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }?.let { trySend(it) }

        awaitClose { LocationManagerCompat.removeUpdates(manager, listener) }
    }

    fun hasAnyProvider(): Boolean {
        val manager = context.getSystemService<LocationManager>() ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    private companion object {
        val DEFAULT_INTERVAL = 30.seconds
    }
}
