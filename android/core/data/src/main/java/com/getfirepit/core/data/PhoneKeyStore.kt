package com.getfirepit.core.data

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.getfirepit.core.crypto.DirectSeal
import com.getfirepit.core.crypto.KeyEnvelope
import com.getfirepit.core.database.KeystoreWrapping
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.PrivateKey
import javax.inject.Inject
import javax.inject.Singleton
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * This phone's own key pair, which room keys and direct messages are sealed to.
 *
 * The radio's key is not enough: whoever holds the radio can read its private
 * key over Bluetooth, and with it anything encrypted to that radio. This one
 * exists only here, wrapped by the Keystore on disk and unwrapped into memory
 * when something sealed to it has to be opened.
 */
@Singleton
class PhoneKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("firepit_phone_key", Context.MODE_PRIVATE)

    private data class Pair(val private: PrivateKey, val public: ByteArray)

    @Volatile
    private var loaded: Pair? = null

    /** The 33-byte public half, to hand to anyone who may need to seal us a key. */
    fun publicKey(): ByteString = pair().public.toByteString()

    /** A room key sealed to this phone, or null when it was not, or was tampered with. */
    fun open(sealed: ByteArray, context: ByteArray): ByteArray? {
        val pair = pair()
        return KeyEnvelope.open(pair.private, pair.public, sealed, context)
    }

    /** Seals [plaintext] from this phone to the phone holding [peerPublic]. See [DirectSeal]. */
    fun sealDirect(peerPublic: ByteArray, plaintext: ByteArray, context: ByteArray): ByteArray {
        val pair = pair()
        return DirectSeal.seal(pair.private, pair.public, peerPublic, plaintext, context)
    }

    /** Null when this was not sealed between that phone and this one, or was tampered with. */
    fun openDirect(peerPublic: ByteArray, sealed: ByteArray, context: ByteArray): ByteArray? {
        val pair = pair()
        return DirectSeal.open(pair.private, pair.public, peerPublic, sealed, context)
    }

    private fun pair(): Pair = loaded ?: synchronized(this) { loaded ?: (restore() ?: create()).also { loaded = it } }

    private fun restore(): Pair? {
        val wrapped = preferences.getString(PRIVATE, null) ?: return null
        val public = preferences.getString(PUBLIC, null)
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?.takeIf(KeyEnvelope::isValidPublicKey)
            ?: return null
        val encoded = KeystoreWrapping.unwrap(ALIAS, Base64.decode(wrapped, Base64.NO_WRAP)) ?: return null
        val private = try {
            KeyEnvelope.restorePrivate(encoded)
        } finally {
            encoded.fill(0)
        } ?: return null
        return Pair(private, public)
    }

    /**
     * A fresh pair, replacing one the Keystore can no longer open.
     *
     * That happens when the app's data reaches another phone or the Keystore is
     * reset; room keys held for this phone are lost with it, which is the same
     * outcome the room keys themselves already have.
     */
    private fun create(): Pair {
        val pair = KeyEnvelope.generateKeyPair()
        val encoded = KeyEnvelope.privateBytes(pair)
        val public = KeyEnvelope.publicBytes(pair.public)
        val wrapped = try {
            KeystoreWrapping.wrap(ALIAS, encoded)
        } finally {
            encoded.fill(0)
        }
        // Committed, not applied: peers seal keys to the public half from the
        // moment it is sent, so losing the pair to a crash would strand them.
        preferences.edit(commit = true) {
            putString(PRIVATE, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            putString(PUBLIC, Base64.encodeToString(public, Base64.NO_WRAP))
        }
        Log.i(TAG, "generated this phone's key")
        return Pair(pair.private, public)
    }

    private companion object {
        const val TAG = "FirepitPhoneKey"
        const val ALIAS = "firepit_phone_key_wrapping"
        const val PRIVATE = "private"
        const val PUBLIC = "public"
    }
}
