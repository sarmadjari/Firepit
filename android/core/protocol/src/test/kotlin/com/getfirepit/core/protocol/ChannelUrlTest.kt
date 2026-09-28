package com.getfirepit.core.protocol

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config

class ChannelUrlTest {

    private val primary = ChannelSettings(name = "LongFast", psk = ByteString.of(1))
    private val secondary = ChannelSettings(name = "camp", psk = ByteArray(32) { 9 }.toByteString())
    private val lora = Config.LoRaConfig(
        use_preset = true,
        modem_preset = Config.LoRaConfig.ModemPreset.LONG_FAST,
        region = Config.LoRaConfig.RegionCode.EU_868,
    )

    @Test
    fun `a link we produce is one we can read back`() {
        val url = ChannelUrl.encode(listOf(primary, secondary), lora)

        val shared = ChannelUrl.decode(url)

        assertNotNull(shared)
        assertEquals(listOf(primary, secondary), shared!!.channels)
        assertEquals(lora, shared.lora)
        assertFalse(shared.addOnly)
    }

    @Test
    fun `the link is the form the official clients produce`() {
        val url = ChannelUrl.encode(listOf(primary), lora)

        assertTrue(url, url.startsWith("https://meshtastic.org/e/#"))
        // Url-safe alphabet with the padding stripped, per python getURL.
        val payload = url.substringAfter("/#")
        assertFalse(payload, payload.contains('+'))
        assertFalse(payload, payload.contains('/'))
        assertFalse(payload, payload.contains('='))
    }

    /** Primary first is the format, not a convention: python `setURL` assigns roles by position. */
    @Test
    fun `the primary keeps its place at the front`() {
        val shared = ChannelUrl.decode(ChannelUrl.encode(listOf(primary, secondary), lora))

        assertEquals(primary, shared?.primary)
    }

    @Test
    fun `padding stripped by another client is restored`() {
        // Every payload length mod 4 has to survive, since the reference
        // implementation removes '=' before sharing.
        (1..6).forEach { count ->
            val channels = List(count) { index ->
                ChannelSettings(name = "ch$index", psk = ByteArray(32) { index.toByte() }.toByteString())
            }
            val url = ChannelUrl.encode(channels, lora)

            assertEquals("count $count", channels, ChannelUrl.decode(url)?.channels)
        }
    }

    @Test
    fun `an add-only link is recognised as adding rather than replacing`() {
        val payload = ChannelUrl.encode(listOf(secondary), lora).substringAfter("/#")

        val shared = ChannelUrl.decode("https://meshtastic.org/e/?add=true#$payload")

        assertTrue(shared!!.addOnly)
        assertEquals(listOf(secondary), shared.channels)
    }

    /** The reference splits on `/#` rather than matching a host, so older links still work. */
    @Test
    fun `older link forms still parse`() {
        val payload = ChannelUrl.encode(listOf(primary), lora).substringAfter("/#")

        listOf(
            "https://meshtastic.org/d/#$payload",
            "https://www.meshtastic.org/c/#$payload",
            "https://meshtastic.org/e/#$payload",
        ).forEach { url ->
            assertEquals(url, listOf(primary), ChannelUrl.decode(url)?.channels)
        }
    }

    @Test
    fun `whitespace around a pasted link is tolerated`() {
        val url = ChannelUrl.encode(listOf(primary), lora)

        assertNotNull(ChannelUrl.decode("  $url\n"))
    }

    @Test
    fun `anything that is not a channel link is refused`() {
        listOf(
            "",
            "not a url",
            "https://meshtastic.org/e/#",
            "https://meshtastic.org/e/#!!!not base64!!!",
            "firepit://join?v=1&d=abc",
            "https://example.com/",
        ).forEach { assertNull(it, ChannelUrl.decode(it)) }
    }

    /**
     * A key the firmware would reject is worse than no channel: it would be
     * written, look configured, and silently fail to decrypt anything. An empty
     * one is worse still, because a secondary with no key inherits the
     * primary's.
     */
    @Test
    fun `channels with an unusable key are dropped`() {
        val bad = ChannelSettings(name = "broken", psk = ByteArray(7).toByteString())
        val url = ChannelUrl.encode(listOf(bad), lora)

        assertNull(ChannelUrl.decode(url))
    }

    @Test
    fun `a usable channel survives alongside an unusable one`() {
        val bad = ChannelSettings(name = "broken", psk = ByteArray(7).toByteString())
        val url = ChannelUrl.encode(listOf(secondary, bad), lora)

        assertEquals(listOf(secondary), ChannelUrl.decode(url)?.channels)
    }

    @Test
    fun `a link with no lora config still parses`() {
        val shared = ChannelUrl.decode(ChannelUrl.encode(listOf(secondary), lora = null))

        assertNotNull(shared)
        assertNull(shared!!.lora)
        assertEquals(listOf(secondary), shared.channels)
    }

    /** A Firepit invite must never be mistaken for a Meshtastic channel link. */
    @Test
    fun `a firepit invite is not a channel url`() {
        assertNull(ChannelUrl.decode("firepit://join?v=1&d=CghNZXNoQ2hhdA"))
    }
}
