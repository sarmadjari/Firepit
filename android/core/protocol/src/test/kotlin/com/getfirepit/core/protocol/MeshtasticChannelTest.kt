package com.getfirepit.core.protocol

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config

class MeshtasticChannelTest {

    private val preset = Config.LoRaConfig.ModemPreset.LONG_FAST

    @Test
    fun `the default key matches the one quoted in channel proto`() {
        assertEquals(
            "d4f1bb3a20290759f0bcffabcf4e6901",
            MeshtasticChannel.DEFAULT_KEY.hex(),
        )
        assertEquals(16, MeshtasticChannel.DEFAULT_KEY.size)
    }

    @Test
    fun `the one-byte shorthand expands to the well-known key`() {
        assertEquals(
            MeshtasticChannel.DEFAULT_KEY,
            MeshtasticChannel.expandPsk(ByteString.of(1)),
        )
    }

    @Test
    fun `shorthands two to ten step the last byte`() {
        (2..10).forEach { shorthand ->
            val expanded = MeshtasticChannel.expandPsk(ByteString.of(shorthand.toByte()))
            val expected = MeshtasticChannel.DEFAULT_KEY.toByteArray().also {
                it[it.lastIndex] = (it[it.lastIndex] + (shorthand - 1)).toByte()
            }.toByteString()

            assertEquals("shorthand $shorthand", expected, expanded)
        }
    }

    @Test
    fun `shorthand zero means no encryption at all`() {
        assertEquals(ByteString.EMPTY, MeshtasticChannel.expandPsk(ByteString.of(0)))
    }

    @Test
    fun `a real key is left alone`() {
        val real = ByteArray(32) { it.toByte() }.toByteString()
        assertEquals(real, MeshtasticChannel.expandPsk(real))
    }

    /**
     * The reason the shorthand has to be expanded at all: without it, a channel
     * carrying a single byte looks like an unrecognised key, and the app would
     * describe a channel the whole world can read as private.
     */
    @Test
    fun `a shorthand channel is reported public, not private`() {
        val stock = ChannelSettings(name = "LongFast", psk = ByteString.of(1))

        assertTrue(MeshtasticChannel.isPublic(stock))
        assertEquals(ChannelKey.DEFAULT, ChannelKey.of(stock.psk.toByteArray()))
    }

    @Test
    fun `a 32-byte key is private`() {
        val shared = ChannelSettings(name = "camp", psk = ByteArray(32) { 7 }.toByteString())

        assertFalse(MeshtasticChannel.isPublic(shared))
    }

    @Test
    fun `an absent or empty key is never called private`() {
        assertTrue(MeshtasticChannel.isPublic(null))
        assertTrue(MeshtasticChannel.isPublic(ChannelSettings()))
    }

    @Test
    fun `public channel names follow the modem preset`() {
        assertEquals("LongFast", MeshtasticChannel.publicNameFor(preset))
        assertEquals("LongFast", MeshtasticChannel.publicNameFor(null))
        assertEquals("MediumSlow", MeshtasticChannel.publicNameFor(Config.LoRaConfig.ModemPreset.MEDIUM_SLOW))
        assertEquals("ShortTurbo", MeshtasticChannel.publicNameFor(Config.LoRaConfig.ModemPreset.SHORT_TURBO))
    }

    /**
     * The trap that length-based classification falls into: the default key is
     * sixteen bytes, exactly like a real AES-128 key. A channel carrying it
     * written out in full is still readable by every radio on the mesh.
     */
    @Test
    fun `the default key written out in full is still public`() {
        val spelledOut = ChannelSettings(name = "LongFast", psk = MeshtasticChannel.DEFAULT_KEY)

        assertTrue(MeshtasticChannel.isPublic(spelledOut))
        assertTrue(MeshtasticChannel.isWellKnown(MeshtasticChannel.DEFAULT_KEY))
    }

    @Test
    fun `every well-known neighbour of the default key is public, however it is written`() {
        (1..MeshtasticChannel.MAX_SHORTHAND).forEach { shorthand ->
            val expanded = MeshtasticChannel.expandPsk(ByteString.of(shorthand.toByte()))

            assertTrue("shorthand $shorthand", MeshtasticChannel.isWellKnown(expanded))
            assertTrue(
                "shorthand $shorthand",
                MeshtasticChannel.isPublic(ChannelSettings(name = "x", psk = expanded)),
            )
        }
    }

    @Test
    fun `a real key is not mistaken for a well-known one`() {
        assertFalse(MeshtasticChannel.isWellKnown(ByteArray(32) { 4 }.toByteString()))
        assertFalse(MeshtasticChannel.isWellKnown(ByteArray(16) { 4 }.toByteString()))
        assertFalse(MeshtasticChannel.isWellKnown(null))
    }

    @Test
    fun `every preset name fits the eleven-byte channel name limit`() {
        // nanopb caps ChannelSettings.name at 12 bytes including the
        // terminator, so a longer name could not be what the firmware uses.
        Config.LoRaConfig.ModemPreset.entries.forEach { preset ->
            val name = MeshtasticChannel.publicNameFor(preset)
            assertTrue(
                "$preset -> \"$name\" is ${name.toByteArray().size} bytes",
                name.toByteArray().size <= 11,
            )
        }
    }

    @Test
    fun `interop channels never gateway to the internet or carry a position`() {
        val built = listOf(
            MeshtasticChannel.publicChannel(index = 1, preset = preset),
            MeshtasticChannel.privateChannel(
                index = 2,
                name = "camp",
                psk = ByteArray(32) { 3 }.toByteString(),
            ),
        )

        built.forEach { channel ->
            val settings = channel.settings!!
            assertFalse(settings.uplink_enabled)
            assertFalse(settings.downlink_enabled)
            assertEquals(
                PositionPrecision.DISABLED,
                settings.module_settings?.position_precision,
            )
        }
    }

    @Test
    fun `a private interop channel refuses a key length the firmware would not accept`() {
        val tooShort = ByteArray(8).toByteString()

        val failure = runCatching {
            MeshtasticChannel.privateChannel(1, "camp", tooShort)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a public interop channel is recognisable as public once built`() {
        val channel = MeshtasticChannel.publicChannel(index = 1, preset = preset)

        assertTrue(MeshtasticChannel.isPublic(channel.settings))
    }
}
