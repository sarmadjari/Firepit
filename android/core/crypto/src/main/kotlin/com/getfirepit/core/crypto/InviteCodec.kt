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

    /**
     * True when a scan carries Firepit's own marker, whatever the payload turns
     * out to be.
     *
     * The scheme is the identifier, and it is the first nine characters, so
     * this settles which decoder to use without base64 or protobuf work. A code
     * that answers true here is Firepit's to explain: falling through to the
     * Meshtastic decoder would report "not a Meshtastic link" about something
     * that was plainly one of ours.
     */
    fun isFirepitCode(uri: String): Boolean =
        uri.trim().startsWith("$SCHEME://join", ignoreCase = true)

    /**
     * The version a code declares, read from the link itself rather than from
     * its payload, so a format this build cannot parse can still be recognised
     * and named.
     */
    fun declaredVersion(uri: String): Int? = uri.trim()
        .substringAfter("?v=", missingDelimiterValue = "")
        .substringBefore('&')
        .toIntOrNull()

    /** Returns null for anything that is not a well-formed invite of a version we understand. */
    fun decode(uri: String): Invite? {
        val trimmed = uri.trim()
        if (!isFirepitCode(trimmed)) return null

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
     * Rejects invites that would produce a broken room, or one we could never
     * be let into.
     *
     * The inviter's public key is the load-bearing field now: without it there
     * is nowhere to send a hello that only they can open, and no way for the
     * keys to come back.
     */
    private fun Invite.isUsable(): Boolean =
        version == VERSION &&
            room_id != 0 &&
            room_name.toByteArray().size <= MAX_ROOM_NAME_BYTES &&
            inviter?.node_num != 0 &&
            inviter?.user?.public_key?.size == PUBLIC_KEY_SIZE &&
            secret.size == INVITE_SECRET_SIZE

    /**
     * Whether this invite stops working on its own.
     *
     * Without a token an invite is an open door: it carries no keys, but it
     * would let anyone ask to be let in at any time.
     */
    fun Invite.isTimeBound(): Boolean = token.size == RoomCrypto.TOKEN_SIZE

    /** nanopb caps `ChannelSettings.name` at 12 bytes including the terminator. */
    const val MAX_ROOM_NAME_BYTES = 11
    private const val PUBLIC_KEY_SIZE = 32
    const val INVITE_SECRET_SIZE = 16
}

fun ByteArray.toOkio() = toByteString()
