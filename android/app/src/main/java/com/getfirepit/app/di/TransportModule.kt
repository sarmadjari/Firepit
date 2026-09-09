package com.getfirepit.app.di

import com.getfirepit.core.transport.RadioLink
import com.getfirepit.core.transport.RadioScanner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Outlives any screen: the radio link must survive navigation and rotation. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object TransportModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideRadioScanner(): RadioScanner = RadioScanner()

    /**
     * One link for the Personal node. A radio accepts a single PhoneAPI client
     * at a time, so this must be a singleton.
     */
    @Provides
    @Singleton
    fun provideRadioLink(@ApplicationScope scope: CoroutineScope): RadioLink = RadioLink(scope)
}
