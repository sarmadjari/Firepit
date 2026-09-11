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
import com.getfirepit.app.settings.RetentionStore
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.getfirepit.app.settings.PersonStore
import com.getfirepit.app.settings.ThemeChoice
import com.getfirepit.app.settings.ThemePreferences
import com.getfirepit.app.ui.FirepitApp
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.NodeClock
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.designsystem.theme.LocalIdentitySlots
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themePreferences: ThemePreferences

    @Inject lateinit var people: PersonStore

    @Inject lateinit var meshRepository: MeshRepository

    @Inject lateinit var nodeClock: NodeClock

    @Inject lateinit var retention: RetentionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // At launch rather than on a timer: a phone left closed for a month
        // should catch up the moment it is opened.
        lifecycleScope.launch { retention.sweep() }
        enableEdgeToEdge()
        setContent {
            val choice by themePreferences.choice.collectAsStateWithLifecycle()
            val person by people.person.collectAsStateWithLifecycle()
            val myNodeNum by meshRepository.myNodeNum.collectAsStateWithLifecycle()
            FirepitTheme(
                darkTheme = when (choice) {
                    ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                    ThemeChoice.LIGHT -> false
                    ThemeChoice.DARK -> true
                },
            ) {
                CompositionLocalProvider(
                    // Your colour follows you onto whichever radio you are
                    // holding, rather than belonging to the radio.
                    LocalIdentitySlots provides buildMap {
                        val node = myNodeNum
                        val chosen = person?.colourSlot
                        if (node != null && chosen != null) put(node, chosen)
                    },
                ) {
                    FirepitApp(Modifier.fillMaxSize())
                    ClockOfferDialog(nodeClock)
                }
            }
        }
    }
}
