package com.getfirepit.core.crypto

import com.getfirepit.protocol.meshchat.Invite
import java.util.Base64
import okio.ByteString.Companion.toByteString

/**
 * Encodes invites for QR codes.
 *
 * The payload only ever travels on a screen or in a link, never over LoRa, so
 * its size is bounded by what scans reliably rather than by the 233-byte mesh
 * budget.
 */
object InviteCodec {

    const val SCHEME = "firepit"
    const val VERSION = 1

    private const val PREFIX = "$SCHEME://join?v=$VERSION&d="

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    fun encode(invite: Invite): String = PREFIX + encoder.encodeToString(invite.encode())

    /** Returns null for anything that is not a well-formed invite of a version we understand. */
    fun decode(uri: String): Invite? {
        val trimmed = uri.trim()
        if (!trimmed.startsWith("$SCHEME://join", ignoreCase = true)) return null

        val payload = trimmed.substringAfter("&d=", missingDelimiterValue = "")
            .ifEmpty { trimmed.substringAfter("?d=", missingDelimiterValue = "") }
        if (payload.isEmpty()) return null

        val invite = try {
            Invite.ADAPTER.decode(decoder.decode(payload))
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: java.io.IOException) {
            return null
        }

        return invite.takeIf { it.isUsable() }
    }

    /**
     * Rejects invites that would produce a broken room. A short PSK is the
     * dangerous one: the firmware treats an empty key as "inherit the primary",
     * so a malformed invite could silently create a readable room.
     */
    private fun Invite.isUsable(): Boolean =
        version == VERSION &&
            room_id != 0 &&
            room_psk.size == RoomCrypto.PSK_SIZE &&
            room_name.toByteArray().size <= MAX_ROOM_NAME_BYTES &&
            inviter?.user?.public_key?.size == PUBLIC_KEY_SIZE

    /** nanopb caps `ChannelSettings.name` at 12 bytes including the terminator. */
    const val MAX_ROOM_NAME_BYTES = 11
    private const val PUBLIC_KEY_SIZE = 32
}

fun ByteArray.toOkio() = toByteString()
