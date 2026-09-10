package com.getfirepit.app.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themePreferences: ThemePreferences,
) : ViewModel() {

    val theme: StateFlow<ThemeChoice> = themePreferences.choice

    fun chooseTheme(choice: ThemeChoice) = themePreferences.set(choice)
}
