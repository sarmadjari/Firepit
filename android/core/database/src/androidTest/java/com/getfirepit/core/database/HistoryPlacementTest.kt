package com.getfirepit.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.getfirepit.core.model.ChatMessage
import com.getfirepit.core.model.MapPin
import com.getfirepit.core.model.MessageStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The queries that keep history with its room, checked against SQLite.
 *
 * Each one moves or deletes rows by slot, room and "filed under no room" at
 * once, and getting one condition wrong either shows one room's words under
 * another's name or deletes somebody's history outright.
 */
@RunWith(AndroidJUnit4::class)
class HistoryPlacementTest {

    private lateinit var db: FirepitDatabase
    private lateinit var messages: MessageDao
    private lateinit var pins: MapPinDao
    private lateinit var activity: RoomActivityDao
    private lateinit var state: ChannelStateDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FirepitDatabase::class.java,
        ).build()
        messages = db.messageDao()
        pins = db.mapPinDao()
        activity = db.roomActivityDao()
        state = db.channelStateDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun anotherRoomsHistoryIsSetAsideAndComesBackToWhereverItsRoomIs() = runBlocking {
        save(id = 1, slot = 2, roomId = X)
        save(id = 2, slot = 2, roomId = Y)

        // Another radio carries Y in slot 2: X's words must leave it.
        messages.parkOtherRooms(slot = 2, keep = Y)
        assertEquals(PARKED_CHANNEL, channelOf(1))
        assertEquals(2, channelOf(2))

        // Back on a radio that carries X in slot 5.
        messages.placeRoom(roomId = X, slot = 5)
        assertEquals(5, channelOf(1))
    }

    /**
     * A Meshtastic channel has no id, so its history is filed under no room.
     * It is set aside while a room holds its slot, and put back — not lost —
     * when a channel without an id is in that slot again.
     */
    @Test
    fun unfiledHistoryIsSetAsidePerSlotAndPutBack() = runBlocking {
        save(id = 1, slot = 3, roomId = 0)
        save(id = 2, slot = 4, roomId = 0)

        messages.parkUnfiled(slot = 3)
        assertEquals(unfiledParkingFor(3), channelOf(1))
        assertEquals("another slot's history is not touched", 4, channelOf(2))

        messages.restoreUnfiled(slot = 4)
        assertEquals("slot 4 has nothing parked, so slot 3's stays set aside", unfiledParkingFor(3), channelOf(1))

        messages.restoreUnfiled(slot = 3)
        assertEquals(3, channelOf(1))
    }

    @Test
    fun parkingOtherRoomsNeverTouchesUnfiledHistory() = runBlocking {
        save(id = 1, slot = 2, roomId = 0)

        messages.parkOtherRooms(slot = 2, keep = X)

        assertEquals(2, channelOf(1))
    }

    /** Leaving a Meshtastic channel must not take every other channel without an id with it. */
    @Test
    fun deletingByRoomNeverTouchesUnfiledHistoryAndBySlotNeverTouchesARooms() = runBlocking {
        save(id = 1, slot = 1, roomId = 0)
        save(id = 2, slot = 2, roomId = 0)
        save(id = 3, slot = 1, roomId = X)
        save(id = 4, slot = PARKED_CHANNEL, roomId = X)

        messages.deleteUnfiled(slot = 1)
        assertNull(messages.findEntity(1))
        assertEquals("another channel's history survives", 2, channelOf(2))
        assertEquals("a room's history is deleted by its id, not its slot", 1, channelOf(3))

        messages.deleteRoom(roomId = X)
        assertNull(messages.findEntity(3))
        assertNull("set-aside history goes with its room", messages.findEntity(4))
        assertEquals(2, channelOf(2))
    }

    @Test
    fun movingUnfiledHistoryLeavesRoomsToBePlacedByTheirIds() = runBlocking {
        save(id = 1, slot = 3, roomId = 0)
        save(id = 2, slot = 3, roomId = X)

        messages.moveUnfiled(from = 3, to = 2)

        assertEquals(2, channelOf(1))
        assertEquals(3, channelOf(2))
    }

    @Test
    fun pinsFollowTheSameRules() = runBlocking {
        pins.save(pin(id = 1, slot = 2, roomId = X))
        pins.save(pin(id = 2, slot = 2, roomId = 0))

        pins.parkOtherRooms(slot = 2, keep = Y)
        pins.parkUnfiled(slot = 2)
        assertEquals(PARKED_CHANNEL, pins.findEntity(1)?.channel)
        assertEquals(unfiledParkingFor(2), pins.findEntity(2)?.channel)

        pins.placeRoom(roomId = X, slot = 4)
        pins.restoreUnfiled(slot = 2)
        assertEquals(4, pins.findEntity(1)?.channel)
        assertEquals(2, pins.findEntity(2)?.channel)

        pins.deleteUnfiled(slot = 2)
        assertNull(pins.findEntity(2))
        assertEquals(4, pins.findEntity(1)?.channel)
    }

    /** A mute belongs to the room, so it comes back when the room is in some slot again. */
    @Test
    fun aRoomsMuteFollowsItIntoWhicheverSlotItLands() = runBlocking {
        activity.setMuted(X, muted = true, now = 1_000)
        state.markRead(channel = 3, now = 1_000, roomId = Y)
        assertFalse(state.find(3)!!.muted)

        state.followRoom(channel = 3, roomId = X, now = 2_000, roomMuted = activity.isMuted(X))

        val followed = state.find(3)!!
        assertEquals(X, followed.roomId)
        assertTrue(followed.muted)
        assertFalse(activity.isMuted(Y))
    }

    private suspend fun save(id: Int, slot: Int, roomId: Int) = messages.save(
        ChatMessage(
            id = id,
            channel = slot,
            fromNodeNum = 11,
            toNodeNum = -1,
            text = "message $id",
            sentAt = 1_000L + id,
            status = MessageStatus.RECEIVED,
            roomId = roomId,
        ),
        myNodeNum = 22,
    )

    private suspend fun channelOf(id: Int): Int? = messages.findEntity(id)?.channel

    private fun pin(id: Int, slot: Int, roomId: Int) = MapPin(
        id = id,
        channel = slot,
        latitudeI = 1,
        longitudeI = 1,
        name = "pin $id",
        createdBy = 11,
        receivedAt = 1_000,
        roomId = roomId,
    )

    private companion object {
        const val X = 0x0BADF00D
        const val Y = 0x0DEFACED
    }
}
