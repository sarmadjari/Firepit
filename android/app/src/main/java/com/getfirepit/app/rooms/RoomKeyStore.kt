package com.getfirepit.app.rooms

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

    /** Null when this room has no Firepit key, which means it is not sealed. */
    fun keyFor(roomId: Int): ByteArray? {
        val stored = preferences.getString(roomId.toString(), null) ?: return null
        return unwrap(Base64.decode(stored, Base64.NO_WRAP))
    }

    fun remember(roomId: Int, key: ByteArray) {
        require(key.size == RoomCipher.KEY_SIZE) { "A room key is ${RoomCipher.KEY_SIZE} bytes" }
        preferences.edit {
            putString(roomId.toString(), Base64.encodeToString(wrap(key), Base64.NO_WRAP))
        }
    }

    fun generate(roomId: Int): ByteArray = RoomCipher.generateKey().also { remember(roomId, it) }

    /** Leaving a room takes its key with it, or leaving would not mean much. */
    fun forget(roomId: Int) = preferences.edit { remove(roomId.toString()) }

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

    private companion object {
        const val TAG = "FirepitRoomKeys"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "firepit_room_key_wrapping"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** What the Keystore's GCM implementation generates. */
        const val NONCE_SIZE = 12
    }
}
