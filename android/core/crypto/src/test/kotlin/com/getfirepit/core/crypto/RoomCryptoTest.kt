package com.getfirepit.core.crypto

import com.getfirepit.protocol.meshchat.Invite
import com.getfirepit.protocol.meshchat.Inviter
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.User

class RoomCryptoTest {

    @Test
    fun `generated keys are the right size and not reused`() {
        val first = RoomCrypto.generatePsk()
        val second = RoomCrypto.generatePsk()

        assertEquals(32, first.size)
        assertFalse("two rooms must never share a key", first.contentEquals(second))
    }

    @Test
    fun `an all-zero key would never be produced`() {
        repeat(20) {
            assertFalse(RoomCrypto.generatePsk().all { byte -> byte == 0.toByte() })
        }
    }

    @Test
    fun `room ids are never zero`() {
        repeat(200) { assertNotEquals(0, RoomCrypto.generateRoomId()) }
    }

    @Test
    fun `every key holder derives the same invite key`() {
        val psk = RoomCrypto.generatePsk()

        val mine = RoomCrypto.inviteKey(psk, roomId = 42, generation = 1)
        val theirs = RoomCrypto.inviteKey(psk, roomId = 42, generation = 1)

        assertTrue("any member must be able to invite", mine.contentEquals(theirs))
    }

    @Test
    fun `rotating the room key changes the invite key`() {
        val psk = RoomCrypto.generatePsk()

        val before = RoomCrypto.inviteKey(psk, roomId = 42, generation = 1)
        val after = RoomCrypto.inviteKey(psk, roomId = 42, generation = 2)

        assertFalse("a removed member's old invites must stop working", before.contentEquals(after))
    }

    @Test
    fun `a different room never derives the same invite key`() {
        val psk = RoomCrypto.generatePsk()

        val roomA = RoomCrypto.inviteKey(psk, roomId = 1, generation = 1)
        val roomB = RoomCrypto.inviteKey(psk, roomId = 2, generation = 1)

        assertFalse(roomA.contentEquals(roomB))
    }

    @Test
    fun `the token changes every window`() {
        val key = RoomCrypto.inviteKey(RoomCrypto.generatePsk(), 42, 1)

        val first = RoomCrypto.token(key, inviterNodeNum = 7, window = 100)
        val next = RoomCrypto.token(key, inviterNodeNum = 7, window = 101)

        assertEquals(8, first.size)
        assertFalse(first.contentEquals(next))
    }

    @Test
    fun `the token is bound to the inviter`() {
        val key = RoomCrypto.inviteKey(RoomCrypto.generatePsk(), 42, 1)

        val mine = RoomCrypto.token(key, inviterNodeNum = 7, window = 100)
        val theirs = RoomCrypto.token(key, inviterNodeNum = 8, window = 100)

        assertFalse(mine.contentEquals(theirs))
    }

    @Test
    fun `a forged token is refused`() {
        val key = RoomCrypto.inviteKey(RoomCrypto.generatePsk(), 42, 1)
        val now = 1_000_000_000_000

        assertFalse(RoomCrypto.matchesRecentToken(key, 7, ByteArray(8), now))
        assertFalse(
            "wrong length is not a near miss",
            RoomCrypto.matchesRecentToken(key, 7, ByteArray(4), now),
        )
    }

    @Test
    fun `windows advance at the rotation rate`() {
        val start = RoomCrypto.windowFor(1_000_000_000_000)
        val later = RoomCrypto.windowFor(1_000_000_000_000 + RoomCrypto.ROTATION_SECONDS * 1000)

        assertEquals(start + 1, later)
    }

    @Test
    fun `a join hello is matched without being told which window it came from`() {
        val key = RoomCrypto.inviteKey(RoomCrypto.generatePsk(), 42, 1)
        val now = 1_000_000_000_000
        val windowsBack = RoomCrypto.DEFAULT_LOOKBACK_WINDOWS - 1
        val scannedAt = now - RoomCrypto.ROTATION_SECONDS * 1000 * windowsBack
        val echoed = RoomCrypto.token(key, 7, RoomCrypto.windowFor(scannedAt))

        assertTrue(RoomCrypto.matchesRecentToken(key, 7, echoed, now))
    }

    @Test
    fun `a token older than the lookback is refused`() {
        val key = RoomCrypto.inviteKey(RoomCrypto.generatePsk(), 42, 1)
        val now = 1_000_000_000_000
        val tooOld = now - RoomCrypto.ROTATION_SECONDS * 1000 * (RoomCrypto.DEFAULT_LOOKBACK_WINDOWS + 1)
        val echoed = RoomCrypto.token(key, 7, RoomCrypto.windowFor(tooOld))

        assertFalse(RoomCrypto.matchesRecentToken(key, 7, echoed, now))
    }

    @Test
    fun `a token minted for another inviter is refused`() {
        val key = RoomCrypto.inviteKey(RoomCrypto.generatePsk(), 42, 1)
        val now = 1_000_000_000_000
        val someoneElse = RoomCrypto.token(key, 8, RoomCrypto.windowFor(now))

        assertFalse(RoomCrypto.matchesRecentToken(key, 7, someoneElse, now))
        assertFalse("wrong length is not a near miss", RoomCrypto.matchesRecentToken(key, 7, ByteArray(4), now))
    }
}

class InviteCodecTest {

    @Test
    fun `an invite survives the round trip`() {
        val invite = validInvite()

        val decoded = InviteCodec.decode(InviteCodec.encode(invite))

        assertEquals(invite, decoded)
        assertEquals(invite.secret, decoded?.secret)
    }

    @Test
    fun `an invite without the in-person secret is unusable`() {
        val broken = validInvite().copy(secret = ByteArray(0).toByteString())

        assertNull(InviteCodec.decode(InviteCodec.encode(broken)))
    }

    @Test
    fun `qr payload length reports the in-person secret cost`() {
        val fullUser = User(
            id = "!12345678",
            long_name = "Alex Firepit",
            short_name = "AF",
            public_key = ByteArray(32) { (0x50 + it).toByte() }.toByteString(),
        )
        val withSecret = validInvite().copy(
            room_name = "12345678901",
            inviter = Inviter(node_num = 0x12345678, user = fullUser),
        )
        val withoutSecret = withSecret.copy(secret = ByteArray(0).toByteString())

        println("Invite QR base64 chars without secret: ${InviteCodec.encode(withoutSecret).substringAfter("&d=").length}")
        println("Invite QR base64 chars with secret: ${InviteCodec.encode(withSecret).substringAfter("&d=").length}")
        assertTrue(InviteCodec.encode(withSecret).length > InviteCodec.encode(withoutSecret).length)
    }

    @Test
    fun `the encoded form is a firepit link`() {
        assertTrue(
            InviteCodec.encode(validInvite())
                .startsWith("firepit://join?v=${InviteCodec.VERSION}&d="),
        )
    }

    @Test
    fun `junk is rejected rather than half-parsed`() {
        assertNull(InviteCodec.decode(""))
        assertNull(InviteCodec.decode("hello"))
        assertNull(InviteCodec.decode("https://example.com/join?v=1&d=abc"))
        assertNull(InviteCodec.decode("firepit://join?v=1&d=!!!not-base64!!!"))
        assertNull(InviteCodec.decode("firepit://join?v=1&d="))
    }

    @Test
    fun `an invite carrying a token expires on its own`() {
        with(InviteCodec) {
            assertTrue(validInvite().copy(token = ByteArray(RoomCrypto.TOKEN_SIZE).toByteString()).isTimeBound())
        }
    }

    @Test
    fun `an invite with no token would last forever, so it is not time bound`() {
        with(InviteCodec) {
            assertFalse(validInvite().copy(token = ByteArray(0).toByteString()).isTimeBound())
        }
    }

    @Test
    fun `a token of the wrong length is not accepted as a window`() {
        with(InviteCodec) {
            assertFalse(validInvite().copy(token = ByteArray(4).toByteString()).isTimeBound())
        }
    }

    @Test
    fun `an invite with an over-long name is rejected`() {
        val broken = validInvite().copy(room_name = "a".repeat(12))

        assertNull(InviteCodec.decode(InviteCodec.encode(broken)))
    }

    @Test
    fun `an invite without the inviter public key is rejected`() {
        // Without it the joiner cannot send the join hello as a PKI direct message.
        val broken = validInvite().copy(inviter = Inviter(node_num = 7, user = User(id = "!7")))

        assertNull(InviteCodec.decode(InviteCodec.encode(broken)))
    }

    private fun validInvite() = Invite(
        version = InviteCodec.VERSION,
        room_id = 0x1234_5678,
        room_name = "Camp",
        generation = 1,
        inviter = Inviter(
            node_num = 7,
            user = User(id = "!00000007", long_name = "Sam", short_name = "SA", public_key = ByteArray(32).toByteString()),
        ),
        invite_id = 0x0BAD_F00D,
        issued_at = 1_788_000_000,
        window = 12_345,
        token = ByteArray(8) { it.toByte() }.toByteString(),
        secret = ByteArray(InviteCodec.INVITE_SECRET_SIZE) { (0xA0 + it).toByte() }.toByteString(),
    )
}
