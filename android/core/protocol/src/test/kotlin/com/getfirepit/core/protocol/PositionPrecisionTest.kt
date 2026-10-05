package com.getfirepit.core.protocol

import com.getfirepit.core.model.ChannelRole
import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.model.RoomKind
import okio.ByteString
import org.meshtastic.proto.Config
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
    fun `a radio that broadcasts nowhere is correct`() {
        assertTrue(PositionSharing.isSilent(listOf(channel(0, 0), channel(1, 0), channel(2, 0))))
    }

    /**
     * Positions travel sealed by the phone, so the radio's own broadcast is
     * never wanted: it goes out under a channel key anyone holding a member's
     * radio can read, and it keeps going with the phone away.
     */
    @Test
    fun `a room broadcasting from the radio is not correct either`() {
        assertFalse(PositionSharing.isSilent(listOf(channel(0, 0), channel(1, 32))))
    }

    @Test
    fun `a broadcasting primary is not correct`() {
        assertFalse(PositionSharing.isSilent(listOf(channel(0, 13), channel(1, 0))))
    }

    @Test
    fun `silencing zeroes every broadcasting channel and touches nothing else`() {
        val channels = listOf(
            channel(0, 13, id = 0),
            channel(1, 0, id = 111),
            channel(2, 32, id = 222),
            channel(3, 16, id = 333, kind = RoomKind.MESHTASTIC_PRIVATE),
        )

        assertEquals(
            listOf(PrecisionWrite(0, 0), PrecisionWrite(2, 0), PrecisionWrite(3, 0)),
            PositionSharing.writesToSilence(channels),
        )
    }

    @Test
    fun `silencing keeps only the active safety-net room precision`() {
        val channels = listOf(
            channel(0, 32, id = 0),
            channel(1, 0, id = 111),
            channel(2, 32, id = 222),
            channel(3, 32, id = 333, kind = RoomKind.MESHTASTIC_PRIVATE),
        )

        assertEquals(
            listOf(PrecisionWrite(0, 0), PrecisionWrite(2, 0), PrecisionWrite(3, 0), PrecisionWrite(1, 32)),
            PositionSharing.writesToSilence(channels, keepSlot = 1),
        )
    }

    @Test
    fun `silencing writes all zeroes before the single full precision write`() {
        val channels = listOf(
            channel(0, 13, id = 0),
            channel(1, 16, id = 111),
            channel(2, 32, id = 222),
        )

        assertEquals(
            listOf(PrecisionWrite(0, 0), PrecisionWrite(1, 0), PrecisionWrite(2, 0), PrecisionWrite(1, 32)),
            PositionSharing.writesToSilence(channels, keepSlot = 1),
        )
    }

    @Test
    fun `an already silent radio needs no writes`() {
        val channels = listOf(channel(0, 0, id = 0), channel(1, 0, id = 111))

        assertTrue("a reconnect check must be silent when nothing is wrong", PositionSharing.writesToSilence(channels).isEmpty())
    }

    /**
     * The trap behind a real bug: a radio we cannot see produces the same empty
     * answer as a radio that is already correct. Callers must establish that the
     * channels are known *before* reading anything into an empty result.
     */
    @Test
    fun `an unknown radio is indistinguishable from a correct one`() {
        val unknown = PositionSharing.writesToSilence(emptyList())
        val correct = PositionSharing.writesToSilence(listOf(channel(0, 0, id = 0)))

        assertTrue(unknown.isEmpty())
        assertEquals("no write list can tell these apart", correct, unknown)
    }

    @Test
    fun `only a firepit room whose key we hold may receive a position`() {
        assertTrue(PositionSharing.canShare(channel(1, 0, kind = RoomKind.FIREPIT)))
        // The primary sets the frequency and carries NodeInfo; never a position.
        assertFalse(PositionSharing.canShare(channel(0, 0, kind = RoomKind.FIREPIT)))
        // A shared Meshtastic channel reaches people the group never chose.
        assertFalse(PositionSharing.canShare(channel(1, 0, kind = RoomKind.MESHTASTIC_PRIVATE)))
        assertFalse(PositionSharing.canShare(channel(2, 0, kind = RoomKind.MESHTASTIC_PUBLIC)))
        // No key to seal with, or a key the rest of the room has moved on from.
        assertFalse(PositionSharing.canShare(channel(3, 0, kind = RoomKind.FIREPIT_KEY_MISSING)))
        assertFalse(PositionSharing.canShare(channel(4, 0, kind = RoomKind.FIREPIT_MOVED_ON)))
    }

    @Test
    fun `safety net slot requires every guard including signed room id and private key`() {
        val roomId = -123456789
        val share = SafetyNetShare(roomId, endsAt = 2_000, radioSafetyNet = true, safetyNetNodeNum = 111)
        val ready = readyPosition()
        val primary = ByteString.of(*ByteArray(32) { 9 })
        val roomKey = ByteArray(32) { 7 }
        val channel = channel(1, 0, id = roomId, psk = roomKey)

        assertEquals(
            1,
            PositionSharing.safetyNetSlot(
                channels = listOf(channel),
                share = share,
                nowMillis = 1_000,
                connectedNodeNum = 111,
                positionConfig = ready,
                heldRoomIds = setOf(roomId),
                primaryKey = primary,
            ),
        )

        val cases = listOf(
            "no share" to null,
            "not opted in" to share.copy(radioSafetyNet = false),
            "expired" to share.copy(endsAt = 1_000),
            "other node" to share.copy(safetyNetNodeNum = 222),
        )
        cases.forEach { (name, badShare) ->
            assertEquals(
                name,
                null,
                PositionSharing.safetyNetSlot(listOf(channel), badShare, 1_000, 111, ready, setOf(roomId), primary),
            )
        }
        assertEquals(null, PositionSharing.safetyNetSlot(listOf(channel), share, 1_000, 111, readyPosition(secs = 1), setOf(roomId), primary))
        assertEquals(null, PositionSharing.safetyNetSlot(listOf(channel), share, 1_000, 111, ready, setOf(roomId), primary, licensedMode = true))
        assertEquals(null, PositionSharing.safetyNetSlot(listOf(channel(1, 0, id = roomId, psk = byteArrayOf())), share, 1_000, 111, ready, setOf(roomId), primary))
        assertEquals(null, PositionSharing.safetyNetSlot(listOf(channel(1, 0, id = roomId, psk = ByteArray(16))), share, 1_000, 111, ready, setOf(roomId), primary))
        assertEquals(null, PositionSharing.safetyNetSlot(listOf(channel(1, 0, id = roomId, psk = primary.toByteArray())), share, 1_000, 111, ready, setOf(roomId), primary))
        assertEquals(null, PositionSharing.safetyNetSlot(listOf(channel.copy(kind = RoomKind.FIREPIT_KEY_MISSING)), share, 1_000, 111, ready, setOf(roomId), primary))
        assertEquals(null, PositionSharing.safetyNetSlot(listOf(channel), share, 1_000, 111, ready, emptySet(), primary))
        assertEquals(null, PositionSharing.safetyNetSlot(listOf(channel, channel(2, 0, id = roomId, psk = roomKey)), share, 1_000, 111, ready, setOf(roomId), primary))
    }

    private fun channel(
        index: Int,
        precision: Int,
        id: Int = index * 100,
        psk: ByteArray? = ByteArray(32) { index.toByte() },
        kind: RoomKind = RoomKind.FIREPIT,
    ) = RoomChannel(
        index = index,
        name = "room$index",
        role = if (index == 0) ChannelRole.PRIMARY else ChannelRole.SECONDARY,
        id = id,
        positionPrecision = precision,
        psk = psk,
        kind = kind,
    )

    private fun readyPosition(secs: Int = 86_400) = Config.PositionConfig(
        gps_mode = Config.PositionConfig.GpsMode.ENABLED,
        fixed_position = false,
        position_broadcast_secs = secs,
        position_broadcast_smart_enabled = false,
        position_flags = Config.PositionConfig.PositionFlags.TIMESTAMP.value,
    )
}
