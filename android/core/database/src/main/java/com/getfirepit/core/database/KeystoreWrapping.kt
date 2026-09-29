package com.getfirepit.core.database

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wrapping for secrets that have to sit in preferences.
 *
 * The wrapping key never leaves secure hardware, so what lands on disk is
 * useless to anyone who copies the file off the phone. Kept in one place
 * because there is more than one kind of secret that needs this — room keys,
 * this phone's own key, and the key the database is encrypted with — and two
 * copies of a cipher configuration is how they drift apart. It lives here, at
 * the bottom of the modules that need it, so the database can use it too.
 *
 * The keys are usable whenever the app runs, locked screen or not: messages
 * arrive while the phone is in a pocket. They do not survive the app's data
 * being copied to another phone, which is the protection they are for.
 */
object KeystoreWrapping {

    private const val TAG = "FirepitKeystore"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    /** What the Keystore's GCM implementation generates. */
    const val NONCE_SIZE = 12

    fun wrap(alias: String, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.ENCRYPT_MODE, master(alias)) }
        return cipher.iv + cipher.doFinal(plain)
    }

    /**
     * Null rather than throwing when the wrapping cannot be undone.
     *
     * The master key is gone if the screen lock was removed or the app's data
     * was restored onto another phone. Whatever it protected is then lost,
     * which is the correct outcome, but it is not a reason to take the app down.
     */
    fun unwrap(alias: String, stored: ByteArray): ByteArray? = try {
        Cipher.getInstance(TRANSFORMATION).run {
            init(
                Cipher.DECRYPT_MODE,
                master(alias),
                GCMParameterSpec(TAG_BITS, stored, 0, NONCE_SIZE),
            )
            doFinal(stored, NONCE_SIZE, stored.size - NONCE_SIZE)
        }
    } catch (cause: GeneralSecurityException) {
        Log.w(TAG, "$alias: wrapped bytes would not open", cause)
        null
    }

    private fun master(alias: String): SecretKey {
        val keystore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keystore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }
}
