package com.getfirepit.core.crypto

import com.getfirepit.protocol.meshchat.Invite
import com.getfirepit.protocol.meshchat.Inviter
import okio.ByteString.Companion.toByteString
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
    fun `a token from a nearby window still scans`() {
        val key = RoomCrypto.inviteKey(RoomCrypto.generatePsk(), 42, 1)
        val shown = RoomCrypto.token(key, inviterNodeNum = 7, window = 100)

        (98..102).forEach { scannerWindow ->
            assertTrue(
                "window $scannerWindow is within tolerance and must be accepted",
                RoomCrypto.isTokenValid(key, 7, shown, claimedWindow = 100, scannedAtWindow = scannerWindow),
            )
        }
    }

    @Test
    fun `a stale token is refused`() {
        val key = RoomCrypto.inviteKey(RoomCrypto.generatePsk(), 42, 1)
        val shown = RoomCrypto.token(key, inviterNodeNum = 7, window = 100)

        assertFalse(
            "three windows out is roughly half a minute old",
            RoomCrypto.isTokenValid(key, 7, shown, claimedWindow = 100, scannedAtWindow = 103),
        )
    }

    @Test
    fun `a forged token is refused`() {
        val key = RoomCrypto.inviteKey(RoomCrypto.generatePsk(), 42, 1)

        assertFalse(RoomCrypto.isTokenValid(key, 7, ByteArray(8), 100, 100))
        assertFalse("wrong length is not a near miss", RoomCrypto.isTokenValid(key, 7, ByteArray(4), 100, 100))
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
        val scannedAt = now - RoomCrypto.ROTATION_SECONDS * 1000 * 6
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
    }

    @Test
    fun `the encoded form is a firepit link`() {
        assertTrue(InviteCodec.encode(validInvite()).startsWith("firepit://join?v=1&d="))
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
    fun `an invite with a short key is rejected`() {
        // The firmware reads an empty or short PSK as "inherit the primary key",
        // which would quietly create a room anyone on the primary could read.
        val broken = validInvite().copy(room_psk = ByteArray(16).toByteString())

        assertNull(InviteCodec.decode(InviteCodec.encode(broken)))
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
        version = 1,
        room_id = 0x1234_5678,
        room_name = "Camp",
        room_psk = RoomCrypto.generatePsk().toByteString(),
        position_precision = 32,
        generation = 1,
        inviter = Inviter(
            node_num = 7,
            user = User(id = "!00000007", long_name = "Sam", short_name = "SA", public_key = ByteArray(32).toByteString()),
        ),
        invite_id = 0x0BAD_F00D,
        issued_at = 1_788_000_000,
        window = 12_345,
        token = ByteArray(8) { it.toByte() }.toByteString(),
    )
}
