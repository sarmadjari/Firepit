package com.getfirepit.core.protocol

/**
 * How long messages are kept before the app deletes them.
 *
 * A mesh has no server and no authority, so this is enforced by each phone on
 * its own copy. It cannot reach anyone else's, and it cannot undo what someone
 * captured off the air. What it does is stop Firepit itself becoming the
 * archive that outlives the conversation.
 */
enum class MessageRetention(val label: String, val days: Int?) {
    DAY("1 day", 1),
    WEEK("1 week", 7),
    MONTH("30 days", 30),
    FOREVER("Keep everything", null),
    ;

    /**
     * Messages sent before this instant are deleted.
     *
     * Null when nothing is deleted, so a caller cannot mistake "keep
     * everything" for a cutoff of zero and erase the lot.
     */
    fun cutoff(nowMillis: Long): Long? = days?.let { nowMillis - it * DAY_MILLIS }

    companion object {
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000

        val DEFAULT = FOREVER

        fun named(name: String?): MessageRetention =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
