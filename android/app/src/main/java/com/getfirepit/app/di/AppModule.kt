package com.getfirepit.app.di

import android.content.Context
import com.getfirepit.core.data.ApplicationScope
import com.getfirepit.core.transport.BluetoothPresence
import com.getfirepit.core.transport.RadioLink
import com.getfirepit.core.transport.RadioScanner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideRadioScanner(): RadioScanner = RadioScanner()

    @Provides
    @Singleton
    fun provideBluetoothPresence(@ApplicationContext context: Context): BluetoothPresence =
        BluetoothPresence(context)

    /**
     * One link for the Personal node. A radio accepts a single PhoneAPI client
     * at a time, so this must be a singleton.
     */
    @Provides
    @Singleton
    fun provideRadioLink(@ApplicationScope scope: CoroutineScope): RadioLink = RadioLink(scope)
}
