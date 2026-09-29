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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.getfirepit.app.privacy.NoPersonalizedLearning
import com.getfirepit.app.privacy.SecureWindow
import com.getfirepit.app.settings.PersonStore
import com.getfirepit.app.settings.ScreenPrivacyPreferences
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
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themePreferences: ThemePreferences

    @Inject lateinit var people: PersonStore

    @Inject lateinit var meshRepository: MeshRepository

    @Inject lateinit var savedRadios: SavedRadioStore

    @Inject lateinit var rooms: RoomRepository

    @Inject lateinit var nodeClock: NodeClock

    @Inject lateinit var screenPrivacy: ScreenPrivacyPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepOutOfScreenshots()
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
                    NoPersonalizedLearning {
                        FirepitApp(Modifier.fillMaxSize())
                        ClockOfferDialog(nodeClock)
                        RoomJoinPrompts()
                    }
                }
            }
        }
    }

    /**
     * Keeps conversations and the map out of screenshots, screen recordings and
     * the Recents snapshot unless the setting allows them. Applied before the
     * first frame, so not even the opening screen is captured.
     */
    private fun keepOutOfScreenshots() {
        if (!screenPrivacy.allowCapture.value) SecureWindow.hold(window, SCREEN_SETTING)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                screenPrivacy.allowCapture.collect { allow ->
                    if (allow) SecureWindow.release(window, SCREEN_SETTING) else SecureWindow.hold(window, SCREEN_SETTING)
                }
            }
        }
    }

    private companion object {
        /** Stands for the setting among whoever holds the window secure. */
        const val SCREEN_SETTING = "screen-privacy-setting"
    }
}
