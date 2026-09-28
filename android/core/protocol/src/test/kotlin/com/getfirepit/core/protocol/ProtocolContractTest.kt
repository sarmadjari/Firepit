package com.getfirepit.core.protocol

import com.getfirepit.protocol.meshchat.JoinHello
import com.getfirepit.protocol.meshchat.KeyRotation
import com.getfirepit.protocol.meshchat.MeshChatControl
import com.getfirepit.protocol.meshchat.PersonCard
import com.getfirepit.protocol.meshchat.Receipt
import com.getfirepit.protocol.meshchat.RoomGrant
import com.getfirepit.protocol.meshchat.RosterEntry
import com.getfirepit.protocol.meshchat.RosterSync
import com.getfirepit.protocol.meshchat.SealedMessage
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Constants
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum

/**
 * Canary for the vendored protobuf pin (protos/UPSTREAM.md).
 *
 * Every number here is a wire-contract fact the app builds on. If a re-pin
 * changes one, this fails immediately instead of producing packets the mesh
 * silently drops.
 */
class ProtocolContractTest {

    @Test
    fun `payload budget matches firmware`() {
        // 239 bytes of encrypted Data fit in a 255-byte frame after the 16-byte
        // header; 233 of those are available to Data.payload.
        assertEquals(233, Constants.DATA_PAYLOAD_LEN.value)
    }

    @Test
    fun `control traffic uses the port the protos sanction for private apps`() {
        // portnums.proto: private apps may use PRIVATE_APP directly rather than
        // claim a number, which would mean diverging from the vendored protos.
        assertEquals(256, PortNum.PRIVATE_APP.value)
        assertTrue(PortNum.PRIVATE_APP.value <= PortNum.MAX.value)
    }

    @Test
    fun `text messages and routing keep their well-known port numbers`() {
        assertEquals(1, PortNum.TEXT_MESSAGE_APP.value)
        assertEquals(3, PortNum.POSITION_APP.value)
        assertEquals(4, PortNum.NODEINFO_APP.value)
        assertEquals(5, PortNum.ROUTING_APP.value)
        assertEquals(6, PortNum.ADMIN_APP.value)
        assertEquals(8, PortNum.WAYPOINT_APP.value)
        assertEquals(11, PortNum.ALERT_APP.value)
        assertEquals(67, PortNum.TELEMETRY_APP.value)
    }

    @Test
    fun `mesh packet round-trips`() {
        val packet = MeshPacket(
            to = BROADCAST_NODENUM,
            channel = 3,
            id = 0x1234_5678,
            // Never 0: a phone-built packet with hop_limit 0 and no want_ack is
            // transmitted but never rebroadcast.
            hop_limit = 3,
            want_ack = true,
            decoded = Data(
                portnum = PortNum.TEXT_MESSAGE_APP,
                payload = "meet at the gate".encodeToByteArray().toByteString(),
            ),
        )

        val decoded = MeshPacket.ADAPTER.decode(MeshPacket.ADAPTER.encode(packet))

        assertEquals(packet, decoded)
        assertEquals(3, decoded.hop_limit)
        assertTrue(decoded.want_ack)
        assertEquals(PortNum.TEXT_MESSAGE_APP, decoded.decoded?.portnum)
    }

    @Test
    fun `2_8 signing fields decode as defaults so 2_7 nodes stay compatible`() {
        // A 2.7 node never sets field 22; the app must read that as "unsigned",
        // not as missing data, or the verified badge would misfire.
        val fromOlderFirmware = MeshPacket(from = 42, id = 7)

        val decoded = MeshPacket.ADAPTER.decode(MeshPacket.ADAPTER.encode(fromOlderFirmware))

        assertFalse(decoded.xeddsa_signed)
    }

    @Test
    fun `join hello stays small enough to be a rounding error on air`() {
        val control = MeshChatControl(
            version = 1,
            join_hello = JoinHello(
                invite_id = 0x7F3A_11C2,
                token = ByteArray(8) { it.toByte() }.toByteString(),
                generation = 1,
                app_version = 1,
            ),
        )

        val encoded = MeshChatControl.ADAPTER.encode(control)

        assertEquals(control, MeshChatControl.ADAPTER.decode(encoded))
        assertTrue(
            "Control payload grew to ${encoded.size} bytes; port 300 messages must stay tiny",
            encoded.size <= 40,
        )
    }

    @Test
    fun `a full receipt fits in one packet, sealed`() {
        // Worst case: every id a large fixed32, both lists full to the cap.
        val control = MeshChatControl(
            version = 1,
            receipt = Receipt(
                room_id = -1,
                delivered = List(ReceiptRules.MAX_IDS_PER_PACKET / 2) { Int.MIN_VALUE + it },
                read = List(ReceiptRules.MAX_IDS_PER_PACKET / 2) { Int.MAX_VALUE - it },
            ),
        )

        val encoded = MeshChatControl.ADAPTER.encode(control)
        val onTheWire = encoded.size + SEALING_OVERHEAD

        assertEquals(control, MeshChatControl.ADAPTER.decode(encoded))
        assertTrue(
            "A sealed receipt is $onTheWire bytes, over the ${Constants.DATA_PAYLOAD_LEN.value}-byte payload",
            onTheWire <= Constants.DATA_PAYLOAD_LEN.value,
        )
    }

    @Test
    fun `a full roster sync fits in one packet`() {
        // Worst case on the wire: negative node numbers are large uint32 values,
        // which take the full five bytes as a varint. Measures 208 of 233.
        val control = MeshChatControl(
            version = 1,
            roster_sync = RosterSync(
                room_id = -1,
                entries = List(MAX_ROSTER_ENTRIES) {
                    RosterEntry(node_num = Int.MIN_VALUE + it, invited_by = Int.MIN_VALUE + it)
                },
                truncated = true,
            ),
        )

        val encoded = MeshChatControl.ADAPTER.encode(control)

        assertEquals(control, MeshChatControl.ADAPTER.decode(encoded))
        assertTrue(
            "Roster sync is ${encoded.size} bytes, over the ${Constants.DATA_PAYLOAD_LEN.value}-byte payload",
            encoded.size <= Constants.DATA_PAYLOAD_LEN.value,
        )
    }

    // --- keys sealed to phones travel inside PKI direct messages -------------

    @Test
    fun `a join hello with both keys fits a direct message`() {
        val control = MeshChatControl(
            version = 1,
            join_hello = JoinHello(
                invite_id = -1,
                token = bytes(8),
                generation = Int.MAX_VALUE,
                app_version = Int.MAX_VALUE,
                joiner_key = bytes(32),
                phone_key = bytes(PHONE_KEY),
            ),
        )

        assertFitsDirect(MeshChatControl.ADAPTER.encode(control).size, "join hello")
    }

    @Test
    fun `a grant with its sealed key fits a direct message`() {
        val control = MeshChatControl(
            version = 1,
            room_grant = RoomGrant(
                answer = RoomGrant.Answer.GRANTED,
                invite_id = -1,
                room_id = -1,
                room_name = "a".repeat(MAX_ROOM_NAME_BYTES),
                room_psk = bytes(32),
                generation = Int.MAX_VALUE,
                position_precision = 32,
                sealed_key = bytes(SEALED_KEY),
            ),
        )

        assertFitsDirect(MeshChatControl.ADAPTER.encode(control).size, "room grant")
    }

    /**
     * The heaviest thing Firepit sends: a new key sealed to the phone, the
     * rotation sealed again under the key it replaces, all inside a PKI direct
     * message. Rotations remove one member at a time.
     */
    @Test
    fun `a rotation sealed under the old key fits a direct message`() {
        val rotation = MeshChatControl(
            version = 1,
            key_rotation = KeyRotation(
                room_id = -1,
                generation = Int.MAX_VALUE,
                room_psk = bytes(32),
                room_name = "a".repeat(MAX_ROOM_NAME_BYTES),
                removed = listOf(Int.MIN_VALUE),
                sealed_key = bytes(SEALED_KEY),
            ),
        )
        val sealedSize = MeshChatControl.ADAPTER.encode(rotation).size + SEALING_OVERHEAD
        val carried = MeshChatControl(
            sealed_message = SealedMessage(room_id = -1, generation = Int.MAX_VALUE, ciphertext = bytes(sealedSize)),
        )

        assertFitsDirect(MeshChatControl.ADAPTER.encode(carried).size, "sealed key rotation")
    }

    @Test
    fun `a person card with its phone key fits one sealed packet`() {
        val card = MeshChatControl(
            version = 1,
            person_card = PersonCard(
                name = "a".repeat(39),
                tag = "abcd",
                colour_slot_plus_one = Int.MAX_VALUE,
                phone_key = bytes(PHONE_KEY),
            ),
        )
        val sealedSize = MeshChatControl.ADAPTER.encode(card).size + SEALING_OVERHEAD
        val carried = MeshChatControl(
            sealed_message = SealedMessage(room_id = -1, generation = Int.MAX_VALUE, ciphertext = bytes(sealedSize)),
        )
        val size = MeshChatControl.ADAPTER.encode(carried).size

        assertTrue(
            "A sealed person card is $size bytes, over the ${Constants.DATA_PAYLOAD_LEN.value}-byte payload",
            size <= Constants.DATA_PAYLOAD_LEN.value,
        )
    }

    private fun assertFitsDirect(size: Int, what: String) {
        val budget = Constants.DATA_PAYLOAD_LEN.value - MeshConstants.PKC_OVERHEAD
        assertTrue("A $what is $size bytes, over the $budget bytes a PKI direct message leaves", size <= budget)
    }

    private fun bytes(count: Int) = ByteArray(count) { 0x7F }.toByteString()

    private companion object {
        const val MESHCHAT_CONTROL_PORT = 300
        const val BROADCAST_NODENUM = -1 // 0xFFFFFFFF as a signed uint32

        /** Mirrors RoomRepository.MAX_ROSTER_ENTRIES. */
        const val MAX_ROSTER_ENTRIES = 14

        /** SealedText.OVERHEAD, restated so core:protocol need not see core:crypto. */
        const val SEALING_OVERHEAD = 29

        /** KeyEnvelope.PUBLIC_KEY_SIZE and SEALED_SIZE, restated for the same reason. */
        const val PHONE_KEY = 33
        const val SEALED_KEY = 93

        /** InviteCodec.MAX_ROOM_NAME_BYTES. */
        const val MAX_ROOM_NAME_BYTES = 11
    }
}
