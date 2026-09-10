package com.getfirepit.app.settings

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which palette to draw, independently of what the phone is doing. */
enum class ThemeChoice(val label: String) {
    SYSTEM("Follow the system"),
    LIGHT("Light"),
    DARK("Dark"),
}

/**
 * The chosen theme.
 *
 * Worth overriding the system for: a phone set to follow daylight will flip to
 * a white screen after dark, which ruins night vision at exactly the moment the
 * app is most likely to be out in the dark.
 */
@Singleton
class ThemePreferences @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("firepit_theme", Context.MODE_PRIVATE)

    private val _choice = MutableStateFlow(read())
    val choice: StateFlow<ThemeChoice> = _choice.asStateFlow()

    fun set(choice: ThemeChoice) {
        preferences.edit { putString(KEY_THEME, choice.name) }
        _choice.value = choice
    }

    private fun read(): ThemeChoice = preferences.getString(KEY_THEME, null)
        ?.let { saved -> ThemeChoice.entries.firstOrNull { it.name == saved } }
        ?: ThemeChoice.SYSTEM

    private companion object {
        const val KEY_THEME = "theme_choice"
    }
}
