package com.getfirepit.core.protocol

import java.util.Base64
import okio.ByteString
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config

/**
 * Meshtastic's own channel-sharing URL, so Firepit can join a channel somebody
 * made in the official app.
 *
 * Reading only. Firepit issues its own invites, which carry a rotating token
 * and a room key the radio never holds; this format can carry neither, so
 * anything shared through it would be weaker than the person sharing it
 * expects.
 *
 * Mirrors python `Node.setURL`: a `ChannelSet` protobuf, url-safe base64 with
 * the padding stripped, after `/#`. Parsing splits on `/#` rather than matching
 * a host, which is what the reference does and what makes the older `/d/#` and
 * `/c/#` links still work.
 */
object ChannelUrl {

    private const val PREFIX = "https://meshtastic.org/e/#"

    /** The marker the apps use for "add these, do not replace what I have". */
    private const val ADD_SEPARATOR = "/?add=true#"
    private const val SEPARATOR = "/#"

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    data class Shared(
        /** Primary first, then secondaries, exactly as the format orders them. */
        val channels: List<ChannelSettings>,
        val lora: Config.LoRaConfig?,
        /** True when the link asked to be added alongside, rather than replace. */
        val addOnly: Boolean,
    ) {
        val primary: ChannelSettings? get() = channels.firstOrNull()
    }

    /**
     * Not offered to the app: Firepit shares rooms through its own invites.
     * Kept so the decoder can be tested against links this codec produced,
     * rather than only against hand-copied fixtures.
     */
    internal fun encode(channels: List<ChannelSettings>, lora: Config.LoRaConfig?): String =
        PREFIX + encoder.encodeToString(
            ChannelSet(settings = channels, lora_config = lora).encode(),
        )

    /** Null for anything that is not a channel link we can read. */
    fun decode(url: String): Shared? {
        val trimmed = url.trim()
        val addOnly = trimmed.contains(ADD_SEPARATOR)
        val payload = when {
            addOnly -> trimmed.substringAfterLast(ADD_SEPARATOR)
            trimmed.contains(SEPARATOR) -> trimmed.substringAfterLast(SEPARATOR)
            else -> return null
        }.substringBefore('?').trim()
        if (payload.isEmpty()) return null

        val set = try {
            // The reference strips padding on the way out, so put it back.
            ChannelSet.ADAPTER.decode(decoder.decode(payload.padded()))
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: java.io.IOException) {
            return null
        }

        val usable = set.settings.filter { it.psk.isUsableKey() }
        if (usable.isEmpty()) return null

        return Shared(channels = usable, lora = set.lora_config, addOnly = addOnly)
    }

    /**
     * A key length the firmware will actually accept. Anything else would be
     * written to the radio and then silently not work, or — for an empty key on
     * a secondary — inherit the primary's, which is not what the link said.
     */
    private fun ByteString.isUsableKey(): Boolean =
        size == 1 || size == 16 || size == 32

    private fun String.padded(): String = when (length % 4) {
        0 -> this
        else -> this + "=".repeat(4 - length % 4)
    }
}
