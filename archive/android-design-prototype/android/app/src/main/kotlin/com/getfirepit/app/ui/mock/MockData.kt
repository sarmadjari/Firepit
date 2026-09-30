package com.getfirepit.app.ui.mock

import androidx.compose.ui.graphics.vector.ImageVector
import com.getfirepit.app.ui.icons.FirepitIcons

/** Message status per docs/meshchat-ux-design.md §7.2 — colour + glyph, never colour alone. */
enum class MsgStatus { QUEUED, SENT, HEARD, DELIVERED, FAILED }

data class MockChat(
    val title: String,
    val tag: String? = null,          // people: 2-char tag
    val icon: ImageVector? = null,    // rooms: fixed icon
    val identity: Int = 0,
    val preview: String,
    val time: String,
    val unread: Int = 0,
    val muted: Boolean = false,
    val ownStatus: MsgStatus? = null,
)

data class MockMessage(
    val sender: String? = null,
    val identity: Int = 0,
    val text: String,
    val time: String,
    val out: Boolean = false,
    val status: MsgStatus? = null,
    val alert: Boolean = false,
    val location: Boolean = false,
    val system: Boolean = false,
)

data class MockMember(
    val name: String,
    val tag: String,
    val identity: Int,
    val sub: String,
    val trailing: String? = null,
    val infra: Boolean = false,
)

object Mock {
    val chats = listOf(
        MockChat(title = "Camp", icon = FirepitIcons.Tent, preview = "Ali: On my way", time = "12:41", unread = 2),
        MockChat(title = "Sam", tag = "SA", identity = 7, preview = "See you at the gate", time = "12:20", ownStatus = MsgStatus.HEARD),
        MockChat(title = "Trail", icon = FirepitIcons.Pin, preview = "Live location ended", time = "Yesterday", muted = true),
        MockChat(title = "Lena", tag = "LE", identity = 3, preview = "Where are you?", time = "11:02"),
    )
    val messages = listOf(
        MockMessage(text = "Today", time = "", system = true),
        MockMessage(sender = "Ali", identity = 1, text = "On my way", time = "12:40"),
        MockMessage(text = "See you at the gate", time = "12:41", out = true, status = MsgStatus.HEARD),
        MockMessage(text = "Sam joined · invited by you", time = "", system = true),
        MockMessage(sender = "Sam", identity = 7, text = "Meet at camp now", time = "12:45", alert = true),
        MockMessage(text = "Coming, 5 minutes", time = "12:46", out = true, status = MsgStatus.QUEUED),
        MockMessage(sender = "Lena", identity = 3, text = "Lena's location", time = "12:47", location = true),
    )
    val members = listOf(
        MockMember("You", "SJ", 9, "T-Echo · 82%", trailing = "Personal"),
        MockMember("Sam · invited you", "SA", 7, "T-Echo · 78% · heard 3 min ago"),
        MockMember("Ali · invited by Sam", "AL", 1, "WisMesh Tag · 54% · heard 12 min ago"),
        MockMember("Camp Base", "", 0, "Fixed position · 91%", trailing = "Base", infra = true),
    )
}
