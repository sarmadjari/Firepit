package com.getfirepit.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyClockTest {

    private var wall = 1_767_225_600_000L + 30 * MINUTE
    private var monotonic = 5_000_000L
    private var boot: String? = "1"
    private val stored = object : ClockAnchors {
        var anchor: ClockAnchor? = null

        override fun load() = anchor

        override fun save(anchor: ClockAnchor) {
            this.anchor = anchor
        }
    }

    private fun clock() = KeyClock({ wall }, { monotonic }, { boot }, stored)

    private fun pass(millis: Long) {
        wall += millis
        monotonic += millis
    }

    @Test
    fun `while the clock keeps step, erasing follows it`() {
        val clock = clock()
        assertEquals(wall, clock.eraseMillis())

        pass(3 * HOUR)
        assertEquals(wall, clock.eraseMillis())
    }

    @Test
    fun `a clock set ahead does not erase ahead of the time really passed`() {
        val clock = clock()
        clock.eraseMillis()
        val real = wall

        wall += 5 * HOUR
        assertEquals(real, clock.eraseMillis())

        pass(HOUR)
        assertEquals(real + HOUR, clock.eraseMillis())
    }

    @Test
    fun `a clock put right again is followed from where real time has got to`() {
        val clock = clock()
        clock.eraseMillis()
        wall += 5 * HOUR
        clock.eraseMillis()

        wall -= 5 * HOUR
        pass(10 * MINUTE)
        assertEquals(wall, clock.eraseMillis())
    }

    @Test
    fun `a clock set back holds erasing back with it`() {
        val clock = clock()
        clock.eraseMillis()

        wall -= 2 * HOUR
        assertEquals(wall, clock.eraseMillis())
    }

    @Test
    fun `a small correction counts as keeping step`() {
        val clock = clock()
        clock.eraseMillis()

        wall += 2 * MINUTE
        assertEquals(wall, clock.eraseMillis())
    }

    @Test
    fun `sealing follows the phone's clock, even a wrong one`() {
        val clock = clock()
        clock.eraseMillis()

        wall += 5 * HOUR
        assertEquals(wall, clock.wallMillis())
    }

    @Test
    fun `restarting the app does not forget what real time it is`() {
        clock().eraseMillis()
        val real = wall

        // The clock jumps while the app is not running.
        wall += 5 * HOUR
        assertEquals(real, clock().eraseMillis())
    }

    @Test
    fun `after a restart of the phone the clock is taken as it is`() {
        clock().eraseMillis()

        boot = "2"
        monotonic = 1_000
        wall += 3 * DAY
        assertEquals(wall, clock().eraseMillis())
    }

    @Test
    fun `a new boot's anchor replaces the last one at once`() {
        clock().eraseMillis()

        boot = "2"
        monotonic = 1_000
        clock().eraseMillis()
        assertEquals("2", stored.anchor?.boot)
        assertEquals(1_000L, stored.anchor?.monotonicMillis)
    }

    @Test
    fun `without a boot id, a running app still guards against a clock set ahead`() {
        boot = null
        val clock = clock()
        clock.eraseMillis()
        val real = wall

        wall += 5 * HOUR
        assertEquals(real, clock.eraseMillis())
    }

    @Test
    fun `without a boot id, nothing outlives the app, so no anchor from before a restart can hold erasing back`() {
        boot = null
        clock().eraseMillis()
        assertEquals(null, stored.anchor)

        monotonic += 3 * DAY
        wall += 5 * DAY
        assertEquals(wall, clock().eraseMillis())
    }

    @Test
    fun `a clock the room agrees with is taken as right`() {
        val clock = clock()
        clock.eraseMillis()

        // Put right after the phone started, say: ahead of what the anchor thinks.
        wall += 3 * HOUR
        clock.eraseMillis()
        clock.agreed()
        assertEquals(wall, clock.eraseMillis())
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
