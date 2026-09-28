package com.getfirepit.core.data

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.getfirepit.core.crypto.RoomCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The keys that open rooms, held where the radio cannot reach.
 *
 * A room key has to stay exportable — an admin puts it into an invite — so it
 * cannot live inside the Keystore itself. Instead each one is wrapped by a
 * master key that never leaves secure hardware, so what sits in preferences is
 * useless to anyone who copies the file off the phone.
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
     * Rotating a room does not make its history unreadable: messages already on
     * the phone were sealed under the key of their day, and throwing that away
     * would delete the conversation rather than protect it.
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

    fun remember(roomId: Int, key: ByteArray, generation: Int = FIRST) {
        require(key.size == RoomCipher.KEY_SIZE) { "A room key is ${RoomCipher.KEY_SIZE} bytes" }
        preferences.edit {
            putString(slot(roomId, generation), Base64.encodeToString(wrap(key), Base64.NO_WRAP))
            // Never walk backwards: a late rotation message must not undo a
            // newer one that has already been applied.
            if (generation >= generationOf(roomId)) putInt(current(roomId), generation)
        }
        unwrapped[slot(roomId, generation)] = key.copyOf()
    }

    fun generate(roomId: Int, generation: Int = FIRST): ByteArray =
        RoomCipher.generateKey().also { remember(roomId, it, generation) }

    /** Leaving a room takes every key it ever had, or leaving would not mean much. */
    fun forget(roomId: Int) {
        preferences.edit {
            preferences.all.keys
                .filter { it == current(roomId) || it.startsWith("$roomId/") }
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
