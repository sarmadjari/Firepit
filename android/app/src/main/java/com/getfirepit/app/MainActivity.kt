package com.getfirepit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.app.settings.IdentityPreferences
import com.getfirepit.app.settings.ThemeChoice
import com.getfirepit.app.settings.ThemePreferences
import com.getfirepit.app.ui.FirepitApp
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.designsystem.theme.LocalIdentitySlots
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themePreferences: ThemePreferences

    @Inject lateinit var identityPreferences: IdentityPreferences

    @Inject lateinit var meshRepository: MeshRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val choice by themePreferences.choice.collectAsStateWithLifecycle()
            val slot by identityPreferences.slot.collectAsStateWithLifecycle()
            val myNodeNum by meshRepository.myNodeNum.collectAsStateWithLifecycle()
            FirepitTheme(
                darkTheme = when (choice) {
                    ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                    ThemeChoice.LIGHT -> false
                    ThemeChoice.DARK -> true
                },
            ) {
                CompositionLocalProvider(
                    LocalIdentitySlots provides buildMap {
                        val node = myNodeNum
                        val chosen = slot
                        if (node != null && chosen != null) put(node, chosen)
                    },
                ) {
                    FirepitApp(Modifier.fillMaxSize())
                }
            }
        }
    }
}
