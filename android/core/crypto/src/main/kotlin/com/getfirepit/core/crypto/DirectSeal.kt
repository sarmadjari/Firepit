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
 * Static-static ECDH, HKDF-SHA256 and AES-256-GCM. Both phones derive the same
 * secret; the direction and both node numbers are mixed into the key and bound
 * as associated data, so a message cannot be turned round or re-addressed.
 * Opening one proves it came from whoever holds the sender's phone key, which a
 * radio in between cannot forge.
 */
object DirectSeal {

    private const val VERSION: Byte = 0x01
    private const val HEADER = 1
    private const val INFO = "firepit-direct-v1"

    /** What sealing costs against the message budget. */
    const val OVERHEAD = HEADER + RoomCipher.OVERHEAD

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
    ): ByteArray {
        val key = requireNotNull(keyFor(ownPrivate, ownPublic, peerPublic, context)) { "not a usable public key" }
        return try {
            byteArrayOf(VERSION) + RoomCipher.seal(key, plaintext, context)
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
    ): ByteArray? {
        if (sealed.size <= HEADER + RoomCipher.OVERHEAD || sealed[0] != VERSION) return null
        val key = keyFor(ownPrivate, ownPublic, peerPublic, context) ?: return null
        return try {
            RoomCipher.open(key, sealed.copyOfRange(HEADER, sealed.size), context)
        } finally {
            key.fill(0)
        }
    }

    /**
     * Binds a message to who sent it and who it is for, in that order, so the
     * reply direction uses a different key and neither can be re-addressed.
     */
    fun contextOf(senderNodeNum: Int, recipientNodeNum: Int): ByteArray =
        intBytes(senderNodeNum) + intBytes(recipientNodeNum)

    private fun keyFor(ownPrivate: PrivateKey, ownPublic: ByteArray, peerPublic: ByteArray, context: ByteArray): ByteArray? {
        // Validated before use: agreeing on a point that is not on the curve is
        // how a static private key is leaked a few bits at a time.
        val peer = KeyEnvelope.decode(peerPublic) ?: return null
        val shared = try {
            KeyEnvelope.agree(ownPrivate, peer)
        } catch (_: GeneralSecurityException) {
            return null
        }
        val salt = ordered(ownPublic, peerPublic)
        val prk = KeyEnvelope.hmac(salt, shared)
        shared.fill(0)
        return try {
            KeyEnvelope.hmac(prk, INFO.toByteArray() + context + byteArrayOf(1))
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
