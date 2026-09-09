package com.getfirepit.app

import android.app.Application
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.RoomRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FirepitApplication : Application() {

    @Inject lateinit var meshRepository: MeshRepository

    @Inject lateinit var roomRepository: RoomRepository

    override fun onCreate() {
        super.onCreate()
        // The inbound pump must outlive every screen, so it starts here rather
        // than in a ViewModel.
        meshRepository.start()
        roomRepository.start()
    }
}
