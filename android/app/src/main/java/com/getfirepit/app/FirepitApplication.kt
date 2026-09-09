package com.getfirepit.app

import android.app.Application
import com.getfirepit.core.data.MeshRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FirepitApplication : Application() {

    @Inject lateinit var meshRepository: MeshRepository

    override fun onCreate() {
        super.onCreate()
        // The inbound pump must outlive every screen, so it starts here rather
        // than in a ViewModel.
        meshRepository.start()
    }
}
