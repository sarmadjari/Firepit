package com.getfirepit.core.designsystem.component

/**
 * The eight fixed room icons. Deliberately not emoji: those render differently
 * on every platform and would not match between Android and iOS.
 */
enum class RoomIcon(val glyph: String, val label: String) {
    TENT("⌂", "Tent"),
    TRAIL("↟", "Trail"),
    CAR("▤", "Car"),
    MUSIC("♪", "Music"),
    FLAG("⚑", "Flag"),
    HOUSE("⌂", "House"),
    STAR("★", "Star"),
    HEART("♥", "Heart"),
    ;

    companion object {
        val Default = TENT

        /** Stable pick from a room id, so every member sees the same icon. */
        fun forRoomId(roomId: Int): RoomIcon = entries[((roomId % entries.size) + entries.size) % entries.size]
    }
}
