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
import com.getfirepit.core.model.ReceiptState

internal class Converters {
    @TypeConverter
    fun toStatus(name: String): MessageStatus = MessageStatus.valueOf(name)

    @TypeConverter
    fun fromStatus(status: MessageStatus): String = status.name

    @TypeConverter
    fun toReceiptState(name: String): ReceiptState = ReceiptState.valueOf(name)

    @TypeConverter
    fun fromReceiptState(state: ReceiptState): String = state.name
}

@Database(
    entities = [
        MessageEntity::class,
        NodeEntity::class,
        RoomMemberEntity::class,
        ChannelStateEntity::class,
        MapPinEntity::class,
        DeletedPinEntity::class,
        ReceiptEntity::class,
        PersonCardEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FirepitDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun nodeDao(): NodeDao
    abstract fun roomMemberDao(): RoomMemberDao
    abstract fun channelStateDao(): ChannelStateDao
    abstract fun mapPinDao(): MapPinDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun personCardDao(): PersonCardDao

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

        /** Adds per-channel read position and mute preference. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS channel_state (
                        channel INTEGER NOT NULL,
                        lastReadAt INTEGER NOT NULL,
                        muted INTEGER NOT NULL,
                        PRIMARY KEY(channel)
                    )
                    """.trimIndent(),
                )
                // Existing conversations start read, so upgrading does not
                // present a wall of unread badges for messages already seen.
                connection.execSQL(
                    "INSERT INTO channel_state (channel, lastReadAt, muted) " +
                        "SELECT DISTINCT channel, ${System.currentTimeMillis()}, 0 FROM messages",
                )
            }
        }

        /** Adds last known position to each node. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                listOf("latitudeI", "longitudeI", "altitude", "positionTime", "positionPrecision")
                    .forEach { column ->
                        connection.execSQL("ALTER TABLE nodes ADD COLUMN $column INTEGER")
                    }
            }
        }

        /** Adds map pins. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS map_pins (
                        id INTEGER NOT NULL,
                        channel INTEGER NOT NULL,
                        latitudeI INTEGER NOT NULL,
                        longitudeI INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        expire INTEGER NOT NULL,
                        lockedTo INTEGER NOT NULL,
                        icon TEXT,
                        createdBy INTEGER NOT NULL,
                        receivedAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
            }
        }

        /** Adds how fast a node is moving and which way. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE nodes ADD COLUMN groundSpeed INTEGER")
                connection.execSQL("ALTER TABLE nodes ADD COLUMN groundTrack INTEGER")
            }
        }

        /** Remembers deleted pins so a rebroadcast cannot resurrect them. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS deleted_pins (
                        id INTEGER NOT NULL,
                        channel INTEGER NOT NULL,
                        deletedAt INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Adds receipts, as a child of the message they describe.
         *
         * The cascade is the point: retention and leaving a room both delete
         * messages, and neither should have to know receipts exist.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS receipts (
                        messageId INTEGER NOT NULL,
                        nodeNum INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        at INTEGER NOT NULL,
                        PRIMARY KEY(messageId, nodeNum),
                        FOREIGN KEY(messageId) REFERENCES messages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_receipts_messageId ON receipts(messageId)",
                )
            }
        }

        /**
         * Adds the person cards room members send about themselves.
         *
         * Keyed by node and not tied to a room: leaving one room should not
         * forget who somebody is in another.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS person_cards (
                        nodeNum INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        tag TEXT NOT NULL,
                        colourSlot INTEGER,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(nodeNum)
                    )
                    """.trimIndent(),
                )
            }
        }

        fun create(context: Context): FirepitDatabase =
            Room.databaseBuilder(context, FirepitDatabase::class.java, "firepit.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                )
                .build()
    }
}
