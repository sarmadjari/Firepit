package com.getfirepit.core.designsystem.component

import androidx.annotation.DrawableRes
import com.getfirepit.core.designsystem.R

/**
 * The eight fixed room icons. Deliberately not emoji: those render differently
 * on every platform and would not match between Android and iOS.
 */
enum class RoomIcon(@param:DrawableRes val res: Int, val label: String) {
    TENT(R.drawable.ic_room_tent, "Tent"),
    TRAIL(R.drawable.ic_room_trail, "Trail"),
    CAR(R.drawable.ic_room_car, "Car"),
    MUSIC(R.drawable.ic_room_music, "Music"),
    FLAG(R.drawable.ic_room_flag, "Flag"),
    HOUSE(R.drawable.ic_room_house, "House"),
    STAR(R.drawable.ic_room_star, "Star"),
    HEART(R.drawable.ic_room_heart, "Heart"),
    ;

    companion object {
        val Default = TENT

        /** Stable pick from a room id, so every member sees the same icon. */
        fun forRoomId(roomId: Int): RoomIcon = entries[((roomId % entries.size) + entries.size) % entries.size]
    }
}
