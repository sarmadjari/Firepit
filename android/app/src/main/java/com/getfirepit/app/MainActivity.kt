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
import com.getfirepit.app.radio.SavedRadioStore
import com.getfirepit.app.rooms.RoomJoinPrompts
import com.getfirepit.app.settings.ThemeChoice
import com.getfirepit.app.settings.ThemePreferences
import com.getfirepit.app.ui.FirepitApp
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.NodeClock
import com.getfirepit.core.data.RoomRepository
import com.getfirepit.core.designsystem.component.FirepitIcons
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.designsystem.theme.IdentityMark
import com.getfirepit.core.designsystem.theme.LocalIdentityMarks
import com.getfirepit.core.designsystem.theme.LocalIdentitySlots
import com.getfirepit.core.protocol.NodeRole
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themePreferences: ThemePreferences

    @Inject lateinit var people: PersonStore

    @Inject lateinit var meshRepository: MeshRepository

    @Inject lateinit var savedRadios: SavedRadioStore

    @Inject lateinit var rooms: RoomRepository

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
            val radios by savedRadios.radios.collectAsStateWithLifecycle()
            val cards by rooms.observePersonCards()
                .collectAsStateWithLifecycle(initialValue = emptyMap())
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
                        cards.forEach { (node, card) ->
                            card.colourSlot?.let { put(node, it) }
                        }
                        val node = myNodeNum
                        val chosen = person?.colourSlot
                        if (node != null && chosen != null) put(node, chosen)
                    },
                    // Decided in one place so every avatar in the app agrees:
                    // people wear the initials they chose, and hardware wears
                    // its role rather than a short name nobody chose to read.
                    LocalIdentityMarks provides buildMap {
                        // Hardware first, so a card takes it back: a role is only
                        // how this phone filed a device when it paired, while a
                        // card is somebody saying they are holding it.
                        radios.forEach { radio ->
                            val icon = when (radio.role) {
                                NodeRole.BASE -> FirepitIcons.RoleBase
                                NodeRole.ROUTER -> FirepitIcons.RoleRouter
                                NodeRole.PERSONAL -> null
                            }
                            val node = radio.nodeNum
                            if (icon != null && node != null) put(node, IdentityMark(icon = icon))
                        }
                        cards.forEach { (node, card) ->
                            card.tag.takeIf { it.isNotBlank() }
                                ?.let { put(node, IdentityMark(tag = it)) }
                        }
                        myNodeNum?.let { node ->
                            person?.tag?.takeIf { it.isNotBlank() }
                                ?.let { put(node, IdentityMark(tag = it)) }
                        }
                    },
                ) {
                    FirepitApp(Modifier.fillMaxSize())
                    ClockOfferDialog(nodeClock)
                    RoomJoinPrompts()
                }
            }
        }
    }
}
