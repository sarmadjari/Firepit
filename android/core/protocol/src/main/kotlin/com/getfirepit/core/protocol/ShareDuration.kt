package com.getfirepit.core.protocol

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * How long a location stays shared before the app stops it on its own.
 *
 * A location is not like a message. A message is read and done; a location
 * keeps describing you long after you stopped paying attention to it. The risk
 * was never the moment you started sharing — you meant to do that. It is the
 * following week, when the room has been forgotten and the phone is at home.
 *
 * So the question is *how long*, and "until I turn it off" is a deliberate
 * answer somebody picked rather than what happens when nobody is asked.
 */
enum class ShareDuration(val label: String, val duration: Duration?) {
    HOUR("For 1 hour", 1.hours),
    FOUR_HOURS("For 4 hours", 4.hours),
    DAY("For 1 day", 24.hours),
    UNTIL_OFF("Until I turn it off", null),
    ;

    /** When sharing should stop, or null when it never does on its own. */
    fun endsAt(nowMillis: Long): Long? =
        duration?.let { nowMillis + it.inWholeMilliseconds }

    companion object {
        /** Long enough for an evening out, short enough to be forgotten safely. */
        val DEFAULT = FOUR_HOURS

        fun named(name: String?): ShareDuration =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
