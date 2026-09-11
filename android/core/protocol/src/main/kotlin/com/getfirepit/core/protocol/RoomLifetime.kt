package com.getfirepit.core.protocol

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * How long a room outlives the talking in it.
 *
 * A room nobody uses is a key sitting on a phone and a slot the radio cannot
 * give to anything else. Left alone it survives the trip it was made for by
 * months, which is exactly the copy someone finds later.
 */
enum class RoomLifetime(val label: String, val silence: Duration?) {
    FOREVER("Keep until I leave", null),
    MONTH("After a month of silence", 30.days),
    QUARTER("After three months of silence", 90.days),
    ;

    companion object {
        /** Nothing is forgotten unless it is asked for: leaving a room is not undoable. */
        val DEFAULT = FOREVER

        fun named(name: String?): RoomLifetime =
            entries.firstOrNull { it.name == name } ?: DEFAULT

        /**
         * Which rooms have gone quiet for longer than [lifetime].
         *
         * A room with no messages at all is judged by when it was joined, so a
         * room created and never used still ages out. A room whose last word is
         * in the future, because a radio's clock was wrong, is left alone rather
         * than deleted on the strength of a bad timestamp.
         */
        fun silentRooms(
            lastActivity: Map<Int, Long>,
            lifetime: RoomLifetime,
            nowMillis: Long,
        ): List<Int> {
            val silence = lifetime.silence ?: return emptyList()
            val cutoff = nowMillis - silence.inWholeMilliseconds
            return lastActivity
                .filterValues { it in 1 until cutoff }
                .keys
                .sorted()
        }
    }
}
