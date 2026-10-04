package com.getfirepit.core.crypto

import java.security.GeneralSecurityException
import java.security.PrivateKey

/**
 * One person's words, sealed from this phone to theirs.
 *
 * A direct message already travels under the firmware's PKI, but that is the
 * radios' encryption: a radio hands its private key to any phone that connects
 * to it, so whoever holds either radio could read everything it carried. This
 * seals the words again under a key only the two phones can derive, from the
 * same P-256 keys a room key is sealed to.
 *
 * Static-static ECDH, HMAC-SHA256 and AES-256-GCM. Both phones derive the same
 * secret; when they share a room, that room's hourly key is mixed in too. The
 * direction and both node numbers are mixed into the key and bound as
 * associated data, so a message cannot be turned round or re-addressed. Opening
 * one proves it came from whoever holds the sender's phone key, which a radio
 * in between cannot forge.
 */
object DirectSeal {

    private const val FIRST_VERSION: Byte = 0x01
    private const val VERSION: Byte = 0x02
    private const val HEADER = 1
    private const val FIRST_INFO = "firepit-direct-v1"
    private const val INFO = "firepit-direct-v2"
    private const val ROOM_INFO = "firepit-direct-room-v1"
    private const val RANDOM_SIZE = RoomCipher.NONCE_SIZE - RoomRatchet.HOUR_TAG_SIZE

    /** What sealing costs against the message budget. */
    const val OVERHEAD = HEADER + RoomCipher.OVERHEAD

    data class RoomSecret(
        val roomId: Int,
        val generation: Int,
        val hour: Int,
        val key: ByteArray,
    )

    data class Opening(
        val plain: ByteArray,
        val room: RoomSecret?,
        val hour: Int?,
        val nonce: ByteArray?,
    )

    /**
     * Seals [plaintext] for the holder of [peerPublic].
     *
     * [ownPublic] is this phone's own public key; both halves go into the
     * derivation in a fixed order, so the two phones agree on it.
     */
    fun seal(
        ownPrivate: PrivateKey,
        ownPublic: ByteArray,
        peerPublic: ByteArray,
        plaintext: ByteArray,
        context: ByteArray,
        room: RoomSecret? = null,
    ): ByteArray {
        val key = requireNotNull(keyFor(ownPrivate, ownPublic, peerPublic, context, room)) { "not a usable public key" }
        return try {
            if (room == null) {
                byteArrayOf(FIRST_VERSION) + RoomCipher.seal(key, plaintext, context)
            } else {
                val tag = RoomRatchet.tagOf(room.hour)
                val nonce = byteArrayOf((tag ushr 8).toByte(), tag.toByte()) + RoomCipher.randomBytes(RANDOM_SIZE)
                byteArrayOf(VERSION) + RoomCipher.seal(key, plaintext, context, nonce)
            }
        } finally {
            key.fill(0)
        }
    }

    /**
     * Null when this was not sealed between these two phones, the context
     * differs, a byte was changed, or the version is one this build does not know.
     */
    fun open(
        ownPrivate: PrivateKey,
        ownPublic: ByteArray,
        peerPublic: ByteArray,
        sealed: ByteArray,
        context: ByteArray,
        rooms: Iterable<RoomSecret> = emptyList(),
    ): Opening? {
        if (sealed.size <= HEADER + RoomCipher.OVERHEAD) return null
        if (sealed[0] == FIRST_VERSION) {
            val key = keyFor(ownPrivate, ownPublic, peerPublic, context, null) ?: return null
            return try {
                RoomCipher.open(key, sealed.copyOfRange(HEADER, sealed.size), context)?.let {
                    Opening(it, null, null, null)
                }
            } finally {
                key.fill(0)
            }
        }
        if (sealed[0] != VERSION) return null
        val nonce = nonceOf(sealed) ?: return null
        for (room in rooms) {
            val key = keyFor(ownPrivate, ownPublic, peerPublic, context, room) ?: continue
            val plain = try {
                RoomCipher.open(key, sealed.copyOfRange(HEADER, sealed.size), context)
            } finally {
                key.fill(0)
            }
            if (plain != null) return Opening(plain, room, room.hour, nonce)
        }
        return null
    }

    fun hourTagOf(payload: ByteArray): Int? {
        if (payload.size < HEADER + RoomCipher.OVERHEAD || payload[0] != VERSION) return null
        return ((payload[HEADER].toInt() and 0xFF) shl 8) or (payload[HEADER + 1].toInt() and 0xFF)
    }

    fun nonceOf(payload: ByteArray): ByteArray? =
        if (hourTagOf(payload) == null) null else payload.copyOfRange(HEADER, HEADER + RoomCipher.NONCE_SIZE)

    /**
     * Binds a message to who sent it and who it is for, in that order, so the
     * reply direction uses a different key and neither can be re-addressed.
     */
    fun contextOf(senderNodeNum: Int, recipientNodeNum: Int): ByteArray =
        intBytes(senderNodeNum) + intBytes(recipientNodeNum)

    fun roomPart(room: RoomSecret): ByteArray =
        KeyEnvelope.hmac(
            room.key,
            ROOM_INFO.toByteArray() + intBytes(room.roomId) + intBytes(room.generation) +
                intBytes(room.hour) + byteArrayOf(1),
        )

    private fun keyFor(
        ownPrivate: PrivateKey,
        ownPublic: ByteArray,
        peerPublic: ByteArray,
        context: ByteArray,
        room: RoomSecret?,
    ): ByteArray? {
        // Validated before use: agreeing on a point that is not on the curve is
        // how a static private key is leaked a few bits at a time.
        val peer = KeyEnvelope.decode(peerPublic) ?: return null
        val shared = try {
            KeyEnvelope.agree(ownPrivate, peer)
        } catch (_: GeneralSecurityException) {
            return null
        }
        val salt = ordered(ownPublic, peerPublic)
        val material = room?.let {
            val part = roomPart(it)
            try {
                shared + part
            } finally {
                part.fill(0)
            }
        } ?: shared
        val prk = KeyEnvelope.hmac(salt, material)
        shared.fill(0)
        if (material !== shared) material.fill(0)
        return try {
            KeyEnvelope.hmac(prk, (if (room == null) FIRST_INFO else INFO).toByteArray() + context + byteArrayOf(1))
        } finally {
            prk.fill(0)
        }
    }

    /** Both public keys in one order both phones agree on, whichever of them is asking. */
    private fun ordered(a: ByteArray, b: ByteArray): ByteArray {
        for (index in 0 until minOf(a.size, b.size)) {
            val left = a[index].toInt() and 0xFF
            val right = b[index].toInt() and 0xFF
            if (left != right) return if (left < right) a + b else b + a
        }
        return if (a.size <= b.size) a + b else b + a
    }

    private fun intBytes(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
