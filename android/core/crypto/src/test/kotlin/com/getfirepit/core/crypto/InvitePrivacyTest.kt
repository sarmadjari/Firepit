package com.getfirepit.core.crypto

import com.getfirepit.protocol.meshchat.Invite
import com.getfirepit.protocol.meshchat.Inviter
import com.getfirepit.protocol.meshchat.JoinHello
import com.getfirepit.protocol.meshchat.RoomGrant
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.User

/**
 * What a photographed invite is worth.
 *
 * A code is shown on a screen, where a camera can reach it, so it carries no
 * key material at all. These are the properties that make that true, asserted
 * on the wire format rather than on the app's behaviour: the code is what
 * leaves the phone.
 */
class InvitePrivacyTest {

    private val roomPsk = ByteArray(RoomCrypto.PSK_SIZE) { 7 }
    private val firepitKey = ByteArray(RoomCipher.KEY_SIZE) { 9 }

    private fun invite() = Invite(
        version = InviteCodec.VERSION,
        room_id = 0x0BADF00D,
        room_name = "camp",
        generation = 1,
        inviter = Inviter(
            node_num = 7,
            user = User(public_key = ByteArray(32) { 3 }.toByteString()),
        ),
        invite_id = 0x1234_5678,
        window = RoomCrypto.windowFor(1_000_000_000_000),
        token = ByteArray(RoomCrypto.TOKEN_SIZE) { 1 }.toByteString(),
    )

    /** The whole point: neither key can be written into an invite any more. */
    @Test
    fun `an invite has nowhere to put a room key`() {
        val fields = Invite.ADAPTER.encode(invite())

        assertFalse("the channel key is in the code", fields.containsRun(roomPsk))
        assertFalse("the sealing key is in the code", fields.containsRun(firepitKey))
    }

    @Test
    fun `an invite without an inviter key is refused`() {
        val broken = invite().copy(inviter = Inviter(node_num = 7, user = User()))

        assertNull(InviteCodec.decode(InviteCodec.encode(broken)))
    }

    @Test
    fun `an invite naming no inviter is refused`() {
        val broken = invite().copy(inviter = null)

        assertNull(InviteCodec.decode(InviteCodec.encode(broken)))
    }

    /** A code from a version we do not implement is refused, not guessed at. */
    @Test
    fun `another version is not accepted`() {
        val newer = invite().copy(version = InviteCodec.VERSION + 1)

        assertNull(InviteCodec.decode(InviteCodec.encode(newer)))
        assertEquals(
            InviteCodec.VERSION + 1,
            InviteCodec.declaredVersion("firepit://join?v=${InviteCodec.VERSION + 1}&d=abc"),
        )
    }

    @Test
    fun `a code with no token would never expire, so it is refused`() {
        with(InviteCodec) {
            assertFalse(invite().copy(token = ByteArray(0).toByteString()).isTimeBound())
            assertTrue(invite().isTimeBound())
        }
    }

    // --- the grant ---------------------------------------------------------

    /**
     * The radio on the other end decrypts the grant, and anyone holding that
     * radio can read its private key. So the room's own key rides sealed to
     * the joiner's phone, where the radio cannot open it.
     */
    @Test
    fun `a grant carries the room key sealed to the joiner's phone, never in the clear`() {
        val phone = KeyEnvelope.generateKeyPair()
        val phonePublic = KeyEnvelope.publicBytes(phone.public)
        val context = KeyEnvelope.contextOf(roomId = 0x0BADF00D, generation = 1, recipientNodeNum = 42, hour = 491_234)
        val grant = RoomGrant(
            answer = RoomGrant.Answer.GRANTED,
            invite_id = 0x1234_5678,
            room_id = 0x0BADF00D,
            room_name = "camp",
            room_psk = roomPsk.toByteString(),
            generation = 1,
            sealed_key = KeyEnvelope.seal(phonePublic, firepitKey, context).toByteString(),
            key_hour = 491_234,
        )

        val bytes = grant.encode()
        val decoded = RoomGrant.ADAPTER.decode(bytes)

        assertFalse("the sealing key travels in the clear", bytes.containsRun(firepitKey))
        assertEquals(roomPsk.toByteString(), decoded.room_psk)
        assertArrayEquals(
            firepitKey,
            KeyEnvelope.open(phone.private, phonePublic, decoded.sealed_key.toByteArray(), context),
        )
    }

    @Test
    fun `a refusal carries no keys to leak`() {
        val declined = RoomGrant(
            answer = RoomGrant.Answer.DECLINED,
            invite_id = 0x1234_5678,
            room_id = 0x0BADF00D,
        )

        val bytes = declined.encode()

        assertFalse(bytes.containsRun(roomPsk))
        assertFalse(bytes.containsRun(firepitKey))
    }

    /** The grant is encrypted to these, so a hello without them cannot be answered. */
    @Test
    fun `a hello carries the keys its answer is encrypted to`() {
        val hello = JoinHello(
            invite_id = 0x1234_5678,
            token = ByteArray(RoomCrypto.TOKEN_SIZE) { 1 }.toByteString(),
            joiner_key = ByteArray(32) { 5 }.toByteString(),
            phone_key = KeyEnvelope.publicBytes(KeyEnvelope.generateKeyPair().public).toByteString(),
        )

        val decoded = JoinHello.ADAPTER.decode(hello.encode())

        assertEquals(32, decoded.joiner_key.size)
        assertTrue(KeyEnvelope.isValidPublicKey(decoded.phone_key.toByteArray()))
    }

    // --- the window --------------------------------------------------------

    /**
     * The lookback is how long a photographed code stays worth presenting, so
     * it is held well under the old two-minute figure on purpose.
     */
    @Test
    fun `the inviter forgets its own tokens quickly`() {
        val seconds = RoomCrypto.DEFAULT_LOOKBACK_WINDOWS * RoomCrypto.ROTATION_SECONDS

        assertTrue("a stolen code stays usable for ${seconds}s", seconds <= 60)
    }

    private fun ByteArray.containsRun(needle: ByteArray): Boolean =
        needle.isNotEmpty() && indices.any { start ->
            start + needle.size <= size &&
                needle.indices.all { this[start + it] == needle[it] }
        }
}
