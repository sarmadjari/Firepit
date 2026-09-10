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
        return MessageDigest.getInstance("SHA-256")
            .digest(raw)
            .take(BYTES)
            .joinToString("") { "%02X".format(it) }
            .chunked(4)
            .joinToString(" ")
    }
}
