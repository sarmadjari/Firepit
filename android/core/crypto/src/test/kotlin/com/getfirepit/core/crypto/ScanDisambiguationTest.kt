package com.getfirepit.core.crypto

import com.getfirepit.core.protocol.ChannelUrl
import com.getfirepit.protocol.meshchat.Invite
import com.getfirepit.protocol.meshchat.Inviter
import java.util.Base64
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.User

/**
 * One scanner reads two formats, and which one a code is decides how private
 * the result can be. If either codec ever accepted the other's payload, a
 * person would join an ordinary Meshtastic channel believing it was a sealed
 * room, or the reverse.
 */
class ScanDisambiguationTest {

    private val invite = Invite(
        version = InviteCodec.VERSION,
        room_id = 0x0BADF00D,
        room_name = "camp",
        generation = 1,
        inviter = Inviter(
            node_num = -1181562854,
            user = User(public_key = ByteArray(32) { 3 }.toByteString()),
        ),
        token = ByteArray(RoomCrypto.TOKEN_SIZE) { 1 }.toByteString(),
        secret = ByteArray(InviteCodec.INVITE_SECRET_SIZE) { (0x40 + it).toByte() }.toByteString(),
    )

    // Built here rather than through our own encoder, so this is a link of the
    // shape the official clients produce and not a round trip through one object.
    private val channelUrl = "https://meshtastic.org/e/#" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            ChannelSet(
                settings = listOf(ChannelSettings(name = "LongFast", psk = ByteString.of(1))),
                lora_config = Config.LoRaConfig(
                    modem_preset = Config.LoRaConfig.ModemPreset.LONG_FAST,
                ),
            ).encode(),
        )

    @Test
    fun `a firepit invite decodes as one and only one thing`() {
        val encoded = InviteCodec.encode(invite)

        assertNotNull(InviteCodec.decode(encoded))
        assertNull("a Firepit invite was read as a Meshtastic channel", ChannelUrl.decode(encoded))
    }

    @Test
    fun `a meshtastic link decodes as one and only one thing`() {
        assertNotNull(ChannelUrl.decode(channelUrl))
        assertNull("a Meshtastic link was read as a Firepit invite", InviteCodec.decode(channelUrl))
    }

    @Test
    fun `neither codec accepts rubbish`() {
        listOf(
            "",
            "   ",
            "hello",
            "https://example.com/e/#abc",
            "firepit://join?v=1&d=",
        ).forEach { junk ->
            assertNull(junk, InviteCodec.decode(junk))
            assertNull(junk, ChannelUrl.decode(junk))
        }
    }

    /**
     * A code that is photographed is worth nothing on its own, because there is
     * no room key in it to take.
     */
    @Test
    fun `a firepit invite carries no room key material`() {
        val decoded = InviteCodec.decode(InviteCodec.encode(invite))

        assertNotNull(decoded)
        assertEquals(invite.room_id, decoded?.room_id)
        // Encoded bytes, not just the fields: a reserved number could still be
        // written by an older encoder and read by nobody.
        val bytes = decoded!!.encode()
        assertFalse(
            "a 32-byte run in the invite is a key that should not be there",
            bytes.containsRun(ByteArray(RoomCrypto.PSK_SIZE) { 7 }),
        )
    }

    private fun ByteArray.containsRun(needle: ByteArray): Boolean =
        needle.isNotEmpty() && indices.any { start ->
            start + needle.size <= size &&
                needle.indices.all { this[start + it] == needle[it] }
        }

    // --- routing -----------------------------------------------------------

    @Test
    fun `the scheme settles which decoder to use, before any decoding`() {
        assertTrue(InviteCodec.isFirepitCode(InviteCodec.encode(invite)))
        assertFalse(InviteCodec.isFirepitCode(channelUrl))
    }

    @Test
    fun `a firepit invite classifies as firepit`() {
        val code = CodeScanner.classify(InviteCodec.encode(invite))

        assertTrue(code.toString(), code is ScannedCode.Firepit)
        assertEquals(invite.room_id, (code as ScannedCode.Firepit).invite.room_id)
    }

    @Test
    fun `a meshtastic link classifies as meshtastic`() {
        val code = CodeScanner.classify(channelUrl)

        assertTrue(code.toString(), code is ScannedCode.Meshtastic)
        assertEquals("LongFast", (code as ScannedCode.Meshtastic).shared.primary?.name)
    }

    /**
     * The case the old fallback got wrong: a damaged Firepit code was handed to
     * the Meshtastic decoder, which then reported it as neither. It is plainly
     * ours, and saying so is the difference between "ask for a fresh code" and
     * "that is not a code".
     */
    @Test
    fun `a damaged firepit invite stays firepit's problem`() {
        listOf(
            "firepit://join?v=1&d=!!!!not-base64!!!!",
            "firepit://join?v=1&d=",
            "firepit://join",
        ).forEach { broken ->
            val code = CodeScanner.classify(broken)

            assertEquals(
                broken,
                ScannedCode.FirepitUnreadable(ScannedCode.Reason.MALFORMED),
                code,
            )
        }
    }

    @Test
    fun `an invite from a newer app is named as such rather than called broken`() {
        val future = "firepit://join?v=${InviteCodec.VERSION + 1}&d=" +
            InviteCodec.encode(invite).substringAfter("&d=")

        assertEquals(
            ScannedCode.FirepitUnreadable(ScannedCode.Reason.NEWER_VERSION),
            CodeScanner.classify(future),
        )
    }

    @Test
    fun `an older invite is still read, not rejected for its version`() {
        // Only a newer version is refused outright; anything at or below ours
        // goes to the decoder, which judges it on its contents.
        val code = CodeScanner.classify(InviteCodec.encode(invite))

        assertTrue(code is ScannedCode.Firepit)
    }

    @Test
    fun `rubbish is unrecognised, and never blamed on Firepit`() {
        listOf("", "   ", "hello", "https://example.com/", "https://meshtastic.org/e/#zzz!")
            .forEach { junk ->
                assertEquals(junk, ScannedCode.Unrecognised, CodeScanner.classify(junk))
            }
    }

    @Test
    fun `the declared version is read from the link, not the payload`() {
        assertEquals(InviteCodec.VERSION, InviteCodec.declaredVersion(InviteCodec.encode(invite)))
        assertEquals(9, InviteCodec.declaredVersion("firepit://join?v=9&d=abc"))
        assertNull(InviteCodec.declaredVersion("firepit://join"))
        assertNull(InviteCodec.declaredVersion(channelUrl))
    }
}
