package com.getfirepit.core.data

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.getfirepit.core.crypto.DirectSeal
import com.getfirepit.core.crypto.KeyEnvelope
import com.getfirepit.core.database.KeystoreWrapping
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement
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
class PhoneKeyStore internal constructor(
    context: Context,
    private val sources: List<PhoneKeySource>,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context,
        listOf(AndroidKeystorePhoneKeySource(), WrappedSoftwarePhoneKeySource),
    )

    private val preferences = context.getSharedPreferences("firepit_phone_key", Context.MODE_PRIVATE)

    internal data class Pair(val private: PrivateKey, val public: ByteArray, val source: PhoneKeySource)

    @Volatile
    private var loaded: Pair? = null

    /** The 33-byte public half, to hand to anyone who may need to seal us a key. */
    fun publicKey(): ByteString = pair().public.toByteString()

    internal fun kind(): String = pair().source.kind

    /** A room key sealed to this phone, or null when it was not, or was tampered with. */
    fun open(sealed: ByteArray, context: ByteArray, hedge: ByteArray? = null): ByteArray? {
        val pair = pair()
        return KeyEnvelope.open(pair.private, pair.public, sealed, context, hedge)
    }

    /** Seals [plaintext] from this phone to the phone holding [peerPublic]. See [DirectSeal]. */
    fun sealDirect(
        peerPublic: ByteArray,
        plaintext: ByteArray,
        context: ByteArray,
        room: DirectSeal.RoomSecret? = null,
    ): ByteArray {
        val pair = pair()
        return DirectSeal.seal(pair.private, pair.public, peerPublic, plaintext, context, room)
    }

    /** Null when this was not sealed between that phone and this one, or was tampered with. */
    fun openDirect(peerPublic: ByteArray, sealed: ByteArray, context: ByteArray): ByteArray? {
        return openDirect(peerPublic, sealed, context, emptyList())?.plain
    }

    /** Null when this was not sealed between that phone and this one, or was tampered with. */
    fun openDirect(
        peerPublic: ByteArray,
        sealed: ByteArray,
        context: ByteArray,
        rooms: Iterable<DirectSeal.RoomSecret>,
    ): DirectSeal.Opening? {
        val pair = pair()
        return DirectSeal.open(pair.private, pair.public, peerPublic, sealed, context, rooms)
    }

    private fun pair(): Pair = loaded ?: synchronized(this) { loaded ?: (restore() ?: create()).also { loaded = it } }

    private fun restore(): Pair? {
        val kind = preferences.getString(KIND, null) ?: if (preferences.contains(PRIVATE)) WrappedSoftwarePhoneKeySource.kind else null
        val source = sources.firstOrNull { it.kind == kind } ?: return null
        val public = preferences.getString(PUBLIC, null)
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?.takeIf(KeyEnvelope::isValidPublicKey)
            ?: return null
        val private = source.restore(preferences) ?: return null
        return Pair(private, public, source)
    }

    /**
     * A fresh pair, replacing one the Keystore can no longer open.
     *
     * That happens when the app's data reaches another phone or the Keystore is
     * reset; room keys held for this phone are lost with it, which is the same
     * outcome the room keys themselves already have.
     */
    private fun create(): Pair {
        sources.forEach { source ->
            val pair = runCatching { source.create() }
                .onFailure { Log.w(TAG, "could not generate ${source.kind} phone key", it) }
                .getOrNull()
                ?: return@forEach
            // Committed, not applied: peers seal keys to the public half from the
            // moment it is sent, so losing the pair to a crash would strand them.
            preferences.edit(commit = true) {
                putString(PUBLIC, Base64.encodeToString(pair.public, Base64.NO_WRAP))
                putString(KIND, source.kind)
                source.store(this, pair)
            }
            Log.i(TAG, "generated this phone's ${source.kind} key")
            return pair
        }
        error("no phone key source could generate a key")
    }

    internal interface PhoneKeySource {
        val kind: String
        fun restore(preferences: android.content.SharedPreferences): PrivateKey?
        fun create(): Pair
        fun store(editor: android.content.SharedPreferences.Editor, pair: Pair)
    }

    private object WrappedSoftwarePhoneKeySource : PhoneKeySource {
        override val kind = "wrapped-software"

        override fun restore(preferences: android.content.SharedPreferences): PrivateKey? {
            val wrapped = preferences.getString(PRIVATE, null) ?: return null
            val encoded = KeystoreWrapping.unwrap(ALIAS, Base64.decode(wrapped, Base64.NO_WRAP)) ?: return null
            return try {
                KeyEnvelope.restorePrivate(encoded)
            } finally {
                encoded.fill(0)
            }
        }

        override fun create(): Pair {
            val pair = KeyEnvelope.generateKeyPair()
            return Pair(pair.private, KeyEnvelope.publicBytes(pair.public), this)
        }

        override fun store(editor: android.content.SharedPreferences.Editor, pair: Pair) {
            val encoded = pair.private.encoded
            val wrapped = try {
                KeystoreWrapping.wrap(ALIAS, encoded)
            } finally {
                encoded.fill(0)
            }
            editor.putString(PRIVATE, Base64.encodeToString(wrapped, Base64.NO_WRAP))
        }
    }

    private class AndroidKeystorePhoneKeySource : PhoneKeySource {
        override val kind = "android-keystore"

        override fun restore(preferences: android.content.SharedPreferences): PrivateKey? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            return (store.getEntry(HARDWARE_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.privateKey
        }

        override fun create(): Pair {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) error("hardware phone keys need Android 12")
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(HARDWARE_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setIsStrongBoxBacked(false)
                    .build(),
            )
            val pair = generator.generateKeyPair()
            // Prove this KeyMint actually allows ECDH before peers learn the public key.
            KeyAgreement.getInstance("ECDH").run {
                init(pair.private)
                doPhase(pair.public, true)
                generateSecret()
            }
            return Pair(pair.private, KeyEnvelope.publicBytes(pair.public), this)
        }

        override fun store(editor: android.content.SharedPreferences.Editor, pair: Pair) {
            editor.remove(PRIVATE)
        }
    }

    private companion object {
        const val TAG = "FirepitPhoneKey"
        const val ALIAS = "firepit_phone_key_wrapping"
        const val HARDWARE_ALIAS = "firepit_phone_key_agreement"
        const val PRIVATE = "private"
        const val PUBLIC = "public"
        const val KIND = "kind"
    }
}
