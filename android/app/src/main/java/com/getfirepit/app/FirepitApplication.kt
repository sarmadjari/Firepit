package com.getfirepit.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.getfirepit.app.radio.RadioSessionController
import com.getfirepit.app.notifications.MessageNotifier
import com.getfirepit.core.data.ChatPresence
import com.getfirepit.core.data.LocationRepository
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.RoomRepository
import com.getfirepit.core.data.WaypointRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FirepitApplication : Application() {

    @Inject lateinit var meshRepository: MeshRepository

    @Inject lateinit var roomRepository: RoomRepository

    @Inject lateinit var locationRepository: LocationRepository

    @Inject lateinit var waypointRepository: WaypointRepository

    @Inject lateinit var mapPreferences: com.getfirepit.app.map.MapPreferences

    @Inject lateinit var messageNotifier: MessageNotifier

    @Inject lateinit var presence: ChatPresence

    @Inject lateinit var radioSession: RadioSessionController

    override fun onCreate() {
        super.onCreate()
        // The inbound pump must outlive every screen, so it starts here rather
        // than in a ViewModel.
        mapPreferences.apply()
        meshRepository.start()
        roomRepository.start()
        waypointRepository.start()
        locationRepository.start()
        messageNotifier.start()
        // Last, so the stores above are listening before packets arrive.
        radioSession.reconnectLastRadio()

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = presence.setForeground(true)
                override fun onStop(owner: LifecycleOwner) = presence.setForeground(false)
            },
        )
    }
}
