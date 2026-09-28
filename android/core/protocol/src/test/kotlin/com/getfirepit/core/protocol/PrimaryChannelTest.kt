package com.getfirepit.core.protocol

import com.getfirepit.protocol.meshchat.MeshMode
import java.io.File
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings

class PrimaryChannelTest {

    /**
     * The one fact both apps must agree on byte-for-byte. Android and iOS
     * generate from the same `protos/` tree, so the key lives there and this
     * asserts the embedded copy has not drifted from it.
     */
    @Test
    fun `embedded key matches the shared protos file`() {
        val shared = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "protos/meshchat-primary-key.txt") }
            .firstOrNull { it.isFile }
        assertNotNull("protos/meshchat-primary-key.txt not found above the test working directory", shared)

        assertEquals(shared!!.readText().trim(), PrimaryChannel.KEY_BASE64)
    }

    @Test
    fun `key is a full 32 bytes, so the firmware treats it as AES256`() {
        // 16 and 32 are the only private lengths; a 1-byte key is one of the
        // published defaults and an empty one disables encryption entirely.
        assertEquals(32, PrimaryChannel.key.size)
        assertTrue(ChannelKey.of(PrimaryChannel.key.toByteArray()).isPrivate)
    }

    @Test
    fun `group-only names the primary, public-relay leaves it empty`() {
        // The firmware hashes this name into the frequency slot: a name of our
        // own is a slot of our own, and an empty one follows the modem preset
        // onto the public mesh.
        assertEquals("MeshChat", PrimaryChannel.nameFor(RangeMode.GROUP_ONLY))
        assertEquals("", PrimaryChannel.nameFor(RangeMode.PUBLIC_RELAY))
    }

    @Test
    fun `the primary never gateways to the internet or carries a position`() {
        RangeMode.entries.forEach { mode ->
            val settings = PrimaryChannel.channelFor(mode).settings!!
            assertFalse(mode.name, settings.uplink_enabled)
            assertFalse(mode.name, settings.downlink_enabled)
            assertEquals(mode.name, PositionPrecision.DISABLED, settings.module_settings?.position_precision)
            assertEquals(mode.name, Channel.Role.PRIMARY, PrimaryChannel.channelFor(mode).role)
            assertEquals(mode.name, PrimaryChannel.SLOT, PrimaryChannel.channelFor(mode).index)
        }
    }

    @Test
    fun `a channel we just built is recognised as already correct`() {
        RangeMode.entries.forEach { mode ->
            assertTrue(mode.name, PrimaryChannel.matches(PrimaryChannel.channelFor(mode), mode))
            assertEquals(mode.name, mode, PrimaryChannel.modeOf(PrimaryChannel.channelFor(mode)))
        }
    }

    @Test
    fun `switching mode is a rewrite, not a no-op`() {
        val groupOnly = PrimaryChannel.channelFor(RangeMode.GROUP_ONLY)
        assertFalse(PrimaryChannel.matches(groupOnly, RangeMode.PUBLIC_RELAY))
    }

    @Test
    fun `a stock LongFast primary is neither ours nor private`() {
        // What every radio ships with: the published one-byte default key.
        val stock = Channel(
            index = 0,
            role = Channel.Role.PRIMARY,
            settings = ChannelSettings(name = "", psk = byteArrayOf(0x01).toByteString()),
        )

        assertTrue(PrimaryChannel.isPublic(stock))
        assertNull(PrimaryChannel.modeOf(stock))
        RangeMode.entries.forEach { assertFalse(it.name, PrimaryChannel.matches(stock, it)) }
    }

    @Test
    fun `an unwritten primary is reported public rather than assumed fine`() {
        assertTrue(PrimaryChannel.isPublic(null))
        assertTrue(PrimaryChannel.isPublic(Channel(index = 0, settings = ChannelSettings())))
    }

    @Test
    fun `range mode round-trips through the wire enum`() {
        RangeMode.entries.forEach { assertEquals(it, RangeMode.of(it.wire)) }
        assertEquals(RangeMode.GROUP_ONLY, RangeMode.of(MeshMode.GROUP_ONLY))
        assertEquals(RangeMode.PUBLIC_RELAY, RangeMode.of(MeshMode.PUBLIC_RELAY))
    }

    @Test
    fun `an unknown or absent mode falls back to the quiet one`() {
        assertEquals(RangeMode.GROUP_ONLY, RangeMode.DEFAULT)
        assertEquals(RangeMode.DEFAULT, RangeMode.of(null))
        assertEquals(RangeMode.DEFAULT, RangeMode.named(null))
        assertEquals(RangeMode.DEFAULT, RangeMode.named("SOMETHING_A_LATER_BUILD_ADDED"))
        assertEquals(RangeMode.PUBLIC_RELAY, RangeMode.named("PUBLIC_RELAY"))
    }

    /**
     * Nothing is taken over until somebody says so, and "not asked" has to stay
     * distinct from "said no" — only the first is worth interrupting for.
     */
    @Test
    fun `a radio starts undecided rather than claimed`() {
        assertEquals(RadioPrivacy.UNDECIDED, RadioPrivacy.DEFAULT)
        assertEquals(RadioPrivacy.DEFAULT, RadioPrivacy.named(null))
        assertEquals(RadioPrivacy.DEFAULT, RadioPrivacy.named(""))
        assertEquals(RadioPrivacy.DEFAULT, RadioPrivacy.named("SOMETHING_LATER"))
    }

    @Test
    fun `each privacy choice round-trips through its stored name`() {
        RadioPrivacy.entries.forEach { choice ->
            assertEquals(choice, RadioPrivacy.named(choice.name))
        }
    }

    /** Every state has something to show, so the settings screen never renders blank. */
    @Test
    fun `every choice carries wording for the screen`() {
        RadioPrivacy.entries.forEach { choice ->
            assertTrue(choice.name, choice.label.isNotBlank())
            assertTrue(choice.name, choice.summary.isNotBlank())
        }
        RangeMode.entries.forEach { choice ->
            assertTrue(choice.name, choice.label.isNotBlank())
            assertTrue(choice.name, choice.summary.isNotBlank())
        }
    }
}
