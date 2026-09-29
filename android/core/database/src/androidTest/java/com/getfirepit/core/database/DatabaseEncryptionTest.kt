package com.getfirepit.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase as PlatformDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.getfirepit.core.model.ChatMessage
import com.getfirepit.core.model.MessageStatus
import java.io.File
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The database really is encrypted on disk, and an older unencrypted one is
 * really rewritten rather than left in place.
 *
 * Checked against SQLCipher on a device rather than a fake: an earlier attempt
 * at this migration ran without error while leaving the plaintext file exactly
 * where it was, and only a test that read the bytes back caught it.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "encryption-test.db"
    private val words = "meet at the ridge at six"

    @Before
    fun setUp() = forgetEverything()

    @After
    fun tearDown() = forgetEverything()

    @Test
    fun anOlderUnencryptedDatabaseIsRewrittenEncryptedWithEverythingInIt() {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        // As an older version left it: write-ahead logging on, rows in the log.
        PlatformDatabase.openOrCreateDatabase(file, null).use { db ->
            db.enableWriteAheadLogging()
            db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, text TEXT NOT NULL)")
            repeat(ROWS) { db.execSQL("INSERT INTO messages (id, text) VALUES ($it, '$words $it')") }
            db.version = VERSION
        }
        assertTrue("the starting file should be plain", isPlain(file))

        val passphrase = DatabaseEncryption.prepare(context, name)

        assertFalse("the file on disk is still plain SQLite", isPlain(file))
        assertFalse("the words are readable in the file", file.readBytes().contains(words.toByteArray()))
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val leftover = File(file.path + suffix)
            assertFalse("a plaintext $suffix was left behind", leftover.exists() && leftover.readBytes().contains(words.toByteArray()))
        }
        CipherDatabase.openDatabase(file.path, passphrase, null, CipherDatabase.OPEN_READONLY, null, null).use { db ->
            assertEquals(VERSION, db.version)
            db.rawQuery("SELECT count(*) FROM messages", null).use { cursor ->
                cursor.moveToFirst()
                assertEquals(ROWS, cursor.getInt(0))
            }
        }
    }

    @Test
    fun theSameKeyOpensItNextTime() {
        val first = DatabaseEncryption.prepare(context, name)
        val second = DatabaseEncryption.prepare(context, name)

        assertArrayEquals(first, second)
    }

    /**
     * The Keystore losing its half leaves a file nobody can open. Starting
     * again empty is the only way forward that does not fail every launch.
     */
    @Test
    fun anEncryptedDatabaseWhoseKeyIsGoneIsReplacedRatherThanLeftToFail() {
        val passphrase = DatabaseEncryption.prepare(context, name)
        val file = context.getDatabasePath(name)
        CipherDatabase.openOrCreateDatabase(file, passphrase, null, null, null).use { db ->
            db.execSQL("CREATE TABLE kept (id INTEGER PRIMARY KEY)")
        }
        context.getSharedPreferences(KEY_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()

        val fresh = DatabaseEncryption.prepare(context, name)

        assertFalse("the unopenable file should be gone", file.exists())
        assertFalse(fresh.contentEquals(passphrase))
    }

    @Test
    fun roomOpensTheEncryptedDatabaseAndStoresIntoIt() = runBlocking<Unit> {
        context.deleteDatabase(ROOM_NAME)
        val db = FirepitDatabase.create(context)
        try {
            db.messageDao().save(
                ChatMessage(
                    id = 7,
                    channel = 1,
                    fromNodeNum = 11,
                    toNodeNum = -1,
                    text = words,
                    sentAt = 1_000,
                    status = MessageStatus.RECEIVED,
                    roomId = 99,
                ),
                myNodeNum = 22,
            )
            assertNotNull(db.messageDao().findEntity(7))
        } finally {
            db.close()
        }
        val file = context.getDatabasePath(ROOM_NAME)
        assertFalse("Room's database is plain SQLite", isPlain(file))
        assertFalse("the words are readable in the file", file.readBytes().contains(words.toByteArray()))
        context.deleteDatabase(ROOM_NAME)
    }

    private fun isPlain(file: File): Boolean {
        val header = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val read = ByteArray(header.size)
        return file.inputStream().use { it.read(read) } == header.size && read.contentEquals(header)
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    private fun forgetEverything() {
        context.deleteDatabase(name)
        File(context.getDatabasePath(name).path + ".encrypting").delete()
        context.getSharedPreferences(KEY_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private companion object {
        const val ROWS = 50
        const val VERSION = 11
        const val ROOM_NAME = "firepit.db"
        const val KEY_PREFERENCES = "firepit_database_key"
    }
}
