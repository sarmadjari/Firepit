package com.getfirepit.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileEstimateTest {

    @Test
    fun `the whole world is one tile at zoom zero`() {
        val tiles = TileEstimate.tileCount(
            north = 85.0,
            south = -85.0,
            east = 180.0,
            west = -180.0,
            minZoom = 0,
            maxZoom = 0,
        )

        assertEquals(1, tiles)
    }

    @Test
    fun `a finer zoom costs more but never more than four times plus its edges`() {
        fun count(zoom: Int) = TileEstimate.tileCount(
            north = 60.0,
            south = 0.0,
            east = 0.0,
            west = -90.0,
            minZoom = zoom,
            maxZoom = zoom,
        )

        val z4 = count(4)
        val z5 = count(5)

        // Not exactly 4x: the grids do not align, so a bounding box picks up
        // partial tiles along each edge at every level.
        assertTrue("a finer zoom must cost more, was $z4 then $z5", z5 > z4)
        assertTrue("growth should stay near 4x, was $z4 then $z5", z5 <= z4 * 4)
    }

    @Test
    fun `a zoom range sums every level`() {
        val single = TileEstimate.tileCount(50.0, 49.0, 1.0, 0.0, 12, 12)
        val range = TileEstimate.tileCount(50.0, 49.0, 1.0, 0.0, 12, 13)

        assertTrue("a wider range must include more tiles", range > single)
    }

    @Test
    fun `an inverted zoom range yields nothing rather than a negative count`() {
        assertEquals(0, TileEstimate.tileCount(50.0, 49.0, 1.0, 0.0, 15, 8))
    }

    @Test
    fun `latitudes beyond the mercator limit are clamped, not wrapped`() {
        // The poles are not representable; asking for them must not produce a
        // nonsense index or a negative count.
        val tiles = TileEstimate.tileCount(
            north = 90.0,
            south = -90.0,
            east = 10.0,
            west = 0.0,
            minZoom = 6,
            maxZoom = 6,
        )

        assertTrue("clamped poles should still yield a sane count, was $tiles", tiles in 1..10_000)
    }

    @Test
    fun `a tiny area still costs at least one tile per zoom`() {
        val tiles = TileEstimate.tileCount(
            north = 25.2049,
            south = 25.2048,
            east = 55.2709,
            west = 55.2708,
            minZoom = 8,
            maxZoom = 15,
        )

        assertEquals("eight zoom levels, one tile each", 8, tiles)
    }

    @Test
    fun `size is described in human terms`() {
        assertEquals("under 1 MB", TileEstimate.describe(10))
        assertTrue(TileEstimate.describe(100).contains("MB"))
        assertTrue(TileEstimate.describe(100_000).contains("MB"))
    }
}
