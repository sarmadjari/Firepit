package com.getfirepit.core.data

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.getfirepit.core.crypto.DirectSeal
import com.getfirepit.core.crypto.KeyEnvelope
import com.getfirepit.core.database.KeystoreWrapping
import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.KeyAgreement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class PhoneKeyStoreTest {
    private lateinit var context: Context

    @Before fun clearStore() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteSharedPreferences("firepit_phone_key")
    }

    @Test fun hardwareKeyIsGeneratedAndNotExportableOnAndroid12() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val store = PhoneKeyStore(context)
        assertEquals(KeyEnvelope.PUBLIC_KEY_SIZE, store.publicKey().size)
        assertEquals("android-keystore", store.kind())
        assertArrayEquals(KeyEnvelope.publicBytes(androidPublicKey()), store.publicKey().toByteArray())
        assertNull(androidKey().encoded)
    }

    @Test fun hardwareKeyAgreementMatchesASoftwarePeer() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val store = PhoneKeyStore(context)
        val peer = KeyEnvelope.generateKeyPair()
        val fromPeer = KeyAgreement.getInstance("ECDH").run {
            init(peer.private)
            doPhase(androidPublicKey(), true)
            generateSecret()
        }
        val fromHardware = KeyAgreement.getInstance("ECDH").run {
            init(androidKey())
            doPhase(peer.public, true)
            generateSecret()
        }
        assertArrayEquals(fromPeer, fromHardware)
    }

    @Test fun hardwareKeyOpensKeyEnvelopesAndDirectSeals() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val store = PhoneKeyStore(context)
        val peer = KeyEnvelope.generateKeyPair()
        val peerPublic = KeyEnvelope.publicBytes(peer.public)
        val roomContext = KeyEnvelope.contextOf(roomId = 7, generation = 1, recipientNodeNum = 42, hour = 12)
        val secret = ByteArray(32) { it.toByte() }
        val envelope = KeyEnvelope.seal(store.publicKey().toByteArray(), secret, roomContext)
        assertArrayEquals(secret, store.open(envelope, roomContext))

        val directContext = DirectSeal.contextOf(senderNodeNum = 11, recipientNodeNum = 42)
        val words = "hello".encodeToByteArray()
        val v1 = DirectSeal.seal(peer.private, peerPublic, store.publicKey().toByteArray(), words, directContext)
        assertArrayEquals(words, store.openDirect(peerPublic, v1, directContext))

        val room = DirectSeal.RoomSecret(roomId = 7, generation = 1, hour = 12, key = ByteArray(32) { (it + 1).toByte() })
        val v2 = DirectSeal.seal(peer.private, peerPublic, store.publicKey().toByteArray(), words, directContext, room)
        assertArrayEquals(words, store.openDirect(peerPublic, v2, directContext, listOf(room))?.plain)
    }

    @Test fun fallsBackWhenTheFirstSourceCannotCreateAKey() {
        val store = PhoneKeyStore(context, listOf(FailingSource, SoftwareSource))
        assertEquals(KeyEnvelope.PUBLIC_KEY_SIZE, store.publicKey().size)
        assertEquals("test-software", store.kind())
    }

    @Test fun existingWrappedKeyIsKeptOnUpgrade() {
        val old = KeyEnvelope.generateKeyPair()
        val public = KeyEnvelope.publicBytes(old.public)
        val encoded = KeyEnvelope.privateBytes(old)
        val wrapped = try {
            KeystoreWrapping.wrap("firepit_phone_key_wrapping", encoded)
        } finally {
            encoded.fill(0)
        }
        context.getSharedPreferences("firepit_phone_key", Context.MODE_PRIVATE)
            .edit()
            .putString("private", Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .putString("public", Base64.encodeToString(public, Base64.NO_WRAP))
            .commit()
        assertFalse(context.getSharedPreferences("firepit_phone_key", Context.MODE_PRIVATE).contains("kind"))

        val upgraded = PhoneKeyStore(context)
        assertArrayEquals(public, upgraded.publicKey().toByteArray())
        assertEquals("wrapped-software", upgraded.kind())

        val roomContext = KeyEnvelope.contextOf(roomId = 8, generation = 1, recipientNodeNum = 43, hour = 13)
        val secret = ByteArray(32) { (it + 3).toByte() }
        assertArrayEquals(secret, upgraded.open(KeyEnvelope.seal(public, secret, roomContext), roomContext))

        val peer = KeyEnvelope.generateKeyPair()
        val peerPublic = KeyEnvelope.publicBytes(peer.public)
        val directContext = DirectSeal.contextOf(senderNodeNum = 43, recipientNodeNum = 44)
        val words = "old-key".encodeToByteArray()
        val sealed = upgraded.sealDirect(peerPublic, words, directContext)
        assertArrayEquals(words, DirectSeal.open(peer.private, peerPublic, public, sealed, directContext)?.plain)
        val peerSealed = DirectSeal.seal(peer.private, peerPublic, public, words, directContext)
        assertArrayEquals(words, upgraded.openDirect(peerPublic, peerSealed, directContext))
    }

    private fun androidKey(): PrivateKey {
        val entry = java.security.KeyStore.getInstance("AndroidKeyStore").run {
            load(null)
            getEntry("firepit_phone_key_agreement", null) as java.security.KeyStore.PrivateKeyEntry
        }
        return entry.privateKey
    }

    private fun androidPublicKey(): PublicKey {
        val entry = java.security.KeyStore.getInstance("AndroidKeyStore").run {
            load(null)
            getEntry("firepit_phone_key_agreement", null) as java.security.KeyStore.PrivateKeyEntry
        }
        return entry.certificate.publicKey
    }

    private object FailingSource : PhoneKeyStore.PhoneKeySource {
        override val kind = "failing"
        override fun restore(preferences: android.content.SharedPreferences): PrivateKey? = null
        override fun create(): PhoneKeyStore.Pair = error("no keymint")
        override fun store(editor: android.content.SharedPreferences.Editor, pair: PhoneKeyStore.Pair) = Unit
    }

    private object SoftwareSource : PhoneKeyStore.PhoneKeySource {
        override val kind = "test-software"
        override fun restore(preferences: android.content.SharedPreferences): PrivateKey? =
            preferences.getString("test_private", null)
                ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
                ?.let(KeyEnvelope::restorePrivate)

        override fun create(): PhoneKeyStore.Pair {
            val pair = KeyEnvelope.generateKeyPair()
            return PhoneKeyStore.Pair(pair.private, KeyEnvelope.publicBytes(pair.public), this)
        }
        override fun store(editor: android.content.SharedPreferences.Editor, pair: PhoneKeyStore.Pair) {
            editor.putString("test_private", android.util.Base64.encodeToString(pair.private.encoded, android.util.Base64.NO_WRAP))
        }
    }
}
