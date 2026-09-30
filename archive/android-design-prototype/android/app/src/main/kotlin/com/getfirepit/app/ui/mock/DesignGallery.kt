package com.getfirepit.app.ui.mock

import androidx.compose.runtime.Composable
import com.getfirepit.app.ui.mock.screens.ChatsScreen
import com.getfirepit.app.ui.mock.screens.InviteScreen
import com.getfirepit.app.ui.mock.screens.JoinScreen
import com.getfirepit.app.ui.mock.screens.MapScreen
import com.getfirepit.app.ui.mock.screens.RoomChatScreen
import com.getfirepit.app.ui.mock.screens.RoomInfoScreen
import com.getfirepit.app.ui.mock.screens.ShareLocationScreen
import com.getfirepit.app.ui.mock.screens.TokensScreen

/**
 * Debug-only design gallery: `adb shell am start -n com.getfirepit.app/.MainActivity --es design <name>`.
 * Names: tokens, chats, room-chat, room-info, invite, join, map, share-location.
 */
@Composable
fun DesignScreen(name: String) {
    when (name) {
        "tokens" -> TokensScreen()
        "chats" -> ChatsScreen()
        "room-chat" -> RoomChatScreen()
        "room-info" -> RoomInfoScreen()
        "invite" -> InviteScreen()
        "join" -> JoinScreen()
        "map" -> MapScreen()
        "share-location" -> ShareLocationScreen()
        else -> ChatsScreen()
    }
}
