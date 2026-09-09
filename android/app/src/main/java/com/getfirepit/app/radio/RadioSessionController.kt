package com.getfirepit.app.radio

import android.content.Context
import com.getfirepit.core.transport.DiscoveredRadio
import com.getfirepit.core.transport.RadioLink
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ties the foreground service's lifetime to the radio session.
 *
 * Connecting from anywhere goes through here, so there is no path that starts a
 * session without the service that keeps it alive.
 */
@Singleton
class RadioSessionController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val link: RadioLink,
) {
    fun connect(radio: DiscoveredRadio) {
        // Started first: the service must reach startForeground quickly, and
        // waiting on the connection would risk the system's start timeout.
        RadioService.start(context)
        link.connect(radio)
    }

    suspend fun disconnect() {
        link.disconnect()
        RadioService.stop(context)
    }
}
