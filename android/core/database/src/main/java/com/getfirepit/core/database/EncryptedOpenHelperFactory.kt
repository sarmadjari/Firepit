package com.getfirepit.core.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Opens the database through SQLCipher, but only when it is first used.
 *
 * Getting ready — loading SQLCipher, unwrapping its key, and on the first start
 * after an upgrade rewriting an unencrypted file — takes real time. Room builds
 * the database the moment something asks for a DAO, which at start-up is the
 * main thread; it only opens it to run a query, which it never does there. So
 * that work waits for the first open, off the main thread.
 */
internal class EncryptedOpenHelperFactory : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        Deferred(configuration)

    private class Deferred(private val configuration: SupportSQLiteOpenHelper.Configuration) : SupportSQLiteOpenHelper {

        private val lock = Any()

        /** Asked for before the database exists; applied once it does. */
        private var writeAheadLogging: Boolean? = null

        private var opened: SupportSQLiteOpenHelper? = null

        private fun helper(): SupportSQLiteOpenHelper = synchronized(lock) {
            opened ?: run {
                val name = requireNotNull(configuration.name) { "an encrypted database needs a file" }
                SupportOpenHelperFactory(DatabaseEncryption.prepare(configuration.context, name), null, true)
                    .create(configuration)
                    .also { helper ->
                        writeAheadLogging?.let(helper::setWriteAheadLoggingEnabled)
                        opened = helper
                    }
            }
        }

        override val databaseName: String? get() = configuration.name

        override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
            synchronized(lock) {
                writeAheadLogging = enabled
                opened?.setWriteAheadLoggingEnabled(enabled)
            }
        }

        override val writableDatabase: SupportSQLiteDatabase get() = helper().writableDatabase

        override val readableDatabase: SupportSQLiteDatabase get() = helper().readableDatabase

        override fun close() {
            synchronized(lock) { opened?.close() }
        }
    }
}
