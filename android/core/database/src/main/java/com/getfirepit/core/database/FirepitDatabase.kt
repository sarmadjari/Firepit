package com.getfirepit.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.getfirepit.core.model.MessageStatus

internal class Converters {
    @TypeConverter
    fun toStatus(name: String): MessageStatus = MessageStatus.valueOf(name)

    @TypeConverter
    fun fromStatus(status: MessageStatus): String = status.name
}

@Database(
    entities = [MessageEntity::class, NodeEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FirepitDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun nodeDao(): NodeDao

    companion object {
        fun create(context: Context): FirepitDatabase =
            Room.databaseBuilder(context, FirepitDatabase::class.java, "firepit.db").build()
    }
}
