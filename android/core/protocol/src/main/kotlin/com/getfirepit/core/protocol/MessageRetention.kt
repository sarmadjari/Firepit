package com.getfirepit.core.protocol

/**
 * How long messages are kept before the app deletes them.
 *
 * There is deliberately no "keep everything": a chat that never forgets becomes
 * a record of everyone who was ever in it, sitting on a phone that can be lost
 * or taken. The only choice is how long.
 *
 * A mesh has no server and no authority, so this is enforced by each phone on
 * its own copy. It cannot reach anyone else's, and it cannot undo what someone
 * captured off the air.
 */
enum class MessageRetention(val label: String, val days: Int) {
    DAY("1 day", 1),
    WEEK("1 week", 7),
    MONTH("1 month", 30),
    ;

    /** Messages sent before this instant are deleted. */
    fun cutoff(nowMillis: Long): Long = nowMillis - days * DAY_MILLIS

    companion object {
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000

        /** Long enough to hold a conversation, short enough not to be a record. */
        val DEFAULT = WEEK

        fun named(name: String?): MessageRetention =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
