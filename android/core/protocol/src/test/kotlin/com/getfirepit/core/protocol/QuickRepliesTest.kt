package com.getfirepit.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickRepliesTest {

    @Test
    fun `the defaults are five and each one fits`() {
        assertEquals(5, QuickReplies.DEFAULTS.size)
        QuickReplies.DEFAULTS.forEach { reply ->
            assertTrue(reply, reply.toByteArray(Charsets.UTF_8).size <= QuickReplies.MAX_BYTES)
            assertEquals(reply, QuickReplies.clean(reply))
        }
    }

    @Test
    fun `a reply is one trimmed line`() {
        assertEquals("Back at the car", QuickReplies.clean("  Back at\nthe   car \t"))
    }

    @Test
    fun `nothing but spaces is no reply`() {
        assertNull(QuickReplies.clean(" \n\t "))
    }

    @Test
    fun `a long reply is cut at the byte limit without splitting a character`() {
        // 30 Arabic letters are 60 bytes; 20 of them fill the 40.
        val arabic = "ب".repeat(30)
        val cleaned = QuickReplies.clean(arabic)!!
        assertEquals(20, cleaned.length)
        assertTrue(cleaned.toByteArray(Charsets.UTF_8).size <= QuickReplies.MAX_BYTES)
    }

    @Test
    fun `an emoji is never split`() {
        val cleaned = QuickReplies.clean("x".repeat(38) + "🔥")!!
        assertEquals("x".repeat(38), cleaned)
    }

    @Test
    fun `the kept list drops empty ones and repeats and stops at the most there can be`() {
        val many = (1..15).map { "Reply $it" }
        assertEquals(QuickReplies.MAX_COUNT, QuickReplies.normalise(many).size)
        assertEquals(listOf("OK", "Here"), QuickReplies.normalise(listOf("OK", " ", "OK ", "Here")))
    }
}
