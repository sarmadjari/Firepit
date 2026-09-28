package com.getfirepit.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemColors
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.getfirepit.app.chat.ChatsPane
import com.getfirepit.app.map.MapScreen
import com.getfirepit.app.settings.SettingsScreen
import com.getfirepit.app.settings.SettingsSection
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import com.getfirepit.core.designsystem.theme.FirepitTheme

/**
 * Top-level shell.
 *
 * Chooses a bottom bar or a rail from the window width, so folding the device
 * relocates the navigation without any screen being aware of it. The bar is
 * Material's short one: the tall variant spends 80dp of a phone screen on three
 * words.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirepitApp(modifier: Modifier = Modifier) {
    // rememberSaveable so the selected tab survives a fold, rotation or process
    // death — all of which recreate the activity.
    var selected by rememberSaveable { mutableStateOf(TopLevelDestination.CHATS) }
    var chatOpen by remember { mutableStateOf(false) }
    var settingsDetailOpen by remember { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf<SettingsSection?>(null) }

    val keyboardOpen = WindowInsets.isImeVisible

    // Reading gets the whole screen. Navigation would otherwise eat a strip of
    // it, and while typing it would sit between the composer and the keyboard.
    // The map keeps the bar: it is a place you pass through, not one you read.
    val immersive = keyboardOpen ||
        (selected == TopLevelDestination.CHATS && chatOpen) ||
        (selected == TopLevelDestination.SETTINGS && settingsDetailOpen)

    val wideEnoughForRail = currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    val itemColors = NavigationItemColors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        selectedIndicatorColor = FirepitTheme.colors.bubbleOut,
        unselectedIconColor = FirepitTheme.colors.textSecondary,
        unselectedTextColor = FirepitTheme.colors.textSecondary,
        disabledIconColor = FirepitTheme.colors.stale,
        disabledTextColor = FirepitTheme.colors.stale,
    )

    val screen: @Composable () -> Unit = {
        when (selected) {
            TopLevelDestination.CHATS -> ChatsPane(onChatOpenChange = { chatOpen = it })
            TopLevelDestination.MAP -> MapScreen(
                onBack = { selected = TopLevelDestination.CHATS },
                onOpenOfflineAreas = {
                    settingsSection = SettingsSection.OFFLINE_MAPS
                    selected = TopLevelDestination.SETTINGS
                },
            )
            TopLevelDestination.SETTINGS ->
                SettingsScreen(
                    openSection = settingsSection,
                    onSectionOpened = { settingsSection = null },
                    onImmersiveChange = { settingsDetailOpen = it },
                )
        }
    }

    if (wideEnoughForRail) {
        Row(modifier.imePadding()) {
            if (!immersive) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationRailItem(
                            selected = selected == destination,
                            onClick = { selected = destination },
                            icon = { DestinationIcon(destination) },
                            label = { Text(destination.label) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = FirepitTheme.colors.bubbleOut,
                                unselectedIconColor = FirepitTheme.colors.textSecondary,
                                unselectedTextColor = FirepitTheme.colors.textSecondary,
                            ),
                        )
                    }
                }
            }
            Box(Modifier.weight(1f)) { screen() }
        }
    } else {
        Scaffold(
            // Lift the whole shell in one place. Padding for the keyboard deeper
            // in the tree would stack with the space the bar reserves.
            modifier = modifier.imePadding(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                AnimatedVisibility(
                    visible = !immersive,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    ShortNavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        TopLevelDestination.entries.forEach { destination ->
                            ShortNavigationBarItem(
                                selected = selected == destination,
                                onClick = { selected = destination },
                                icon = { DestinationIcon(destination) },
                                label = { Text(destination.label) },
                                colors = itemColors,
                            )
                        }
                    }
                }
            },
        ) { padding ->
            // Bottom only. Each screen's own bar already inset itself for the
            // status bar, and padding here as well left a dead band above it.
            Box(Modifier.padding(bottom = padding.calculateBottomPadding())) { screen() }
        }
    }
}

@Composable
private fun DestinationIcon(destination: TopLevelDestination) {
    Icon(
        painter = painterResource(destination.icon),
        // The item's own label already announces it.
        contentDescription = null,
        modifier = Modifier.size(22.dp),
    )
}
