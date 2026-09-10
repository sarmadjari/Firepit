package com.getfirepit.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerNameTest {

    @Test
    fun `initials when the name has several words`() {
        assertEquals("SJ", OwnerName.suggestShort("Sam Jones"))
        assertEquals("ABC", OwnerName.suggestShort("anna bea carter"))
    }

    @Test
    fun `opening letters when the name is one word`() {
        assertEquals("SARM", OwnerName.suggestShort("Sarmad"))
    }

    @Test
    fun `a long single word is cut to the radio's four bytes`() {
        assertEquals(4, OwnerName.suggestShort("Bartholomew").length)
    }

    @Test
    fun `arabic is cut on a character, not in the middle of one`() {
        // Each of these is two bytes, so only two fit in four.
        val short = OwnerName.shortName("سلام")
        assertEquals("سل", short)
        assertTrue(short.toByteArray(Charsets.UTF_8).size <= OwnerName.MAX_SHORT_BYTES)
    }

    @Test
    fun `an emoji is kept whole or dropped, never split`() {
        val short = OwnerName.shortName("🔥🔥")
        // One four-byte emoji fits; half of one would be a broken character.
        assertEquals("🔥", short)
    }

    @Test
    fun `a long name is capped at thirty-nine bytes`() {
        val long = OwnerName.longName("x".repeat(80))
        assertEquals(OwnerName.MAX_LONG_BYTES, long.length)
    }

    @Test
    fun `surrounding space is not spent on the budget`() {
        assertEquals("Sam", OwnerName.longName("  Sam  "))
    }

    @Test
    fun `fits reports what the radio will accept`() {
        assertTrue(OwnerName.fits("Sam Jones", "SJ"))
        assertFalse(OwnerName.fits("x".repeat(40), "SJ"))
        assertFalse(OwnerName.fits("Sam", "TOOLONG"))
    }
}
