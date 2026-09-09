package com.getfirepit.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.getfirepit.app.chat.ChatsPane
import com.getfirepit.app.map.MapScreen
import com.getfirepit.app.settings.SettingsScreen

/**
 * Top-level shell.
 *
 * The navigation suite picks a bottom bar, rail or drawer from the window size
 * on its own, so folding the device relocates the navigation without any screen
 * being aware of it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirepitApp(modifier: Modifier = Modifier) {
    // rememberSaveable so the selected tab survives a fold, rotation or process
    // death — all of which recreate the activity.
    var selected by rememberSaveable { mutableStateOf(TopLevelDestination.CHATS) }
    var chatOpen by remember { mutableStateOf(false) }

    val keyboardOpen = WindowInsets.isImeVisible
    val suiteState = rememberNavigationSuiteScaffoldState()

    // Reading and the map get the whole screen. Navigation would otherwise eat
    // a strip of it, and while typing it would sit between the composer and the
    // keyboard.
    val immersive = keyboardOpen ||
        selected == TopLevelDestination.MAP ||
        (selected == TopLevelDestination.CHATS && chatOpen)

    LaunchedEffect(immersive) {
        if (immersive) suiteState.hide() else suiteState.show()
    }

    // Without the bar there is no visible way off the map, so back must work.
    BackHandler(enabled = selected == TopLevelDestination.MAP) {
        selected = TopLevelDestination.CHATS
    }

    NavigationSuiteScaffold(
        // Lift the whole shell in one place. Padding for the keyboard deeper in
        // the tree would stack with the space the navigation bar reserves.
        modifier = modifier.imePadding(),
        state = suiteState,
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                item(
                    selected = selected == destination,
                    onClick = { selected = destination },
                    icon = { Text(destination.glyph) },
                    label = { Text(destination.label) },
                )
            }
        },
    ) {
        when (selected) {
            TopLevelDestination.CHATS -> ChatsPane(onChatOpenChange = { chatOpen = it })
            TopLevelDestination.MAP -> MapScreen()
            TopLevelDestination.SETTINGS -> SettingsScreen()
        }
    }
}
