package com.getfirepit.app.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TextHighlightTest {

    @Test
    fun `finds every occurrence, not just the first`() {
        assertEquals(
            listOf(0 until 4, 12 until 16),
            highlightRanges("camp at the camp", "camp"),
        )
    }

    @Test
    fun `ignores case, because nobody types a search the way it was written`() {
        assertEquals(listOf(0 until 4), highlightRanges("Camp", "cAmP"))
    }

    @Test
    fun `an empty or blank query highlights nothing`() {
        assertEquals(emptyList<IntRange>(), highlightRanges("camp", ""))
        assertEquals(emptyList<IntRange>(), highlightRanges("camp", "   "))
    }

    @Test
    fun `a query longer than the text finds nothing`() {
        assertEquals(emptyList<IntRange>(), highlightRanges("hi", "hello"))
    }

    @Test
    fun `runs do not overlap`() {
        // "aa" in "aaa" matches once; the second would reuse a character.
        assertEquals(listOf(0 until 2), highlightRanges("aaa", "aa"))
    }

    @Test
    fun `arabic is matched by character, not by byte`() {
        val ranges = highlightRanges("مرحبا بالعالم", "بالعالم")
        assertEquals(1, ranges.size)
        assertEquals("بالعالم", "مرحبا بالعالم".substring(ranges.first()))
    }

    @Test
    fun `surrounding space in the query is not searched for`() {
        assertEquals(listOf(0 until 4), highlightRanges("camp", "  camp  "))
    }
}
