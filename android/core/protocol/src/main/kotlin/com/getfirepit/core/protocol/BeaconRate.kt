package com.getfirepit.core.protocol

/**
 * How often a radio reports where it is, on its own.
 *
 * This is the radio's job, not the app's: it keeps reporting with the phone
 * dead, off, or out of Bluetooth range, which is the only reason a tracker is
 * worth carrying.
 */
enum class BeaconRate(val label: String, val seconds: Int) {
    BRISK("5 min", 300),
    STEADY("15 min", 900),
    SPARING("30 min", 1_800),
    HOURLY("1 hour", 3_600),
    ;

    companion object {
        /** What the firmware uses when the field is left at zero. */
        const val FIRMWARE_DEFAULT_SECONDS: Int = 900

        /**
         * Reads the radio's setting back.
         *
         * Zero is not "never": it means the firmware's own default, so it is
         * reported as the rate that default actually produces. An interval
         * Firepit does not offer returns null rather than being rounded to a
         * neighbour, so it is never silently rewritten.
         */
        fun of(seconds: Int?): BeaconRate? {
            val wanted = if (seconds == 0) FIRMWARE_DEFAULT_SECONDS else seconds
            return entries.firstOrNull { it.seconds == wanted }
        }
    }
}
