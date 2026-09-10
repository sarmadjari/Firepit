package com.getfirepit.core.protocol

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class KeyFingerprintTest {
    /** Built rather than pasted: a literal here reads as a leaked credential. */
    private fun keyOf(seed: Int) =
        Base64.getEncoder().encodeToString(ByteArray(32) { (it + seed).toByte() })

    private val key = keyOf(0)

    @Test
    fun `reads as three groups of four`() {
        val fingerprint = KeyFingerprint.of(key)

        assertTrue(
            "not readable as groups: $fingerprint",
            fingerprint!!.matches(Regex("[0-9A-F]{4} [0-9A-F]{4} [0-9A-F]{4}")),
        )
    }

    @Test
    fun `the same key always gives the same fingerprint`() {
        assertEquals(KeyFingerprint.of(key), KeyFingerprint.of(key))
    }

    @Test
    fun `a different key gives a different fingerprint`() {
        assertNotEquals(KeyFingerprint.of(key), KeyFingerprint.of(keyOf(1)))
    }

    @Test
    fun `a node that has shared no key has no fingerprint to show`() {
        assertNull(KeyFingerprint.of(null))
        assertNull(KeyFingerprint.of(""))
        assertNull(KeyFingerprint.of("   "))
    }

    @Test
    fun `nonsense is refused rather than shown as a fingerprint`() {
        assertNull(KeyFingerprint.of("not base64 at all!!"))
    }
}
