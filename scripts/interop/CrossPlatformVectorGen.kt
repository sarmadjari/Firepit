package com.getfirepit.core.crypto

import com.getfirepit.core.model.ChannelRole
import com.getfirepit.core.model.RoomChannel
import com.getfirepit.protocol.meshchat.Invite
import com.getfirepit.protocol.meshchat.Inviter
import com.getfirepit.protocol.meshchat.LoRaProfile
import com.getfirepit.protocol.meshchat.MeshMode
import java.io.File
import java.security.interfaces.ECPrivateKey
import java.util.Base64
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Test
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.User

/** Writes what the Android crypto produces, for the iOS port to open and reproduce byte for byte. Not part of the app. */
class CrossPlatformVectorGen {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun scalar(k: java.security.PrivateKey): ByteArray {
        val raw = (k as ECPrivateKey).s.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun generate() {
        val fields = linkedMapOf<String, String>()
        fun put(k: String, v: String) { fields[k] = q(v) }

        val roomKey = ByteArray(32) { (it * 7 + 1).toByte() }
        val sender = -1181562854
        val text = "Meet at the ridge — نلتقي عند البوابة 👍🏽".toByteArray(Charsets.UTF_8)
        val textCtx = SealedText.contextOf(roomId = 0x0BADF00D, senderNodeNum = sender)
        put("roomKey", hex(roomKey)); put("textPlain", hex(text)); put("textContext", hex(textCtx))
        // roomKey taken as one hour's key, moved on two hours, then this sender's key for that hour.
        val generation = 3
        val fromHour = 491_234
        val sealHour = fromHour + 2
        val hourKey = requireNotNull(RoomRatchet.forward(roomKey, 0x0BADF00D, generation, fromHour, sealHour))
        val senderKey = RoomRatchet.senderKey(hourKey, 0x0BADF00D, generation, sealHour, sender)
        put("ratchetGeneration", generation.toString()); put("ratchetFromHour", fromHour.toString())
        put("ratchetHour", sealHour.toString())
        put("sealedText", hex(SealedText.seal(senderKey, sealHour, text, textCtx)))
        put("roomCipherEmpty", hex(RoomCipher.seal(roomKey, ByteArray(0), ByteArray(0))))

        val joiner = KeyEnvelope.generateKeyPair()
        val joinerPub = KeyEnvelope.publicBytes(joiner.public)
        val envCtx = KeyEnvelope.contextOf(roomId = 0x0BADF00D, generation = 3, recipientNodeNum = -42, hour = 491_234)
        put("joinerScalar", hex(scalar(joiner.private))); put("joinerPkcs8", hex(joiner.private.encoded))
        put("joinerPublic", hex(joinerPub)); put("envelopeContext", hex(envCtx))
        put("envelope", hex(KeyEnvelope.seal(joinerPub, roomKey, envCtx)))

        val alice = KeyEnvelope.generateKeyPair(); val alicePub = KeyEnvelope.publicBytes(alice.public)
        val bob = KeyEnvelope.generateKeyPair(); val bobPub = KeyEnvelope.publicBytes(bob.public)
        val words = "Meet at the ridge at six".toByteArray()
        val dmCtx = DirectSeal.contextOf(senderNodeNum = 11, recipientNodeNum = sender)
        put("aliceScalar", hex(scalar(alice.private))); put("alicePublic", hex(alicePub))
        put("bobScalar", hex(scalar(bob.private))); put("bobPublic", hex(bobPub))
        put("directContext", hex(dmCtx)); put("directPlain", hex(words))
        put("direct", hex(DirectSeal.seal(alice.private, alicePub, bobPub, words, dmCtx)))

        val psk = ByteArray(32) { (255 - it).toByte() }
        val inviteKey = RoomCrypto.inviteKey(psk, 0x0BADF00D, 3)
        val window = RoomCrypto.windowFor(1_789_000_000_123)
        val token = RoomCrypto.token(inviteKey, sender, window)
        put("psk", hex(psk)); put("inviteKey", hex(inviteKey)); put("window", window.toString()); put("token", hex(token))
        put("negativeWindow", RoomCrypto.windowFor(-1L).toString())

        val invite = Invite(
            version = InviteCodec.VERSION,
            room_id = 0x0BADF00D,
            room_name = "Camp 🔥",
            generation = 3,
            lora = LoRaProfile(
                use_preset = true,
                modem_preset = Config.LoRaConfig.ModemPreset.LONG_FAST,
                region = Config.LoRaConfig.RegionCode.EU_868,
                hop_limit = 3,
                mesh_mode = MeshMode.PUBLIC_RELAY,
            ),
            inviter = Inviter(
                node_num = sender,
                user = User(
                    id = "!b992a91a", long_name = "Sam نلتقي", short_name = "SA",
                    public_key = ByteArray(32) { 3 }.toByteString(), hw_model = HardwareModel.T_ECHO,
                ),
            ),
            invite_id = 0x12345678,
            issued_at = 1_789_000_000,
            window = window,
            token = token.toByteString(),
        )
        put("inviteUri", InviteCodec.encode(invite))

        val channelLink = "https://meshtastic.org/e/#" + Base64.getUrlEncoder().withoutPadding().encodeToString(
            ChannelSet(
                settings = listOf(
                    ChannelSettings(name = "LongFast", psk = ByteString.of(1)),
                    ChannelSettings(name = "Friends", psk = ByteArray(16) { 5 }.toByteString(), id = 99),
                ),
                lora_config = Config.LoRaConfig(modem_preset = Config.LoRaConfig.ModemPreset.MEDIUM_FAST, hop_limit = 4),
            ).encode(),
        )
        put("channelLink", channelLink)

        val names = listOf("Camp", "LongFast", "", "نلتقي", "🔥 Fire", "Trail")
        put("seedNames", names.joinToString(",") { hex(it.toByteArray(Charsets.UTF_8)) })
        put("seeds", names.joinToString(",") {
            RoomChannel(index = 1, name = it, role = ChannelRole.SECONDARY, id = 0, positionPrecision = 0).iconSeed.toString()
        })

        val json = fields.entries.joinToString(",\n", "{\n", "\n}\n") { "  ${q(it.key)}: ${it.value}" }
        File(System.getenv("FIREPIT_VECTORS_DIR") ?: error("set FIREPIT_VECTORS_DIR"), "android-vectors.json").writeText(json)
        println("wrote ${fields.size} vectors")
    }
}
