package com.getfirepit.core.data

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.getfirepit.core.crypto.RoomCipher
import com.getfirepit.core.database.KeystoreWrapping
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The keys that open rooms, held where the radio cannot reach.
 *
 * A room key has to leave the phone — sealed to another phone's key in a grant
 * or a rotation — so it cannot live inside the Keystore itself. Instead each one
 * is wrapped by a master key that never leaves secure hardware, so what sits in
 * preferences is useless to anyone who copies the file off the phone.
 */
@Singleton
class RoomKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences =
        context.getSharedPreferences("firepit_room_keys", Context.MODE_PRIVATE)

    /**
     * Unwrapped keys, so the Keystore is asked once per room rather than once
     * per message. A busy room decrypts on every arrival, and the platform asks
     * that its Keystore not be used on hot paths or the main thread.
     */
    private val unwrapped = ConcurrentHashMap<String, ByteArray>()

    /** The key a room is sealing with now, or null when it is not sealed. */
    fun keyFor(roomId: Int): ByteArray? = keyFor(roomId, generationOf(roomId))

    /**
     * A specific generation, because old keys are kept.
     *
     * History on the phone is stored already opened, in the encrypted
     * database, so this is not what keeps it readable. It is for packets sealed
     * just before a rotation that arrive after it, which would otherwise be
     * lost: the mesh delivers late and out of order.
     */
    fun keyFor(roomId: Int, generation: Int): ByteArray? {
        val slot = slot(roomId, generation)
        // A copy every time: the cached array is wiped when the room is left,
        // and a caller part-way through sealing must not have its key blanked.
        unwrapped[slot]?.let { return it.copyOf() }
        val stored = preferences.getString(slot, null) ?: return null
        return unwrap(Base64.decode(stored, Base64.NO_WRAP))?.also { unwrapped[slot] = it.copyOf() }
    }

    /** Which generation this room is sealing with. */
    fun generationOf(roomId: Int): Int = preferences.getInt(current(roomId), FIRST)

    /**
     * The key to seal with now, or null when there is none — or when the room
     * has moved to a key that never reached us. Old keys still open history,
     * but the only other people holding them are whoever was removed, so
     * nothing new is ever sealed under one.
     */
    fun sealingKey(roomId: Int): ByteArray? = if (isSuperseded(roomId)) null else keyFor(roomId)

    /** True when a member told us the room moved to a later key than the one we hold. */
    fun isSuperseded(roomId: Int): Boolean = preferences.getInt(superseded(roomId), 0) > generationOf(roomId)

    /** Records that [roomId] has moved on to [generation]; cleared once we hold that key. */
    fun markSuperseded(roomId: Int, generation: Int) {
        if (generation <= generationOf(roomId)) return
        preferences.edit { putInt(superseded(roomId), maxOf(generation, preferences.getInt(superseded(roomId), 0))) }
    }

    fun remember(roomId: Int, key: ByteArray, generation: Int = FIRST) {
        require(key.size == RoomCipher.KEY_SIZE) { "A room key is ${RoomCipher.KEY_SIZE} bytes" }
        preferences.edit {
            putString(slot(roomId, generation), Base64.encodeToString(wrap(key), Base64.NO_WRAP))
            // Never walk backwards: a late rotation message must not undo a
            // newer one that has already been applied.
            if (generation >= generationOf(roomId)) putInt(current(roomId), generation)
            // Holding the key the room moved to is the end of being left behind.
            if (generation >= preferences.getInt(superseded(roomId), 0)) remove(superseded(roomId))
        }
        unwrapped[slot(roomId, generation)] = key.copyOf()
    }

    fun generate(roomId: Int, generation: Int = FIRST): ByteArray =
        RoomCipher.generateKey().also { remember(roomId, it, generation) }

    /** Leaving a room takes every key it ever had, or leaving would not mean much. */
    fun forget(roomId: Int) {
        preferences.edit {
            preferences.all.keys
                .filter { it == current(roomId) || it == superseded(roomId) || it.startsWith("$roomId/") }
                .forEach { remove(it) }
        }
        // Leaving has to take the copies in memory too, or the room stays
        // readable for the life of the process.
        unwrapped.keys.filter { it.startsWith("$roomId/") }.forEach { slot ->
            unwrapped.remove(slot)?.fill(0)
        }
    }

    private fun slot(roomId: Int, generation: Int) = "$roomId/$generation"

    private fun current(roomId: Int) = "$roomId.generation"

    private fun superseded(roomId: Int) = "$roomId.superseded"

    private fun wrap(key: ByteArray): ByteArray = KeystoreWrapping.wrap(ALIAS, key)

    private fun unwrap(stored: ByteArray): ByteArray? = KeystoreWrapping.unwrap(ALIAS, stored)

    companion object {
        /** Rooms start here; a rotation is always one more. */
        const val FIRST = 1

        const val TAG = "FirepitRoomKeys"

        /** Unchanged since the first release: renaming it would orphan every stored key. */
        const val ALIAS = "firepit_room_key_wrapping"
    }
}
