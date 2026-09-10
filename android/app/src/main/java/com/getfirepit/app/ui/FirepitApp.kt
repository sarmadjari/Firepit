package com.getfirepit.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
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
import androidx.compose.ui.res.painterResource
import com.getfirepit.app.chat.ChatsPane
import com.getfirepit.app.map.MapScreen
import com.getfirepit.app.settings.SettingsScreen
import com.getfirepit.core.designsystem.theme.FirepitTheme

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
    var settingsDetailOpen by remember { mutableStateOf(false) }

    val keyboardOpen = WindowInsets.isImeVisible
    val suiteState = rememberNavigationSuiteScaffoldState()

    // Reading gets the whole screen. Navigation would otherwise eat a strip of
    // it, and while typing it would sit between the composer and the keyboard.
    // The map keeps the bar: it is a place you pass through, not one you read.
    val immersive = keyboardOpen ||
        (selected == TopLevelDestination.CHATS && chatOpen) ||
        (selected == TopLevelDestination.SETTINGS && settingsDetailOpen)

    LaunchedEffect(immersive) {
        if (immersive) suiteState.hide() else suiteState.show()
    }

    val navigationColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = FirepitTheme.colors.bubbleOut,
            unselectedIconColor = FirepitTheme.colors.textSecondary,
            unselectedTextColor = FirepitTheme.colors.textSecondary,
        ),
    )

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
                    icon = {
                        Icon(
                            painter = painterResource(destination.icon),
                            // The item's own label already announces it.
                            contentDescription = null,
                        )
                    },
                    label = { Text(destination.label) },
                    colors = navigationColors,
                )
            }
        },
    ) {
        when (selected) {
            TopLevelDestination.CHATS -> ChatsPane(onChatOpenChange = { chatOpen = it })
            TopLevelDestination.MAP -> MapScreen(
                onBack = { selected = TopLevelDestination.CHATS },
                onOpenOfflineAreas = { selected = TopLevelDestination.SETTINGS },
            )
            TopLevelDestination.SETTINGS ->
                SettingsScreen(onImmersiveChange = { settingsDetailOpen = it })
        }
    }
}
