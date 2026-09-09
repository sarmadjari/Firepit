package com.getfirepit.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.getfirepit.core.model.MessageStatus

internal class Converters {
    @TypeConverter
    fun toStatus(name: String): MessageStatus = MessageStatus.valueOf(name)

    @TypeConverter
    fun fromStatus(status: MessageStatus): String = status.name
}

@Database(
    entities = [MessageEntity::class, NodeEntity::class, RoomMemberEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FirepitDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun nodeDao(): NodeDao
    abstract fun roomMemberDao(): RoomMemberDao

    companion object {
        /** Adds the roster table. Messages and nodes are left untouched. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS room_members (
                        roomId INTEGER NOT NULL,
                        nodeNum INTEGER NOT NULL,
                        invitedBy INTEGER,
                        firstSeen INTEGER NOT NULL,
                        lastHeard INTEGER NOT NULL,
                        PRIMARY KEY(roomId, nodeNum)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Makes lastHeard nullable for members we have only been told about.
         * SQLite cannot relax NOT NULL in place, so the table is rebuilt.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE room_members_new (
                        roomId INTEGER NOT NULL,
                        nodeNum INTEGER NOT NULL,
                        invitedBy INTEGER,
                        firstSeen INTEGER NOT NULL,
                        lastHeard INTEGER,
                        PRIMARY KEY(roomId, nodeNum)
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "INSERT INTO room_members_new SELECT roomId, nodeNum, invitedBy, firstSeen, lastHeard " +
                        "FROM room_members",
                )
                connection.execSQL("DROP TABLE room_members")
                connection.execSQL("ALTER TABLE room_members_new RENAME TO room_members")
            }
        }

        fun create(context: Context): FirepitDatabase =
            Room.databaseBuilder(context, FirepitDatabase::class.java, "firepit.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
