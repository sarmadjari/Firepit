package com.getfirepit.app.settings

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a message's words appear in its notification.
 *
 * Off by default: a notification is read by whoever is looking at the phone,
 * which is not always the person it was sent to.
 */
@Singleton
class NotificationPreferences @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val preferences =
        context.getSharedPreferences("firepit_notifications", Context.MODE_PRIVATE)

    private val _showText = MutableStateFlow(preferences.getBoolean(KEY, false))
    val showText: StateFlow<Boolean> = _showText.asStateFlow()

    fun setShowText(show: Boolean) {
        preferences.edit { putBoolean(KEY, show) }
        _showText.value = show
    }

    private companion object {
        const val KEY = "show_message_text"
    }
}
