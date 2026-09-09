package com.getfirepit.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshTextLimitTest {

    @Test
    fun `text within the limit is returned untouched`() {
        val text = "Meet at the north gate"

        assertEquals(text, MeshConstants.truncateToBytes(text))
    }

    @Test
    fun `ascii is cut at the byte limit`() {
        val text = "x".repeat(300)

        val result = MeshConstants.truncateToBytes(text)

        assertEquals(MeshConstants.MAX_TEXT_BYTES, result.length)
    }

    @Test
    fun `arabic is never split mid-character`() {
        // Each Arabic letter is two UTF-8 bytes, so an odd limit lands mid-character.
        val text = "مرحبا".repeat(60)

        val result = MeshConstants.truncateToBytes(text, maxBytes = 15)

        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 15)
        assertTrue("truncation must not invent replacement characters", !result.contains('\uFFFD'))
        assertTrue("result must be a prefix of the original", text.startsWith(result))
    }

    @Test
    fun `emoji are never split into lone surrogates`() {
        // A campfire is four UTF-8 bytes and two UTF-16 chars.
        val text = "🔥".repeat(10)

        val result = MeshConstants.truncateToBytes(text, maxBytes = 10)

        assertEquals("two whole emoji fit in ten bytes", "🔥🔥", result)
        assertTrue(result.none { it.isSurrogate() && !result.isValidPair(it) })
    }

    @Test
    fun `a limit smaller than the first character yields empty rather than garbage`() {
        val result = MeshConstants.truncateToBytes("🔥", maxBytes = 3)

        assertEquals("", result)
    }

    private fun String.isValidPair(char: Char): Boolean {
        val index = indexOf(char)
        return if (char.isHighSurrogate()) {
            index + 1 < length && this[index + 1].isLowSurrogate()
        } else {
            index > 0 && this[index - 1].isHighSurrogate()
        }
    }
}
