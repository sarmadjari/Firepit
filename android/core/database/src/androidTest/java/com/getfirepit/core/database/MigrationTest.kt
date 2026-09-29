package com.getfirepit.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrading keeps what people already have, and lands exactly on the schema the
 * app expects. A migration that differs from the entities by one default value
 * does not fail here in a test — it fails on every upgraded phone at launch.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FirepitDatabase::class.java,
    )

    @Test
    fun elevenToTwelveKeepsMessagesPinsAndReadStateAndMatchesTheEntities() {
        helper.createDatabase(DB, 11).use { db ->
            db.execSQL(
                "INSERT INTO messages (id, channel, fromNodeNum, toNodeNum, peerNodeNum, text, sentAt, " +
                    "status, isOutgoing, signed) VALUES (1, 2, 3, -1, 3, 'kept', 100, 'RECEIVED', 0, 0)",
            )
            db.execSQL(
                "INSERT INTO map_pins (id, channel, latitudeI, longitudeI, name, description, expire, " +
                    "lockedTo, createdBy, receivedAt) VALUES (5, 2, 1, 1, 'camp', '', 0, 3, 3, 100)",
            )
            db.execSQL("INSERT INTO channel_state (channel, lastReadAt, muted) VALUES (2, 50, 1)")
        }

        helper.runMigrationsAndValidate(DB, 12, true, *FirepitDatabase.MIGRATIONS).use { db ->
            db.query("SELECT text, roomId FROM messages WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("kept", cursor.getString(0))
                // Filed under a room by RoomHistory once a radio says which.
                assertEquals(0, cursor.getInt(1))
            }
            db.query("SELECT name, roomId FROM map_pins WHERE id = 5").use { cursor ->
                cursor.moveToFirst()
                assertEquals("camp", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
            db.query("SELECT muted, roomId FROM channel_state WHERE channel = 2").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
            }
        }
    }

    /** Every version still in the wild reaches the current one through the chain. */
    @Test
    fun everyExportedVersionUpgradesToTheCurrentOne() {
        (1..11).forEach { version ->
            val name = "$DB-from-$version"
            helper.createDatabase(name, version).close()
            helper.runMigrationsAndValidate(name, 12, true, *FirepitDatabase.MIGRATIONS).close()
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
