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
 * Whether Firepit's screens may be captured.
 *
 * Off by default. A screenshot of a conversation or of the map outlives every
 * deletion Firepit makes, and ends up in photo backups; the Recents snapshot of
 * an open room is kept by the system, outside the app. Somebody who wants to
 * screenshot the map can say so.
 */
@Singleton
class ScreenPrivacyPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("firepit_screen_privacy", Context.MODE_PRIVATE)

    private val _allowCapture = MutableStateFlow(preferences.getBoolean(KEY, false))
    val allowCapture: StateFlow<Boolean> = _allowCapture.asStateFlow()

    fun setAllowCapture(allow: Boolean) {
        preferences.edit { putBoolean(KEY, allow) }
        _allowCapture.value = allow
    }

    private companion object {
        const val KEY = "allow_screen_capture"
    }
}
