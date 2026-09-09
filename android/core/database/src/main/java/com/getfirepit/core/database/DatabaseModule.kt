package com.getfirepit.core.database

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Keeps Room an implementation detail of the data layer. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FirepitDatabase =
        FirepitDatabase.create(context)

    @Provides
    fun provideMessageDao(database: FirepitDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideNodeDao(database: FirepitDatabase): NodeDao = database.nodeDao()

    @Provides
    fun provideRoomMemberDao(database: FirepitDatabase): RoomMemberDao = database.roomMemberDao()
}
