package com.getfirepit.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.getfirepit.core.crypto.HourKey
import com.getfirepit.core.crypto.RoomCipher
import com.getfirepit.core.crypto.RoomRatchet
import com.getfirepit.core.database.KeystoreWrapping
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    private val sender = 7
    private val hour = 491_234

    /** Real time. The phone's clock reads [ahead] of it, which is normally nothing. */
    private var now = hour * RoomRatchet.HOUR_MILLIS + 60_000
    private var ahead = 0L

    @Before
    fun setUp() {
        store = storeAtNow()
        store.forget(room)
        store.forget(room + 1)
    }

    @After
    fun tearDown() {
        store.forget(room)
        store.forget(room + 1)
    }

    @Test
    fun aRoomWithNoKeyIsNotSealed() {
        assertFalse(store.holds(room))
        assertFalse(store.canSeal(room))
        assertNull(store.currentKey(room))
        assertNull(store.seal(room, sender, "hello".encodeToByteArray()))
    }

    @Test
    fun aGeneratedKeyStartsThisHour() {
        val key = store.generate(room)

        assertEquals(hour, key.hour)
        assertTrue(store.holds(room))
        assertArrayEquals(key.key, store.currentKey(room)?.key)
    }

    @Test
    fun aKeyFromAGrantIsKeptAsGiven() {
        val fromGrant = HourKey(hour, RoomCipher.generateKey())

        store.remember(room, fromGrant)

        assertArrayEquals(fromGrant.key, store.currentKey(room)?.key)
    }

    @Test
    fun theStoredFormIsNotTheKey() {
        val key = store.generate(room)
        val stored = context.getSharedPreferences("firepit_room_keys", 0).getString("$room/1", null)

        assertNotNull(stored)
        assertFalse("the key is sitting in preferences", stored!!.contains(key.key.toBase64()))
    }

    @Test
    fun eachRoomGetsItsOwnKey() {
        val mine = store.generate(room)
        val other = store.generate(room + 1)

        assertNotEquals(mine.key.toList(), other.key.toList())
    }

    @Test
    fun leavingTakesTheKeyWithIt() {
        store.generate(room)

        store.forget(room)

        assertFalse(store.holds(room))
        assertNull(store.currentKey(room))
    }

    @Test
    fun aKeyOutlivesTheObjectThatMadeIt() {
        val key = store.generate(room)

        assertArrayEquals(key.key, storeAtNow().currentKey(room)?.key)
    }

    @Test
    fun aMemberReadsWhatAnotherSealedOnce() {
        store.generate(room)
        val sealed = store.seal(room, sender, "meet at the north gate".encodeToByteArray())!!
        val payload = sealed.ciphertext.toByteArray()

        val opened = store.open(room, sealed.generation, sender, payload)
        assertEquals("meet at the north gate", (opened as Opening.Read).plain.decodeToString())
        assertEquals(Opening.Replayed, store.open(room, sealed.generation, sender, payload))
    }

    @Test
    fun aMessageCannotBeReattributed() {
        store.generate(room)
        val sealed = store.seal(room, sender, "on my way".encodeToByteArray())!!

        assertEquals(Opening.Unreadable, store.open(room, sealed.generation, sender + 1, sealed.ciphertext.toByteArray()))
    }

    @Test
    fun theHourGoneStillOpensAndTheOneBeforeItDoesNot() {
        store.generate(room)
        val first = store.seal(room, sender, "one".encodeToByteArray())!!.ciphertext.toByteArray()
        val second = store.seal(room, sender, "two".encodeToByteArray())!!.ciphertext.toByteArray()

        now += RoomRatchet.HOUR_MILLIS
        assertTrue(store.open(room, 1, sender, first) is Opening.Read)

        now += RoomRatchet.HOUR_MILLIS
        assertTrue(store.open(room, 1, sender, second) is Opening.OutOfHours)
    }

    @Test
    fun aKeyTakenLaterOpensNothingFromBefore() {
        store.generate(room)
        val early = store.seal(room, sender, "before".encodeToByteArray())!!.ciphertext.toByteArray()

        now += 5 * RoomRatchet.HOUR_MILLIS
        store.erase()

        assertEquals(hour + 5, store.currentKey(room)?.hour)
        assertTrue(store.open(room, 1, sender, early) is Opening.OutOfHours)
    }

    @Test
    fun aKeyHandedOnIsThisHoursAndMovesOnAsTheirsWould() {
        val start = store.generate(room)

        now += 3 * RoomRatchet.HOUR_MILLIS
        val handed = store.currentKey(room)!!

        assertEquals(hour + 3, handed.hour)
        assertArrayEquals(RoomRatchet.forward(start.key, room, 1, hour, hour + 3), handed.key)
    }

    @Test
    fun aKeyStoredBeforeTheRatchetCountsFromTheFixedHour() {
        val legacy = RoomCipher.generateKey()
        context.getSharedPreferences("firepit_room_keys", 0).edit()
            .putString("$room/1", KeystoreWrapping.wrap(RoomKeyStore.ALIAS, legacy).toBase64())
            .putInt("$room.generation", 1)
            .commit()

        val current = storeAtNow().currentKey(room)!!

        assertEquals(hour, current.hour)
        assertArrayEquals(RoomRatchet.forward(legacy, room, 1, RoomRatchet.LEGACY_HOUR, hour), current.key)
    }

    @Test
    fun aGenerationMovedOnFromGoesOnceLatePacketsHaveArrived() {
        store.generate(room)
        store.remember(room, HourKey(hour, RoomCipher.generateKey()), generation = 2)
        assertNotNull("sealed just before the move", store.seal(room, sender, "late".encodeToByteArray(), generation = 1))

        now += RoomKeyStore.RETIRED_GRACE_HOURS * RoomRatchet.HOUR_MILLIS
        store.erase()

        assertNull(store.seal(room, sender, "later".encodeToByteArray(), generation = 1))
        assertNotNull(store.seal(room, sender, "now".encodeToByteArray()))
    }

    @Test
    fun aGenerationStillOwedToSomebodyIsKept() {
        store.generate(room)
        store.remember(room, HourKey(hour, RoomCipher.generateKey()), generation = 2)

        now += 10 * RoomRatchet.HOUR_MILLIS
        store.erase(owed = setOf(RoomGeneration(room, 1)))

        assertNotNull(store.seal(room, sender, "your new key".encodeToByteArray(), generation = 1))
    }

    @Test
    fun aClockSetAheadErasesNothingTheRoomStillNeeds() {
        store.generate(room)
        val before = store.seal(room, sender, "before".encodeToByteArray())!!.ciphertext.toByteArray()

        ahead = 5 * RoomRatchet.HOUR_MILLIS
        store.erase()
        assertEquals(hour + 5, store.currentKey(room)?.hour)

        // Put right again, the phone still reads what was sealed before the clock went wrong.
        ahead = 0
        assertTrue(store.open(room, 1, sender, before) is Opening.Read)
    }

    @Test
    fun eachReadGetsItsOwnCopy() {
        val key = store.generate(room)
        val first = store.currentKey(room)!!

        first.key.fill(0)

        assertArrayEquals(key.key, store.currentKey(room)?.key)
    }

    private fun storeAtNow() = RoomKeyStore(context).also {
        it.time = object : KeyTime {
            override fun wallMillis() = now + ahead

            override fun eraseMillis() = now
        }
    }

    private fun ByteArray.toBase64() =
        android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
}
