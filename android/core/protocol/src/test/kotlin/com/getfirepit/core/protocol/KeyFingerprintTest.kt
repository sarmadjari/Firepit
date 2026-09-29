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

    @Test
    fun `a join line reads like any other fingerprint`() {
        val line = KeyFingerprint.ofJoin(ByteArray(32) { 1 }, ByteArray(33) { 2 })

        assertTrue("not readable as groups: $line", line!!.matches(Regex("[0-9A-F]{4} [0-9A-F]{4} [0-9A-F]{4}")))
    }

    /**
     * Whoever holds the joiner's radio could ask in with their own phone. The
     * line has to change when only the phone does, or reading it aloud proves
     * nothing about the key the room will be sealed to.
     */
    @Test
    fun `a different phone behind the same radio reads differently`() {
        val radio = ByteArray(32) { 1 }

        assertNotEquals(
            KeyFingerprint.ofJoin(radio, ByteArray(33) { 2 }),
            KeyFingerprint.ofJoin(radio, ByteArray(33) { 3 }),
        )
    }

    @Test
    fun `both phones compute the same join line`() {
        val radio = ByteArray(32) { (it * 3).toByte() }
        val phone = ByteArray(33) { (it * 5).toByte() }

        assertEquals(KeyFingerprint.ofJoin(radio, phone), KeyFingerprint.ofJoin(radio.copyOf(), phone.copyOf()))
    }

    @Test
    fun `a join line is never a plain key's line`() {
        val radio = ByteArray(32) { 7 }

        assertNotEquals(KeyFingerprint.of(Base64.getEncoder().encodeToString(radio)), KeyFingerprint.ofJoin(radio, ByteArray(33) { 7 }))
    }

    @Test
    fun `a join line needs both keys`() {
        assertNull(KeyFingerprint.ofJoin(ByteArray(0), ByteArray(33)))
        assertNull(KeyFingerprint.ofJoin(ByteArray(32), ByteArray(0)))
    }
}
