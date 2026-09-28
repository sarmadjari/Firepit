package com.getfirepit.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.getfirepit.core.crypto.RoomCipher
import com.getfirepit.core.crypto.SealedText
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a device because the wrapping key lives in the Android Keystore,
 * which has no counterpart on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class RoomKeyStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: RoomKeyStore

    private val room = 4242

    @Before
    fun setUp() {
        store = RoomKeyStore(context)
        store.forget(room)
    }

    @After
    fun tearDown() = store.forget(room)

    @Test
    fun aRoomWithNoKeyIsNotSealed() {
        assertNull(store.keyFor(room))
    }

    @Test
    fun aGeneratedKeyIsReadBackUnchanged() {
        val key = store.generate(room)

        assertArrayEquals(key, store.keyFor(room))
        assertEquals(RoomCipher.KEY_SIZE, key.size)
    }

    @Test
    fun aKeyFromAnInviteIsKeptAsGiven() {
        val fromInvite = RoomCipher.generateKey()

        store.remember(room, fromInvite)

        assertArrayEquals(fromInvite, store.keyFor(room))
    }

    @Test
    fun theStoredFormIsNotTheKey() {
        val key = store.generate(room)
        val stored = context.getSharedPreferences("firepit_room_keys", 0)
            .getString(room.toString(), null)!!

        assertFalse("the key is sitting in preferences", stored.contains(key.toBase64()))
    }

    @Test
    fun eachRoomGetsItsOwnKey() {
        val mine = store.generate(room)
        val other = store.generate(room + 1)

        assertNotEquals(mine.toList(), other.toList())
        store.forget(room + 1)
    }

    @Test
    fun leavingTakesTheKeyWithIt() {
        store.generate(room)

        store.forget(room)

        assertNull(store.keyFor(room))
    }

    @Test
    fun aKeyOutlivesTheObjectThatMadeIt() {
        val key = store.generate(room)

        assertArrayEquals(key, RoomKeyStore(context).keyFor(room))
    }

    @Test
    fun aStoredKeyStillOpensWhatItSealed() {
        val key = store.generate(room)
        val context = SealedText.contextOf(room, senderNodeNum = 7)
        val sealed = SealedText.seal(key, "meet at the north gate".encodeToByteArray(), context)

        assertEquals(
            "meet at the north gate",
            SealedText.open(store.keyFor(room)!!, sealed, context)?.decodeToString(),
        )
    }

    /**
     * Keys are cached so the Keystore is not asked once per message, but each
     * caller must get its own array: leaving a room wipes the cached copy, and
     * a send part-way through sealing must not have its key blanked.
     */
    @Test
    fun eachReadGetsItsOwnCopy() {
        val key = store.generate(room)
        val first = store.keyFor(room)!!

        first.fill(0)

        assertArrayEquals(key, store.keyFor(room))
    }

    @Test
    fun leavingWipesTheCachedCopyTooAndNotTheCallersHand() {
        val key = store.generate(room)

        store.forget(room)

        // The caller's own array is untouched; the store simply has nothing left.
        assertEquals(RoomCipher.KEY_SIZE, key.size)
        assertFalse("the key was zeroed in the caller's hand", key.all { it == 0.toByte() })
        assertNull(store.keyFor(room))
    }

    private fun ByteArray.toBase64() =
        android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
}
