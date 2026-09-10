package com.getfirepit.core.protocol

import org.meshtastic.proto.Config

/**
 * Who a radio will relay for.
 *
 * A relay does not need to read a packet to pass it on, so by default a radio
 * carries traffic for every mesh sharing its frequency. That is generous, and
 * sometimes not what you want from hardware you paid for and left on a hill.
 */
enum class RelayReach(val label: String, val mode: Config.DeviceConfig.RebroadcastMode) {
    /** Carries anything it hears, including other people's meshes. */
    EVERYONE("Everybody", Config.DeviceConfig.RebroadcastMode.ALL),

    /** Only passes on what it can decrypt: traffic on your own channels. */
    GROUP("My group only", Config.DeviceConfig.RebroadcastMode.LOCAL_ONLY),

    ;

    companion object {
        /**
         * Anything other than the two modes Firepit offers is left alone rather
         * than reported as one of them, so an unrecognised setting is never
         * silently overwritten by the next tap.
         */
        fun of(mode: Config.DeviceConfig.RebroadcastMode?): RelayReach? =
            entries.firstOrNull { it.mode == mode }
    }
}
