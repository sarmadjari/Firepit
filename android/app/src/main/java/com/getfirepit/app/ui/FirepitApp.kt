package com.getfirepit.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.app.chat.ChatsPane
import com.getfirepit.app.map.Following
import com.getfirepit.app.map.MapScreen
import com.getfirepit.app.settings.SettingsScreen
import com.getfirepit.app.settings.SettingsSection
import com.getfirepit.core.designsystem.adaptive.LocalWideScreen
import com.getfirepit.core.designsystem.adaptive.WideScreenControls
import com.getfirepit.core.designsystem.adaptive.chatSideDirective
import com.getfirepit.core.designsystem.adaptive.currentWindowShape
import com.getfirepit.core.designsystem.adaptive.foldAwarePaneDirective
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.model.DividerSettle
import com.getfirepit.core.model.PaneArrangement
import com.getfirepit.core.model.PaneLayout
import com.getfirepit.core.model.PaneLayouts
import com.getfirepit.core.model.PaneNavigation

/**
 * Top-level shell.
 *
 * Lays the window out by the one rule both apps share ([PaneLayouts], UX
 * §6.11): the phone app in a narrow window, with a bottom bar or a rail; the
 * chat side beside the map side in a wide one; map above conversation when
 * half-folded across the screen. Folding, unfolding or resizing moves the
 * screens rather than rebuilding them, so nothing in them is lost. The bar is
 * Material's short one: the tall variant spends 80dp of a phone screen on three
 * words.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun FirepitApp(
    modifier: Modifier = Modifier,
    openRequest: ChatTarget? = null,
    onOpenRequestTaken: () -> Unit = {},
    /** Ctrl+F: search the chats, from wherever the user is. */
    searchAsked: Boolean = false,
    onSearchTaken: () -> Unit = {},
    shell: ShellViewModel = hiltViewModel(),
) {
    // rememberSaveable so the selected tab survives a fold, rotation or process
    // death — all of which recreate the activity.
    var selected by rememberSaveable { mutableStateOf(TopLevelDestination.CHATS) }
    var chatOpen by remember { mutableStateOf(false) }
    var settingsDetailOpen by remember { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf<SettingsSection?>(null) }
    // A conversation asked for from outside Chats: a notification, or a person in Settings. Chats opens it and
    // clears it, so asking twice works.
    var chatTarget by remember { mutableStateOf<ChatTarget?>(null) }
    // Settings across the whole window, while two panes show and no bar or rail carries it.
    var settingsOverPanes by rememberSaveable { mutableStateOf(false) }
    // Chats wants the whole window for a moment: scanning an invite.
    var chatsWholeWindow by remember { mutableStateOf(false) }
    // The side touched last, so folding back to one pane shows that one.
    var lastTouched by rememberSaveable { mutableStateOf(Side.CHAT) }
    // A side being closed: the panes move first, then one pane shows (UX §6.11.11).
    var closing by remember { mutableStateOf<Side?>(null) }
    // Where the chat side starts when two panes open again from one.
    var enterFrom by remember { mutableStateOf<Float?>(null) }
    var wasSplit by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(openRequest) {
        openRequest?.let {
            chatTarget = it
            onOpenRequestTaken()
        }
    }
    // Chats answers it once it is on screen, so a search asked for from the
    // map or Settings in one pane goes to Chats first.
    var chatsSearch by remember { mutableStateOf(false) }
    LaunchedEffect(searchAsked) {
        if (searchAsked) {
            onSearchTaken()
            selected = TopLevelDestination.CHATS
            settingsOverPanes = false
            chatsSearch = true
        }
    }
    LaunchedEffect(chatTarget) {
        if (chatTarget != null) {
            selected = TopLevelDestination.CHATS
            settingsOverPanes = false
        }
    }

    val keyboardOpen = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    // However Settings came over the panes (⚙, Offline areas, unfolding), a
    // composer left focused beneath would keep its keyboard and its keys.
    LaunchedEffect(settingsOverPanes) {
        if (settingsOverPanes) focusManager.clearFocus()
    }
    val choice by shell.choice.collectAsStateWithLifecycle()
    val window = currentWindowShape()
    val chatMin = PaneLayouts.chatMinFor(LocalDensity.current.fontScale)
    val layout = PaneLayouts.layoutFor(window, choice, chatMin)
    val canSplit = PaneLayouts.layoutFor(window, choice.copy(arrangement = PaneArrangement.CHAT_AND_MAP), chatMin) !is
        PaneLayout.OnePane
    val split = layout !is PaneLayout.OnePane
    val splitNow by rememberUpdatedState(split)

    // Back to one pane: the side used last is the one that stays.
    LaunchedEffect(split) {
        // The split that opened has taken its starting width; a later one starts settled.
        enterFrom = null
        // A close the window outran (a fold mid-animation) must not carry over to the next split.
        if (!split) closing = null
        if (split) {
            wasSplit = true
            if (selected == TopLevelDestination.SETTINGS) settingsOverPanes = true
        } else if (wasSplit) {
            wasSplit = false
            // An arrangement chosen on purpose wins over the side touched last:
            // tapping ◫ in a chat bar to choose Map only is a touch on the chat.
            selected = when {
                settingsOverPanes -> TopLevelDestination.SETTINGS
                choice.arrangement == PaneArrangement.MAP_ONLY -> TopLevelDestination.MAP
                choice.arrangement == PaneArrangement.CHAT_ONLY -> TopLevelDestination.CHATS
                lastTouched == Side.MAP -> TopLevelDestination.MAP
                else -> TopLevelDestination.CHATS
            }
            settingsOverPanes = false
        }
    }

    // What the chat side has open, which the map follows while both are on screen (UX §6.11.6).
    var openConversation by remember { mutableStateOf<Following?>(null) }

    val chats = remember {
        movableContentOf { directive: PaneScaffoldDirective ->
            ChatsPane(
                paneDirective = directive,
                onChatOpenChange = { chatOpen = it },
                onWholeWindowChange = { chatsWholeWindow = it },
                backEnabled = !splitNow || lastTouched == Side.CHAT,
                searchAsked = chatsSearch,
                onSearchTaken = { chatsSearch = false },
                besideMap = splitNow,
                onOpenConversationChange = { openConversation = it },
                openTarget = chatTarget,
                onTargetOpened = { chatTarget = null },
            )
        }
    }
    val map = remember {
        movableContentOf { insetBottom: Boolean ->
            MapScreen(
                insetBottom = insetBottom,
                following = openConversation.takeIf { splitNow },
                // Either one opens on the chat side, or switches to Chats in one pane.
                onMessage = { peer -> chatTarget = ChatTarget.Direct(peer) },
                onOpenRoom = { index -> chatTarget = ChatTarget.Channel(index) },
                onBack = { selected = TopLevelDestination.CHATS },
                onOpenOfflineAreas = {
                    settingsSection = SettingsSection.OFFLINE_MAPS
                    if (splitNow) settingsOverPanes = true else selected = TopLevelDestination.SETTINGS
                },
            )
        }
    }

    // Settles an arrangement for real, once any panes have finished moving.
    val arrangeNow: (PaneArrangement) -> Unit = { arrangement ->
        shell.arrange(arrangement)
        when (arrangement) {
            PaneArrangement.CHAT_ONLY -> selected = TopLevelDestination.CHATS
            PaneArrangement.MAP_ONLY -> selected = TopLevelDestination.MAP
            PaneArrangement.CHAT_AND_MAP -> Unit
        }
    }
    val controls = WideScreenControls(
        canSplit = canSplit,
        arrangement = choice.arrangement,
        onArrange = { arrangement ->
            when {
                // Side by side, closing a side moves the panes first.
                layout is PaneLayout.SideBySide && arrangement != PaneArrangement.CHAT_AND_MAP ->
                    closing = if (arrangement == PaneArrangement.CHAT_ONLY) Side.MAP else Side.CHAT

                // Opening again from one side starts the split where that side is.
                arrangement == PaneArrangement.CHAT_AND_MAP && choice.arrangement != PaneArrangement.CHAT_AND_MAP -> {
                    enterFrom = if (choice.arrangement == PaneArrangement.CHAT_ONLY) window.width else 0f
                    arrangeNow(arrangement)
                }

                else -> arrangeNow(arrangement)
            }
        },
        onSwapSides = if (layout is PaneLayout.Stacked) null else shell::swapSides,
        onOpenSettings = if (split) {
            { settingsOverPanes = true }
        } else {
            null
        },
    )

    CompositionLocalProvider(LocalWideScreen provides controls.takeIf { canSplit }) {
        if (layout is PaneLayout.OnePane) {
            OnePane(
                navigation = layout.navigation,
                selected = selected,
                onSelect = { selected = it },
                // Reading gets the whole screen. Navigation would otherwise eat a strip of
                // it, and while typing it would sit between the composer and the keyboard.
                // The map keeps the bar: it is a place you pass through, not one you read.
                immersive = keyboardOpen ||
                    (selected == TopLevelDestination.CHATS && chatOpen) ||
                    (selected == TopLevelDestination.SETTINGS && settingsDetailOpen),
                modifier = modifier,
            ) {
                when (selected) {
                    TopLevelDestination.CHATS -> chats(foldAwarePaneDirective())
                    // No bar under the map beside a rail: it clears the gesture bar itself.
                    TopLevelDestination.MAP -> map(layout.navigation == PaneNavigation.RAIL)
                    TopLevelDestination.SETTINGS ->
                        SettingsScreen(
                            openSection = settingsSection,
                            onSectionOpened = { settingsSection = null },
                            onImmersiveChange = { settingsDetailOpen = it },
                            onMessage = { peer -> chatTarget = ChatTarget.Direct(peer) },
                        )
                }
            }
        } else {
            Box(modifier.fillMaxSize()) {
                // Settings covers the panes rather than replacing them, so closing
                // it finds the conversation and the map as they were.
                val panes = Modifier.outOfReach(settingsOverPanes)
                when (layout) {
                    is PaneLayout.SideBySide -> SidePanes(
                        layout = layout,
                        window = window,
                        // Scanning an invite gets the whole window.
                        chatWholeWindow = chatsWholeWindow,
                        chatMin = chatMin,
                        chat = { chats(chatSideDirective(layout.listBesideConversation)) },
                        map = { map(true) },
                        onSettle = { settle ->
                            when (settle) {
                                is DividerSettle.Share -> shell.setShare(window.isUpright, settle.share)
                                DividerSettle.CloseChat -> controls.onArrange(PaneArrangement.MAP_ONLY)
                                DividerSettle.CloseMap -> controls.onArrange(PaneArrangement.CHAT_ONLY)
                            }
                        },
                        onReset = shell::resetDivider,
                        onSwapSides = shell::swapSides,
                        onTouched = { lastTouched = it },
                        modifier = panes,
                        enterFrom = enterFrom,
                        closing = closing,
                        onClosed = {
                            val closed = closing
                            closing = null
                            arrangeNow(if (closed == Side.MAP) PaneArrangement.CHAT_ONLY else PaneArrangement.MAP_ONLY)
                        },
                    )

                    is PaneLayout.Stacked -> StackedPanes(
                        layout = layout,
                        window = window,
                        // Half-folded, typing gets the whole window: the keyboard
                        // would leave the bottom half no room for the conversation.
                        chatWholeWindow = chatsWholeWindow || keyboardOpen,
                        chat = { chats(chatSideDirective(listBesideConversation = false)) },
                        map = { map(false) },
                        onTouched = { lastTouched = it },
                        modifier = panes,
                    )

                    is PaneLayout.OnePane -> Unit
                }
                if (settingsOverPanes) {
                    BackHandler { settingsOverPanes = false }
                    SettingsScreen(
                        modifier = Modifier.fillMaxSize().imePadding(),
                        openSection = settingsSection,
                        onSectionOpened = { settingsSection = null },
                        onImmersiveChange = { settingsDetailOpen = it },
                        onMessage = { peer ->
                            chatTarget = ChatTarget.Direct(peer)
                            settingsOverPanes = false
                        },
                        onClose = { settingsOverPanes = false },
                    )
                }
            }
        }
    }
}

/** The phone app: one place at a time, with the bottom bar or, on a wide but short window, the rail. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnePane(
    navigation: PaneNavigation,
    selected: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
    immersive: Boolean,
    modifier: Modifier = Modifier,
    screen: @Composable () -> Unit,
) {
    val itemColors = NavigationItemColors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        selectedTextColor = MaterialTheme.colorScheme.primary,
        selectedIndicatorColor = FirepitTheme.colors.bubbleOut,
        unselectedIconColor = FirepitTheme.colors.textSecondary,
        unselectedTextColor = FirepitTheme.colors.textSecondary,
        disabledIconColor = FirepitTheme.colors.stale,
        disabledTextColor = FirepitTheme.colors.stale,
    )

    if (navigation == PaneNavigation.RAIL) {
        Row(modifier.imePadding()) {
            if (!immersive) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    TopLevelDestination.entries.forEach { destination ->
                        NavigationRailItem(
                            selected = selected == destination,
                            onClick = { onSelect(destination) },
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
                                onClick = { onSelect(destination) },
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
