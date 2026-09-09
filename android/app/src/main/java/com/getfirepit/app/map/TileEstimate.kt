package com.getfirepit.app.map

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

/**
 * How big a download will be, before starting it.
 *
 * The tile count is exact slippy-map arithmetic. The byte figure is an estimate
 * and is presented as one, because vector tile size varies with how much is in
 * the area: open country compresses to almost nothing, a city centre does not.
 */
object TileEstimate {

    /** Web Mercator cannot represent the poles; tiles stop at this latitude. */
    private const val MAX_LATITUDE = 85.05112878

    /**
     * Rough average for an OpenFreeMap vector tile at street zooms. Deliberately
     * on the high side so the number shown is a ceiling rather than a surprise.
     */
    private const val AVERAGE_TILE_BYTES = 45_000L

    fun tileCount(
        north: Double,
        south: Double,
        east: Double,
        west: Double,
        minZoom: Int,
        maxZoom: Int,
    ): Long {
        if (minZoom > maxZoom) return 0

        var total = 0L
        for (zoom in minZoom..maxZoom) {
            val xMin = lonToTileX(west, zoom)
            val xMax = lonToTileX(east, zoom)
            // Tile y grows southward, so the north edge gives the smaller index.
            val yMin = latToTileY(north, zoom)
            val yMax = latToTileY(south, zoom)

            val wide = (xMax - xMin + 1).coerceAtLeast(1).toLong()
            val tall = (yMax - yMin + 1).coerceAtLeast(1).toLong()
            total += wide * tall
        }
        return total
    }

    fun estimatedBytes(tiles: Long): Long = tiles * AVERAGE_TILE_BYTES

    /** Short human form, e.g. "about 42 MB". */
    fun describe(tiles: Long): String {
        val bytes = estimatedBytes(tiles)
        val megabytes = bytes / 1_000_000.0
        return when {
            megabytes < 1 -> "under 1 MB"
            megabytes < 10 -> "about %.1f MB".format(megabytes)
            else -> "about ${megabytes.toInt()} MB"
        }
    }

    private fun lonToTileX(longitude: Double, zoom: Int): Int {
        val scale = 1 shl zoom
        val normalised = (longitude.coerceIn(-180.0, 180.0) + 180.0) / 360.0
        return floor(normalised * scale).toInt().coerceIn(0, scale - 1)
    }

    private fun latToTileY(latitude: Double, zoom: Int): Int {
        val scale = 1 shl zoom
        val clamped = latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val radians = clamped * PI / 180.0
        val normalised = (1.0 - asinh(tan(radians)) / PI) / 2.0
        return floor(normalised * scale).toInt().coerceIn(0, scale - 1)
    }
}
