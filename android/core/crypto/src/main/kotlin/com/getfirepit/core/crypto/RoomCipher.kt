package com.getfirepit.core.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts a room's words with a key the radio never holds.
 *
 * The channel key cannot keep a room private, because it lives on the radio:
 * anyone holding the hardware can read it out with the official app and then
 * decrypt everything. Sealing the content again under a key that only ever sits
 * on members' phones leaves such a person with a timestamp and a node number,
 * and nothing to read.
 *
 * It also gives a room something Meshtastic channels do not have: tampering is
 * detected rather than delivered.
 *
 * AES-256-GCM from the platform, because inventing a construction here would be
 * the most dangerous thing in the codebase.
 */
object RoomCipher {

    const val KEY_SIZE = 32
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16

    /** What sealing costs against the text budget. */
    const val OVERHEAD = NONCE_SIZE + TAG_SIZE

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ALGORITHM = "AES"
    private val random = SecureRandom()

    fun generateKey(): ByteArray = ByteArray(KEY_SIZE).also(random::nextBytes)

    /**
     * Returns nonce followed by ciphertext.
     *
     * The nonce is random and sent rather than derived from anything in the
     * packet: repeating one under the same key breaks GCM completely, and a
     * packet id is not ours to guarantee unique.
     *
     * [context] is authenticated but not sent — bind the room and sender to it
     * so a sealed message cannot be replayed into another room or re-attributed.
     */
    fun seal(key: ByteArray, plaintext: ByteArray, context: ByteArray = ByteArray(0)): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keyOf(key), GCMParameterSpec(TAG_SIZE * 8, nonce))
            updateAAD(context)
        }
        return nonce + cipher.doFinal(plaintext)
    }

    /** Null when the key is wrong, the context differs, or a byte was changed. */
    fun open(key: ByteArray, sealed: ByteArray, context: ByteArray = ByteArray(0)): ByteArray? {
        if (sealed.size < NONCE_SIZE + TAG_SIZE) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    keyOf(key),
                    GCMParameterSpec(TAG_SIZE * 8, sealed, 0, NONCE_SIZE),
                )
                updateAAD(context)
            }
            cipher.doFinal(sealed, NONCE_SIZE, sealed.size - NONCE_SIZE)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun keyOf(key: ByteArray): SecretKeySpec {
        require(key.size == KEY_SIZE) { "A room key is $KEY_SIZE bytes, not ${key.size}" }
        return SecretKeySpec(key, ALGORITHM)
    }
}
