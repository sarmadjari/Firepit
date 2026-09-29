package com.getfirepit.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.getfirepit.app.notifications.MessageNotifier
import com.getfirepit.app.radio.RadioSessionController
import com.getfirepit.app.settings.RetentionStore
import com.getfirepit.core.data.ChatPresence
import com.getfirepit.core.data.LocationRepository
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.NodeClock
import com.getfirepit.core.data.RangeRepository
import com.getfirepit.core.data.RoomHistory
import com.getfirepit.core.data.RoomRepository
import com.getfirepit.core.data.WaypointRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FirepitApplication : Application() {

    @Inject lateinit var meshRepository: MeshRepository

    @Inject lateinit var roomRepository: RoomRepository

    @Inject lateinit var rangeRepository: RangeRepository

    @Inject lateinit var locationRepository: LocationRepository

    @Inject lateinit var waypointRepository: WaypointRepository

    @Inject lateinit var mapPreferences: com.getfirepit.app.map.MapPreferences

    @Inject lateinit var messageNotifier: MessageNotifier

    @Inject lateinit var nodeClock: NodeClock

    @Inject lateinit var presence: ChatPresence

    @Inject lateinit var radioSession: RadioSessionController

    @Inject lateinit var roomHistory: RoomHistory

    @Inject lateinit var retention: RetentionStore

    override fun onCreate() {
        super.onCreate()
        // The inbound pump must outlive every screen, so it starts here rather
        // than in a ViewModel.
        mapPreferences.apply()
        meshRepository.start()
        // Before anything can transmit: a radio still on the factory primary
        // announces its owner's name to every Meshtastic device in range.
        rangeRepository.start()
        roomRepository.start()
        roomHistory.start()
        waypointRepository.start()
        locationRepository.start()
        messageNotifier.start()
        nodeClock.start()
        // Now and every few hours: a phone left closed for a month catches up
        // the moment it starts, and one left running for days keeps up.
        retention.start()

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                private var reconnected = false

                override fun onStart(owner: LifecycleOwner) {
                    presence.setForeground(true)
                    // Not from onCreate: Android refuses a foreground service
                    // started from the background, and the reconnect scan can
                    // take half a minute to find the radio. Starting the search
                    // once we are actually in front of somebody means the
                    // service is allowed, and the link then survives the app
                    // being swiped away.
                    if (!reconnected) {
                        reconnected = true
                        radioSession.reconnectLastRadio()
                    }
                }

                override fun onStop(owner: LifecycleOwner) = presence.setForeground(false)
            },
        )
    }
}
