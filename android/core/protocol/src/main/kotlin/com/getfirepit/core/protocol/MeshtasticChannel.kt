package com.getfirepit.core.protocol

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.ModuleSettings

/**
 * What Firepit needs to speak plain Meshtastic.
 *
 * A Firepit room is sealed inside `PRIVATE_APP` and is unreadable to any other
 * client. That is the point of it, and it is also why it cannot be used to talk
 * to somebody running the official app. An interoperable channel is the
 * opposite trade: ordinary `TEXT_MESSAGE_APP` on an ordinary channel, readable
 * by whoever holds the key — which on the default key is everybody.
 *
 * Both are legitimate; only one of them is private, and the app has to say
 * which is which rather than let someone assume.
 */
object MeshtasticChannel {

    /**
     * The published default key, from `channel.proto`. Quoted there in full,
     * which is the whole problem with it: a channel using this is readable by
     * any Meshtastic device out of the box.
     */
    val DEFAULT_KEY: ByteString = byteArrayOf(
        0xd4.toByte(), 0xf1.toByte(), 0xbb.toByte(), 0x3a,
        0x20, 0x29, 0x07, 0x59,
        0xf0.toByte(), 0xbc.toByte(), 0xff.toByte(), 0xab.toByte(),
        0xcf.toByte(), 0x4e, 0x69, 0x01,
    ).toByteString()

    /** `psk = [1]` is the shorthand for [DEFAULT_KEY]; 2..10 are it plus 1..9. */
    const val DEFAULT_KEY_SHORTHAND: Byte = 1
    const val MAX_SHORTHAND: Int = 10

    /**
     * Resolves the one-byte shorthand the firmware allows in `psk`.
     *
     * Without this a channel carrying `[1]` looks like a one-byte key rather
     * than the well-known default, and the app would call a public channel
     * private.
     */
    fun expandPsk(psk: ByteString?): ByteString = when {
        psk == null || psk.size != 1 -> psk ?: ByteString.EMPTY
        psk[0].toInt() == 0 -> ByteString.EMPTY
        psk[0].toInt() in 1..MAX_SHORTHAND -> {
            val offset = psk[0].toInt() - 1
            val bytes = DEFAULT_KEY.toByteArray()
            bytes[bytes.lastIndex] = (bytes[bytes.lastIndex] + offset).toByte()
            bytes.toByteString()
        }

        else -> psk
    }

    /**
     * The name a channel must carry to sit on the public mesh.
     *
     * The firmware derives the frequency slot from the primary channel's name,
     * and treats an empty name as the modem preset's own label. A channel that
     * means to reach public nodes has to use that same label, because the
     * channel hash every receiver checks is taken over the name and the key
     * together.
     */
    fun publicNameFor(preset: Config.LoRaConfig.ModemPreset?): String = when (preset) {
        Config.LoRaConfig.ModemPreset.LONG_SLOW -> "LongSlow"
        Config.LoRaConfig.ModemPreset.LONG_MODERATE -> "LongMod"
        Config.LoRaConfig.ModemPreset.LONG_TURBO -> "LongTurbo"
        Config.LoRaConfig.ModemPreset.MEDIUM_SLOW -> "MediumSlow"
        Config.LoRaConfig.ModemPreset.MEDIUM_FAST -> "MediumFast"
        Config.LoRaConfig.ModemPreset.SHORT_SLOW -> "ShortSlow"
        Config.LoRaConfig.ModemPreset.SHORT_FAST -> "ShortFast"
        Config.LoRaConfig.ModemPreset.SHORT_TURBO -> "ShortTurbo"
        Config.LoRaConfig.ModemPreset.VERY_LONG_SLOW -> "VLongSlow"
        // LONG_FAST is the firmware default and what an unset preset means.
        else -> "LongFast"
    }

    /**
     * A channel that reaches the open mesh: the preset's own name and the key
     * everybody already has.
     */
    fun publicChannel(
        index: Int,
        preset: Config.LoRaConfig.ModemPreset?,
        role: Channel.Role = Channel.Role.SECONDARY,
    ): Channel = Channel(
        index = index,
        role = role,
        settings = ChannelSettings(
            name = publicNameFor(preset),
            psk = ByteString.of(DEFAULT_KEY_SHORTHAND),
            // Never on our account: an uplink copies the channel's traffic to
            // a public internet broker.
            uplink_enabled = false,
            downlink_enabled = false,
            module_settings = ModuleSettings(position_precision = PositionPrecision.DISABLED),
        ),
    )

    /**
     * A channel shared with particular people: a name and a real key.
     *
     * Private from the mesh at large, but not from whoever holds the key, and
     * the radio holds it — so this is weaker than a Firepit room and is only
     * offered for talking to people who are not running Firepit.
     */
    fun privateChannel(
        index: Int,
        name: String,
        psk: ByteString,
        role: Channel.Role = Channel.Role.SECONDARY,
    ): Channel {
        require(psk.size == 16 || psk.size == 32) {
            "a Meshtastic key is 16 or 32 bytes, was ${psk.size}"
        }
        return Channel(
            index = index,
            role = role,
            settings = ChannelSettings(
                name = name,
                psk = psk,
                uplink_enabled = false,
                downlink_enabled = false,
                module_settings = ModuleSettings(position_precision = PositionPrecision.DISABLED),
            ),
        )
    }

    /** True when this channel's traffic is readable by any Meshtastic radio. */
    fun isPublic(settings: ChannelSettings?): Boolean =
        !ChannelKey.of(settings?.psk?.toByteArray()).isPrivate

    /**
     * True for the published default key and its nine neighbours, whether they
     * arrived as the one-byte shorthand or written out in full.
     *
     * Length alone cannot answer this: the default key is sixteen bytes, the
     * same as a real AES-128 key, so a check that only measured it would call
     * the most public key in the protocol private.
     */
    fun isWellKnown(psk: ByteString?): Boolean {
        val expanded = expandPsk(psk)
        if (expanded.size != DEFAULT_KEY.size) return false
        return (1..MAX_SHORTHAND).any { expanded == expandPsk(ByteString.of(it.toByte())) }
    }
}
