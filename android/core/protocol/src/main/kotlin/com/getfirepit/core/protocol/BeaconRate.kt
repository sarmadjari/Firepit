package com.getfirepit.core.protocol

/**
 * How often a shared position goes to its room.
 *
 * Kept in the radio's position settings, where Meshtastic has always kept it,
 * but the phone does the sending: it seals each fix under the room's key, which
 * the radio's own broadcast could not. So it pauses while the phone is away
 * from its radio.
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
