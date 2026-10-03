package com.getfirepit.app.ui

/** A conversation that something outside Chats — a notification, a person in Settings — asks Chats to open. */
sealed interface ChatTarget {
    data class Channel(val index: Int) : ChatTarget

    data class Direct(val peer: Int) : ChatTarget
}
