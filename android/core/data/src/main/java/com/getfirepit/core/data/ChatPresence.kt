package com.getfirepit.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which conversation is on screen, and whether the app is being looked at.
 *
 * Notifying someone about a message they are already reading is noise, so the
 * UI reports what it is showing and the notifier respects it.
 */
@Singleton
class ChatPresence @Inject constructor() {

    private val _openChannel = MutableStateFlow<Int?>(null)
    val openChannel: StateFlow<Int?> = _openChannel.asStateFlow()

    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    fun setOpenChannel(channel: Int?) {
        _openChannel.value = channel
    }

    fun setForeground(foreground: Boolean) {
        _foreground.value = foreground
    }

    /** True when a new message on [channel] would land in front of the reader. */
    fun isWatching(channel: Int): Boolean = _foreground.value && _openChannel.value == channel
}
