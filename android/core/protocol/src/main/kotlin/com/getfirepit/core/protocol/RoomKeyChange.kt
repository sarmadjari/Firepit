package com.getfirepit.core.protocol

/** How often this phone changes keys for rooms it made. */
enum class RoomKeyChange {
    DAILY,
    WEEKLY,
    NEVER,
}

/** Pure decision for scheduled room-key changes; ported byte-for-byte to Swift. */
object ScheduledKeyChange {
    const val DAY_MILLIS: Long = 24L * 60L * 60L * 1_000L
    const val WEEK_MILLIS: Long = 7L * DAY_MILLIS

    fun intervalMillis(setting: RoomKeyChange): Long? = when (setting) {
        RoomKeyChange.DAILY -> DAY_MILLIS
        RoomKeyChange.WEEKLY -> WEEK_MILLIS
        RoomKeyChange.NEVER -> null
    }

    fun shouldChange(
        isMaker: Boolean,
        setting: RoomKeyChange,
        keyAgeMillis: Long?,
        hasCurrentGenerationEvidence: Boolean,
        connected: Boolean,
        alreadyRotating: Boolean,
    ): Boolean {
        val interval = intervalMillis(setting) ?: return false
        return isMaker &&
            connected &&
            !alreadyRotating &&
            hasCurrentGenerationEvidence &&
            keyAgeMillis != null &&
            keyAgeMillis >= interval
    }
}
