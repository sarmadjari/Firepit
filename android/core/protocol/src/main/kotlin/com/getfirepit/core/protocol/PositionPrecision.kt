package com.getfirepit.core.protocol

/**
 * Coordinate truncation, matching the firmware bit for bit.
 *
 * A room can be shared at reduced precision so members see roughly where
 * somebody is without publishing their doorstep. The firmware masks the low
 * bits and then adds half a cell, so the result sits in the middle of the
 * possible area rather than at its corner. Anything else here would disagree
 * with what other clients draw for the same packet.
 */
object PositionPrecision {

    /** Positions are never transmitted on a channel set to this. */
    const val DISABLED = 0

    /** No truncation: the coordinate goes out exactly as the GPS reported it. */
    const val FULL = 32

    /**
     * Truncates a 1e-7 degree coordinate to [precisionBits].
     *
     * [FULL] and [DISABLED] are returned untouched because the shift below is
     * only defined for 1..31: Kotlin masks Int shift counts to five bits, so
     * `1 shl -1` would silently become `1 shl 31` and move the point across
     * the planet.
     */
    fun truncate(coordinate: Int, precisionBits: Int): Int {
        if (precisionBits >= FULL || precisionBits <= DISABLED) return coordinate

        val masked = coordinate and (-1 shl (FULL - precisionBits))
        return masked + (1 shl (FULL - 1 - precisionBits))
    }

    /** How many 1e-7 degree units wide one cell is at [precisionBits]. */
    fun cellSize(precisionBits: Int): Long =
        if (precisionBits >= FULL || precisionBits <= DISABLED) 1L else 1L shl (FULL - precisionBits)
}
