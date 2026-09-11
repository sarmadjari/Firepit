package com.getfirepit.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.getfirepit.core.crypto.RoomCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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
    @param:ApplicationContext context: Context,
) {
    private val preferences =
        context.getSharedPreferences("firepit_room_keys", Context.MODE_PRIVATE)

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
        val stored = preferences.getString(slot(roomId, generation), null) ?: return null
        return unwrap(Base64.decode(stored, Base64.NO_WRAP))
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
    }

    fun generate(roomId: Int, generation: Int = FIRST): ByteArray =
        RoomCipher.generateKey().also { remember(roomId, it, generation) }

    /** Leaving a room takes every key it ever had, or leaving would not mean much. */
    fun forget(roomId: Int) = preferences.edit {
        preferences.all.keys
            .filter { it == current(roomId) || it.startsWith("$roomId/") }
            .forEach { remove(it) }
    }

    private fun slot(roomId: Int, generation: Int) = "$roomId/$generation"

    private fun current(roomId: Int) = "$roomId.generation"

    private fun wrap(key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, master()) }
        return cipher.iv + cipher.doFinal(key)
    }

    /**
     * Null rather than throwing when the wrapping cannot be undone.
     *
     * The master key is gone if the screen lock was removed or the app's data
     * restored elsewhere. That makes the room unreadable, which is the correct
     * outcome, but it is not a reason to take the app down.
     */
    private fun unwrap(stored: ByteArray): ByteArray? = try {
        Cipher.getInstance(TRANSFORMATION).run {
            init(
                Cipher.DECRYPT_MODE,
                master(),
                GCMParameterSpec(RoomCipher.TAG_SIZE * 8, stored, 0, NONCE_SIZE),
            )
            doFinal(stored, NONCE_SIZE, stored.size - NONCE_SIZE)
        }
    } catch (cause: GeneralSecurityException) {
        Log.w(TAG, "a room key could not be unwrapped", cause)
        null
    }

    private fun master(): SecretKey {
        val keystore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keystore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        /** Rooms start here; a rotation is always one more. */
        const val FIRST = 1

        const val TAG = "FirepitRoomKeys"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "firepit_room_key_wrapping"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** What the Keystore's GCM implementation generates. */
        const val NONCE_SIZE = 12
    }
}
