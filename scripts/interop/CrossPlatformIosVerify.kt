package com.getfirepit.core.crypto

import com.getfirepit.protocol.meshchat.MeshMode
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPrivateKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.meshtastic.proto.Config
import org.meshtastic.proto.HardwareModel

/**
 * Opens what the iOS app sealed and encoded (ios-vectors.json, written by FirepitCryptoTests' IOSVectorWriter) with
 * the Android app's own crypto. Run by scripts/check-android-interop.sh on a scratch copy; not part of the app.
 */
class CrossPlatformIosVerify {
    private val vectors: Map<String, String> by lazy {
        val dir = System.getenv("FIREPIT_VECTORS_DIR") ?: error("set FIREPIT_VECTORS_DIR")
        val text = File(dir, "ios-vectors.json").readText()
        Regex("\"(\\w+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun bytes(key: String): ByteArray =
        vectors.getValue(key).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun privateFrom(scalarKey: String): PrivateKey {
        val params = (
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair().public as ECPublicKey
            ).params
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, bytes(scalarKey)), params))
    }

    private val sender = -1181562854

    @Test
    fun `a room message sealed on iOS opens on Android`() {
        assertArrayEquals(bytes("textContext"), SealedText.contextOf(0x0BADF00D, sender))
        // Derived here from the room key alone: if the two apps moved keys on differently, it would not open.
        val generation = vectors.getValue("ratchetGeneration").toInt()
        val hour = vectors.getValue("ratchetHour").toInt()
        val hourKey = requireNotNull(
            RoomRatchet.forward(bytes("roomKey"), 0x0BADF00D, generation, vectors.getValue("ratchetFromHour").toInt(), hour),
        )
        val senderKey = RoomRatchet.senderKey(hourKey, 0x0BADF00D, generation, hour, sender)
        assertEquals(RoomRatchet.tagOf(hour), SealedText.hourTagOf(bytes("sealedText")))
        assertArrayEquals(bytes("textPlain"), SealedText.open(senderKey, bytes("sealedText"), bytes("textContext")))
    }

    @Test
    fun `a room key sealed on iOS to this phone opens on Android`() {
        assertArrayEquals(bytes("envelopeContext"), KeyEnvelope.contextOf(0x0BADF00D, 3, -42, 491_234))
        assertArrayEquals(
            bytes("roomKey"),
            KeyEnvelope.open(privateFrom("joinerScalar"), bytes("joinerPublic"), bytes("envelope"), bytes("envelopeContext")),
        )
        val inviteHedge = KeyEnvelope.inviteHedge(bytes("inviteRandom"), 0x0BADF00D, 0x0F0F_1234)
        assertArrayEquals(bytes("inviteHedge"), inviteHedge)
        assertArrayEquals(
            bytes("roomKey"),
            KeyEnvelope.open(
                privateFrom("joinerScalar"), bytes("joinerPublic"), bytes("hedgedEnvelope"),
                bytes("envelopeContext"), inviteHedge,
            ),
        )
    }

    @Test
    fun `a direct message sealed on iOS opens for both phones on Android`() {
        assertArrayEquals(bytes("directContext"), DirectSeal.contextOf(11, sender))
        assertArrayEquals(
            bytes("directPlain"),
            DirectSeal.open(privateFrom("bobScalar"), bytes("bobPublic"), bytes("alicePublic"), bytes("direct"), bytes("directContext"))?.plain,
        )
        assertArrayEquals(
            bytes("directPlain"),
            DirectSeal.open(privateFrom("aliceScalar"), bytes("alicePublic"), bytes("bobPublic"), bytes("direct"), bytes("directContext"))?.plain,
        )
        val room = DirectSeal.RoomSecret(
            vectors.getValue("directRoomId").toInt(),
            vectors.getValue("directRoomGeneration").toInt(),
            vectors.getValue("directRoomHour").toInt(),
            bytes("directRoomHourly"),
        )
        assertArrayEquals(
            bytes("directPlain"),
            DirectSeal.open(
                privateFrom("bobScalar"), bytes("bobPublic"), bytes("alicePublic"),
                bytes("directV2"), bytes("directContext"), listOf(room),
            )?.plain,
        )
    }

    @Test
    fun `invite keys and QR tokens agree`() {
        val inviteKey = RoomCrypto.inviteKey(bytes("psk"), 0x0BADF00D, 3)
        assertArrayEquals(bytes("inviteKey"), inviteKey)
        val window = RoomCrypto.windowFor(1_789_000_000_123)
        assertEquals(vectors.getValue("window").toInt(), window)
        assertArrayEquals(bytes("token"), RoomCrypto.token(inviteKey, sender, window))
    }

    @Test
    fun `an iOS invite decodes on Android and re-encodes identically`() {
        val uri = vectors.getValue("inviteUri")
        val invite = InviteCodec.decode(uri) ?: error("the iOS invite did not decode")
        assertEquals(0x0BADF00D, invite.room_id)
        assertEquals("Trail \uD83E\uDD7E", invite.room_name)
        assertEquals(3, invite.generation)
        assertEquals(Config.LoRaConfig.ModemPreset.MEDIUM_FAST, invite.lora?.modem_preset)
        assertEquals(Config.LoRaConfig.RegionCode.US, invite.lora?.region)
        assertEquals(4, invite.lora?.hop_limit)
        assertEquals(MeshMode.GROUP_ONLY, invite.lora?.mesh_mode)
        assertEquals(sender, invite.inviter?.node_num)
        assertEquals("Lena من", invite.inviter?.user?.long_name)
        assertEquals(HardwareModel.RAK4631, invite.inviter?.user?.hw_model)
        assertArrayEquals(bytes("token"), invite.token.toByteArray())
        assertArrayEquals(bytes("inviteRandom"), invite.secret.toByteArray())
        assertEquals(uri, InviteCodec.encode(invite))
    }
}
