package com.getfirepit.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SeenSealsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val room = 0x0BADF00D
    private val hour = 491_234
    private val nonce = ByteArray(12) { it.toByte() }

    @Test
    fun `a message opens once, and every copy after is refused`() {
        val seen = SeenSeals(null)

        assertTrue(seen.firstSight(room, 1, 42, hour, nonce, hour))
        assertFalse(seen.firstSight(room, 1, 42, hour, nonce, hour))
        assertFalse(seen.firstSight(room, 1, 42, hour, nonce.copyOf(), hour))
    }

    @Test
    fun `different messages are all let through`() {
        val seen = SeenSeals(null)

        assertTrue(seen.firstSight(room, 1, 42, hour, nonce, hour))
        assertTrue(seen.firstSight(room, 1, 42, hour, nonce.copyOf().also { it[11] = 99 }, hour))
        assertTrue(seen.firstSight(room, 1, 43, hour, nonce, hour))
        assertTrue(seen.firstSight(room, 2, 42, hour, nonce, hour))
        assertTrue(seen.firstSight(room + 1, 1, 42, hour, nonce, hour))
    }

    @Test
    fun `restarting the app is not a way to play one back`() {
        val file = folder.newFile("seen")
        SeenSeals(file).firstSight(room, 1, 42, hour, nonce, hour)

        assertFalse(SeenSeals(file).firstSight(room, 1, 42, hour, nonce, hour))
    }

    @Test
    fun `hours no message can open in any more are forgotten`() {
        val file = folder.newFile("seen")
        val seen = SeenSeals(file)
        seen.firstSight(room, 1, 42, hour, nonce, hour)
        seen.firstSight(room, 1, 42, hour + 3, nonce.copyOf().also { it[0] = 1 }, hour + 3)

        assertEquals(1, seen.size())
        assertEquals(1, SeenSeals(file).size())
    }

    @Test
    fun `a damaged file costs the memory of it, not the app`() {
        val file = folder.newFile("seen").apply { writeText("not a record\n1 2 3\n\u0000\u0001") }
        val seen = SeenSeals(file)

        assertEquals(0, seen.size())
        assertTrue(seen.firstSight(room, 1, 42, hour, nonce, hour))
    }
}
