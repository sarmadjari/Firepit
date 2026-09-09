package com.getfirepit.app.ui

import androidx.annotation.DrawableRes
import com.getfirepit.core.designsystem.component.FirepitIcons

/** The three tabs, in the order the design's bottom bar shows them. */
enum class TopLevelDestination(val label: String, @param:DrawableRes val icon: Int) {
    CHATS("Chats", FirepitIcons.Chats),
    MAP("Map", FirepitIcons.Map),
    SETTINGS("Settings", FirepitIcons.Settings),
}
