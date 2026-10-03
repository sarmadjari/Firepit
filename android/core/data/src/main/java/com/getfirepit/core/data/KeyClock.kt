package com.getfirepit.core.data

import kotlin.math.abs

/** What time it is, as far as room keys are concerned. */
interface KeyTime {
    /** The phone's clock. Members agree on hours by it, so sealing and opening follow it. */
    fun wallMillis(): Long

    /**
     * How far old keys may be erased up to: never later than the phone's
     * clock, nor than the time that has really passed since that clock was
     * last seen keeping step.
     */
    fun eraseMillis(): Long

    /**
     * The room has just shown the phone's clock is right: another member's
     * message, opened fresh, sealed within an hour of it.
     */
    fun agreed() {}
}

/** The last moment the phone's clock and the time really passed agreed. */
data class ClockAnchor(val wallMillis: Long, val monotonicMillis: Long, val boot: String?)

/** Where that moment is kept, so the app being restarted does not forget it. */
interface ClockAnchors {
    fun load(): ClockAnchor?

    fun save(anchor: ClockAnchor)
}

/**
 * Keeps a clock set wrong from destroying keys that are still needed.
 *
 * Room keys are erased on the hour, so a phone whose clock jumps a day ahead —
 * set by hand, or by a network with the wrong time — would erase keys the rest
 * of the room is still using, and could not read it again even once the clock
 * was put right. So keys are erased on real time: the phone's clock is only
 * believed as far as a monotonic clock (one nothing can set, which keeps
 * counting while the phone sleeps) agrees time has passed since the two last
 * kept step. A clock set ahead costs nothing permanent; a clock set back only
 * keeps keys a little longer.
 *
 * The anchor outlives the app only where the phone says which boot it is
 * in: without that, an anchor from before a restart could pass for this
 * boot's and hold erasing back for days. Where it is missing, a clock changed
 * while the app was not running is believed when it next starts. A clock the
 * room keeps agreeing with is taken as right however it got there, which is
 * how a clock put right after the phone started is caught up with.
 *
 * What is left: a phone that starts up with its clock already wrong has
 * nothing to compare it with.
 */
class KeyClock(
    private val wall: () -> Long,
    private val monotonic: () -> Long,
    private val boot: () -> String?,
    private val anchors: ClockAnchors,
) : KeyTime {

    private var anchor: ClockAnchor? = null
    private var saved: ClockAnchor? = null

    override fun wallMillis(): Long = wall()

    @Synchronized
    override fun eraseMillis(): Long {
        val now = wall()
        val elapsed = monotonic()
        val bootId = boot()
        val known = (anchor ?: loadAnchor(bootId))
            // Another boot, or a monotonic clock that went back, which only a restart does.
            ?.takeIf { it.boot == bootId && elapsed >= it.monotonicMillis }
            ?: return now.also { keepStep(ClockAnchor(now, elapsed, bootId)) }

        val real = known.wallMillis + (elapsed - known.monotonicMillis)
        if (abs(now - real) <= IN_STEP_MILLIS) {
            keepStep(ClockAnchor(now, elapsed, bootId))
            return now
        }
        anchor = known
        return minOf(now, real)
    }

    @Synchronized
    override fun agreed() {
        keepStep(ClockAnchor(wall(), monotonic(), boot()))
    }

    private fun loadAnchor(bootId: String?): ClockAnchor? =
        if (bootId == null) null else anchors.load()?.also { saved = it }

    private fun keepStep(next: ClockAnchor) {
        anchor = next
        if (next.boot == null) return
        val last = saved
        // Written now and then, not on every message: the anchor only moves to
        // absorb drift. At once when what was written belongs to another boot.
        val stale = last == null || last.boot != next.boot || next.monotonicMillis < last.monotonicMillis
        if (stale || next.monotonicMillis - last.monotonicMillis >= SAVE_EVERY_MILLIS) {
            anchors.save(next)
            saved = next
        }
    }

    companion object {
        /** How far the clock may differ from the time really passed and still be taken as keeping step. */
        const val IN_STEP_MILLIS = 5 * 60_000L

        private const val SAVE_EVERY_MILLIS = 10 * 60_000L
    }
}
