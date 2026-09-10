package com.getfirepit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.app.settings.ThemeChoice
import com.getfirepit.app.settings.ThemePreferences
import com.getfirepit.app.ui.FirepitApp
import com.getfirepit.core.designsystem.theme.FirepitTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themePreferences: ThemePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val choice by themePreferences.choice.collectAsStateWithLifecycle()
            FirepitTheme(
                darkTheme = when (choice) {
                    ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                    ThemeChoice.LIGHT -> false
                    ThemeChoice.DARK -> true
                },
            ) {
                FirepitApp(Modifier.fillMaxSize())
            }
        }
    }
}
