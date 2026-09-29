package com.getfirepit.core.protocol

import java.security.MessageDigest
import java.util.Base64

/**
 * A short, readable digest of a node's public key.
 *
 * Names on a mesh prove nothing: anyone can call themselves anything, on any
 * radio. The key is the part that cannot be borrowed, so two people who read
 * the same fingerprint aloud have checked something worth checking.
 */
object KeyFingerprint {
    /** Six bytes: short enough to say out loud, long enough to be worth saying. */
    private const val BYTES = 6

    fun of(publicKeyBase64: String?): String? {
        val key = publicKeyBase64?.takeIf { it.isNotBlank() } ?: return null
        val raw = runCatching { Base64.getDecoder().decode(key) }.getOrNull() ?: return null
        if (raw.isEmpty()) return null
        return readable(raw)
    }

    /**
     * One line for somebody asking to join: their radio's key and their
     * phone's key together.
     *
     * The room's own key is sealed to the phone key, while the firmware only
     * ever proves the radio key. A line over the radio alone would let whoever
     * held that radio ask in with a phone of their own and still read the same
     * line aloud; over both, a match means the phone in front of you is the
     * one the room key will be sealed to.
     */
    fun ofJoin(radioKey: ByteArray, phoneKey: ByteArray): String? {
        if (radioKey.isEmpty() || phoneKey.isEmpty()) return null
        return readable(JOIN_DOMAIN.toByteArray() + radioKey + phoneKey)
    }

    private fun readable(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .take(BYTES)
            .joinToString("") { "%02X".format(it) }
            .chunked(4)
            .joinToString(" ")

    /** Keeps a join line from ever equalling a plain key's, whatever the bytes. */
    private const val JOIN_DOMAIN = "firepit-join-v1"
}
