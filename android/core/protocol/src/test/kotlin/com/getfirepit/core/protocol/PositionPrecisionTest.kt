package com.getfirepit.core.protocol

import com.getfirepit.core.model.ChannelRole
import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.model.RoomKind
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionPrecisionTest {

    @Test
    fun `full precision is left alone`() {
        val coordinate = 512_345_678

        assertEquals(coordinate, PositionPrecision.truncate(coordinate, PositionPrecision.FULL))
    }

    @Test
    fun `out of range precision never moves the point`() {
        val coordinate = 512_345_678

        // 0 means "do not send", and anything above 32 is not a real setting.
        assertEquals(coordinate, PositionPrecision.truncate(coordinate, 0))
        assertEquals(coordinate, PositionPrecision.truncate(coordinate, 33))
        assertEquals(coordinate, PositionPrecision.truncate(coordinate, -1))
    }

    @Test
    fun `truncation lands in the middle of its cell, not the corner`() {
        // 16 bits keeps the top half of the coordinate and re-centres.
        val coordinate = 0x1234_ABCD
        val expected = (coordinate and (-1 shl 16)) + (1 shl 15)

        assertEquals(expected, PositionPrecision.truncate(coordinate, 16))
    }

    @Test
    fun `every point in a cell truncates to the same place`() {
        val random = Random(20260909)
        repeat(500) {
            val precision = random.nextInt(1, 32)
            val cell = PositionPrecision.cellSize(precision)
            val base = random.nextInt()

            val first = PositionPrecision.truncate(base, precision)
            // Another coordinate inside the same cell, chosen without crossing
            // the boundary.
            val offset = (base.toLong() and (cell - 1))
            val sameCell = (base.toLong() - offset).toInt()
            val second = PositionPrecision.truncate(sameCell, precision)

            assertEquals("precision=$precision base=$base", first, second)
        }
    }

    @Test
    fun `the point never moves further than one cell`() {
        val random = Random(1)
        repeat(500) {
            val precision = random.nextInt(1, 32)
            val coordinate = random.nextInt()

            val moved = PositionPrecision.truncate(coordinate, precision)
            val distance = kotlin.math.abs(moved.toLong() - coordinate.toLong())

            assertTrue(
                "precision=$precision moved $distance which is over one cell",
                distance <= PositionPrecision.cellSize(precision),
            )
        }
    }

    @Test
    fun `the result always sits at the centre of its cell`() {
        val random = Random(7)
        repeat(500) {
            val precision = random.nextInt(1, 32)
            val coordinate = random.nextInt()
            val cell = PositionPrecision.cellSize(precision)

            val moved = PositionPrecision.truncate(coordinate, precision).toLong() and 0xFFFF_FFFFL

            assertEquals(
                "precision=$precision must land on a cell midpoint, not a corner",
                cell / 2,
                moved % cell,
            )
        }
    }

    @Test
    fun `coarser precision means a larger uncertainty area`() {
        var previous = 0L
        for (precision in 31 downTo 1) {
            val cell = PositionPrecision.cellSize(precision)
            assertTrue("precision $precision should be coarser than ${precision + 1}", cell > previous)
            previous = cell
        }
    }
}

class PositionSharingTest {

    @Test
    fun `no sharing at all is valid`() {
        assertTrue(PositionSharing.isValid(listOf(channel(0, 0), channel(1, 0))))
    }

    @Test
    fun `one sharing room is valid`() {
        assertTrue(PositionSharing.isValid(listOf(channel(0, 0), channel(1, 32))))
    }

    /**
     * The firmware sends its own periodic broadcasts on the primary and its key
     * is one every Meshtastic radio has, so a position there is public however
     * few channels carry it.
     */
    @Test
    fun `the primary sharing is never valid, even on its own`() {
        assertFalse(PositionSharing.isValid(listOf(channel(0, 32), channel(1, 0))))
    }

    @Test
    fun `a sharing primary is turned off on the next check`() {
        val channels = listOf(channel(0, 32, id = 0), channel(1, 0, id = 111))

        val writes = PositionSharing.writesToShareOnly(channels, roomId = null, precision = 32)

        assertEquals(listOf(PrecisionWrite(0, 0)), writes)
    }

    /** A primary carrying a room's id is still the primary. */
    @Test
    fun `the primary cannot be chosen as the sharing room`() {
        val channels = listOf(channel(0, 0, id = 111))

        val writes = PositionSharing.writesToShareOnly(channels, roomId = 111, precision = 32)

        assertTrue("the primary must never be given a precision", writes.isEmpty())
    }

    @Test
    fun `two sharing channels is a privacy failure`() {
        assertFalse(PositionSharing.isValid(listOf(channel(1, 32), channel(2, 16))))
    }

    @Test
    fun `enabling one room disables every other`() {
        val channels = listOf(channel(0, 0, id = 0), channel(1, 32, id = 111), channel(2, 16, id = 222))

        val writes = PositionSharing.writesToShareOnly(channels, roomId = 222, precision = 32)

        assertEquals(
            listOf(PrecisionWrite(1, 0), PrecisionWrite(2, 32)),
            writes,
        )
    }

    @Test
    fun `turning sharing off clears every channel`() {
        val channels = listOf(channel(1, 32, id = 111), channel(2, 0, id = 222))

        val writes = PositionSharing.writesToShareOnly(channels, roomId = null, precision = 32)

        assertEquals(listOf(PrecisionWrite(1, 0)), writes)
    }

    @Test
    fun `an already correct radio needs no writes`() {
        val channels = listOf(channel(0, 0, id = 0), channel(1, 32, id = 111))

        val writes = PositionSharing.writesToShareOnly(channels, roomId = 111, precision = 32)

        assertTrue("a reconnect check must be silent when nothing is wrong", writes.isEmpty())
    }

    /**
     * The trap behind a real bug: a radio we cannot see produces the same empty
     * answer as a radio that is already correct.
     *
     * An expiry check that ran before the link came up therefore concluded
     * "nothing to do", forgot the deadline, and left the radio transmitting
     * forever. Callers must establish that the channels are known *before*
     * reading anything into an empty result.
     */
    @Test
    fun `an unknown radio is indistinguishable from a correct one`() {
        val unknown = PositionSharing.writesToShareOnly(emptyList(), roomId = null, precision = 32)
        val correct = PositionSharing.writesToShareOnly(
            listOf(channel(0, 0, id = 0)),
            roomId = null,
            precision = 32,
        )

        assertTrue(unknown.isEmpty())
        assertTrue(correct.isEmpty())
        assertEquals("no write list can tell these apart", correct, unknown)
    }

    /**
     * A shared Meshtastic channel reaches people the group never chose, and the
     * firmware would broadcast a position there on its own schedule. It is not
     * somewhere a position may go, however it was asked for.
     */
    @Test
    fun `a standard meshtastic channel can never carry a position`() {
        val meshtastic = channel(1, PositionPrecision.DISABLED, kind = RoomKind.MESHTASTIC_PRIVATE)
        val public = channel(2, PositionPrecision.DISABLED, kind = RoomKind.MESHTASTIC_PUBLIC)

        assertFalse(PositionSharing.canShare(meshtastic))
        assertFalse(PositionSharing.canShare(public))
    }

    @Test
    fun `asking to share with a meshtastic channel turns sharing off instead`() {
        val channels = listOf(
            channel(0, PositionPrecision.DISABLED),
            channel(1, 32, id = 111),
            channel(2, PositionPrecision.DISABLED, id = 222, kind = RoomKind.MESHTASTIC_PRIVATE),
        )

        val writes = PositionSharing.writesToShareOnly(channels, roomId = 222, precision = 32)

        // The room we were told to share with cannot, so nothing is left sharing.
        assertEquals(listOf(PrecisionWrite(1, PositionPrecision.DISABLED)), writes)
    }

    @Test
    fun `a position already set on a meshtastic channel is reported invalid`() {
        val channels = listOf(
            channel(0, PositionPrecision.DISABLED),
            channel(1, 32, id = 111, kind = RoomKind.MESHTASTIC_PUBLIC),
        )

        assertFalse(PositionSharing.isValid(channels))
        assertEquals(
            listOf(PrecisionWrite(1, PositionPrecision.DISABLED)),
            PositionSharing.writesToShareOnly(channels, roomId = null, precision = 32),
        )
    }

    @Test
    fun `only a firepit room may carry a position`() {
        assertTrue(PositionSharing.canShare(channel(1, 32, kind = RoomKind.FIREPIT)))
        // The primary sets the frequency and carries NodeInfo; never a position.
        assertFalse(PositionSharing.canShare(channel(0, 32, kind = RoomKind.FIREPIT)))
    }

    private fun channel(
        index: Int,
        precision: Int,
        id: Int = index * 100,
        kind: RoomKind = RoomKind.FIREPIT,
    ) = RoomChannel(
        index = index,
        name = "room$index",
        role = if (index == 0) ChannelRole.PRIMARY else ChannelRole.SECONDARY,
        id = id,
        positionPrecision = precision,
        kind = kind,
    )
}
