package com.getfirepit.core.protocol

/**
 * How busy the radio channel is.
 *
 * On a shared LoRa channel there is no collision detection: a congested channel
 * does not refuse messages, it swallows them. Surfacing this is the difference
 * between "nobody replied" and "nothing left the antenna".
 */
enum class ChannelLoad {
    /** Room to talk. */
    CLEAR,

    /** Usable, but messages may take a while. */
    BUSY,

    /** Firmware throttles its own telemetry here; expect losses. */
    CONGESTED,
    ;

    companion object {
        /**
         * Matches the firmware's own threshold for suppressing telemetry, so
         * our warning appears at the point the radio starts holding back.
         */
        const val BUSY_PERCENT = 25f
        const val CONGESTED_PERCENT = 50f

        fun of(utilizationPercent: Float?): ChannelLoad? = when {
            utilizationPercent == null -> null
            utilizationPercent >= CONGESTED_PERCENT -> CONGESTED
            utilizationPercent >= BUSY_PERCENT -> BUSY
            else -> CLEAR
        }
    }
}
