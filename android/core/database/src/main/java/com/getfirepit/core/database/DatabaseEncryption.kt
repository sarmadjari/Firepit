package com.getfirepit.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase as PlatformDatabase
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.security.SecureRandom
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase

/**
 * The key the database is encrypted with, and the one move an older,
 * unencrypted database needs.
 *
 * Android's disk encryption only protects the app's files until the phone is
 * first unlocked after a restart. From then on anything that can read them — a
 * forensic copy of a locked phone, malware with root, a backup the OS gets
 * wrong — reads messages, positions and names straight out of SQLite. So the
 * file is encrypted by SQLCipher under a random key, and that key sits in
 * preferences wrapped by the Keystore like every other secret here.
 */
internal object DatabaseEncryption {

    private const val TAG = "FirepitDatabaseKey"
    private const val PREFERENCES = "firepit_database_key"
    private const val WRAPPED = "wrapped"
    private const val ALIAS = "firepit_database_key_wrapping"
    private const val KEY_SIZE = 32

    /** How every unencrypted SQLite file begins; an encrypted one looks like noise. */
    private val PLAIN_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * What SQLCipher opens [name] with, after making sure the file on disk is
     * encrypted with it.
     *
     * The raw-key form, `x'…'` with 64 hex digits, skips the passphrase
     * derivation SQLCipher would otherwise run on every open: the key is random
     * already, so stretching it buys nothing and costs a slow start.
     */
    fun prepare(context: Context, name: String): ByteArray {
        System.loadLibrary("sqlcipher")
        val database = context.getDatabasePath(name)
        val key = keyFor(context, database)
        val passphrase = "x'${key.toHex()}'".toByteArray(Charsets.US_ASCII)
        key.fill(0)
        encryptIfPlain(database, passphrase)
        return passphrase
    }

    /**
     * The wrapped key, or a new one when there is none that opens.
     *
     * A key that no longer unwraps means the Keystore lost its half — the app's
     * data restored onto another phone, or the Keystore reset. The database it
     * encrypted is then unreadable to anyone, this app included, so it is
     * removed rather than left to fail every start.
     */
    private fun keyFor(context: Context, database: File): ByteArray {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(WRAPPED, null)?.let { stored ->
            KeystoreWrapping.unwrap(ALIAS, Base64.decode(stored, Base64.NO_WRAP))?.let { return it }
            Log.w(TAG, "the database key would not unwrap; starting a new, empty database")
            deleteDatabaseFiles(database)
        }
        if (database.exists() && !isPlain(database)) {
            Log.w(TAG, "an encrypted database with no key to open it; starting a new, empty one")
            deleteDatabaseFiles(database)
        }

        val key = ByteArray(KEY_SIZE).also(SecureRandom()::nextBytes)
        // Committed, not applied: a database encrypted under a key that never
        // reached the disk would be lost on the next start.
        preferences.edit(commit = true) {
            putString(WRAPPED, Base64.encodeToString(KeystoreWrapping.wrap(ALIAS, key), Base64.NO_WRAP))
        }
        return key
    }

    /**
     * Rewrites an unencrypted database under [passphrase], once.
     *
     * SQLCipher's own export copies every table, index and row into a new
     * encrypted file, which then replaces the old one. The write-ahead log is
     * folded in first, so nothing it still held is left behind in the clear.
     * If any of it fails the unencrypted file is removed anyway: an empty
     * history is recoverable, a readable one on disk is not.
     */
    private fun encryptIfPlain(database: File, passphrase: ByteArray) {
        if (!database.exists() || !isPlain(database)) return
        val encrypted = File(database.parentFile, "${database.name}.encrypting")
        deleteDatabaseFiles(encrypted)

        try {
            PlatformDatabase.openDatabase(database.path, null, PlatformDatabase.OPEN_READWRITE).use { plain ->
                plain.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                plain.rawQuery("PRAGMA journal_mode = DELETE", null).use { it.moveToFirst() }
            }
            // Out of WAL mode now, so this is one connection: an ATTACH made on
            // one pooled connection is invisible to a query run on another.
            // And opened allowing creation, which ATTACH inherits: without it
            // SQLite cannot create the encrypted file at all.
            val (version, tables) = CipherDatabase.openDatabase(
                database.path,
                "",
                null,
                CipherDatabase.OPEN_READWRITE or CipherDatabase.CREATE_IF_NECESSARY,
                null,
                null,
            ).use { plain ->
                val key = String(passphrase, Charsets.US_ASCII)
                plain.execSQL("ATTACH DATABASE '${encrypted.path}' AS encrypted KEY \"$key\"")
                plain.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { it.moveToFirst() }
                plain.execSQL("PRAGMA encrypted.user_version = ${plain.version}")
                plain.execSQL("DETACH DATABASE encrypted")
                plain.version to tableCount(plain)
            }
            // Believed only once the copy opens under the key and holds the same.
            CipherDatabase.openDatabase(encrypted.path, passphrase, null, CipherDatabase.OPEN_READONLY, null, null)
                .use { copy ->
                    check(copy.version == version && tableCount(copy) == tables) {
                        "the encrypted copy does not match the original"
                    }
                }
            deleteDatabaseFiles(database)
            check(encrypted.renameTo(database)) { "could not move the encrypted database into place" }
            Log.i(TAG, "encrypted the existing database")
        } catch (cause: Exception) {
            Log.e(TAG, "could not encrypt the existing database; removing it rather than leaving it readable", cause)
            deleteDatabaseFiles(encrypted)
            deleteDatabaseFiles(database)
        }
    }

    private fun tableCount(database: CipherDatabase): Int =
        database.rawQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun isPlain(database: File): Boolean {
        val header = ByteArray(PLAIN_HEADER.size)
        val read = database.inputStream().use { it.read(header) }
        return read == header.size && header.contentEquals(PLAIN_HEADER)
    }

    private fun deleteDatabaseFiles(database: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix -> File(database.path + suffix).delete() }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
