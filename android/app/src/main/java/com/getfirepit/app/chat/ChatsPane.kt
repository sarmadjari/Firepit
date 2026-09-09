package com.getfirepit.app.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.app.rooms.CreateRoomDialog
import com.getfirepit.app.rooms.InviteScreen
import com.getfirepit.app.rooms.JoinRoomScreen
import com.getfirepit.app.rooms.RoomMembersScreen
import com.getfirepit.app.rooms.RoomsViewModel
import com.getfirepit.core.designsystem.adaptive.foldAwarePaneDirective
import com.getfirepit.core.designsystem.component.BackButton
import com.getfirepit.core.designsystem.component.MessageBubble
import com.getfirepit.core.designsystem.component.RoomAvatar
import com.getfirepit.core.designsystem.component.RoomIcon
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.model.ChannelRole
import com.getfirepit.core.model.RoomChannel
import kotlinx.coroutines.launch

/**
 * Chats as a list-detail pair. On a phone or folded cover screen the detail
 * replaces the list; on a tablet or unfolded book posture they sit side by
 * side, split at the hinge.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ChatsPane(
    modifier: Modifier = Modifier,
    onChatOpenChange: (Boolean) -> Unit = {},
    viewModel: ChatsViewModel = hiltViewModel(),
    roomsViewModel: RoomsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val roomsState by roomsViewModel.uiState.collectAsStateWithLifecycle()
    val navigator = rememberListDetailPaneScaffoldNavigator<Int>(
        scaffoldDirective = foldAwarePaneDirective(),
    )
    val scope = rememberCoroutineScope()

    var overlay by remember { mutableStateOf<RoomsOverlay?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    // True only when the detail covers the list, i.e. single-pane. Side by side
    // the list is still reachable, so navigation should stay.
    val chatCoversList = navigator.canNavigateBack()
    // Both the QR screens want the whole display, same as an open chat does.
    LaunchedEffect(chatCoversList, overlay) { onChatOpenChange(chatCoversList || overlay != null) }

    overlay?.let { current ->
        val dismiss = {
            overlay = null
            roomsViewModel.clearMessages()
        }
        BackHandler(onBack = dismiss)
        when (current) {
            is RoomsOverlay.Invite -> InviteScreen(
                roomId = current.roomId,
                roomName = current.roomName,
                onBack = dismiss,
                modifier = modifier,
                viewModel = roomsViewModel,
            )

            RoomsOverlay.Join -> JoinRoomScreen(
                onBack = dismiss,
                modifier = modifier,
                viewModel = roomsViewModel,
            )

            is RoomsOverlay.Members -> RoomMembersScreen(
                roomId = current.roomId,
                roomName = current.roomName,
                onBack = dismiss,
                modifier = modifier,
                viewModel = roomsViewModel,
            )
        }
        return
    }

    if (showCreateDialog) {
        CreateRoomDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                showCreateDialog = false
                roomsViewModel.createRoom(name)
            },
        )
    }

    BackHandler(enabled = chatCoversList) {
        scope.launch { navigator.navigateBack() }
    }

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        modifier = modifier,
        listPane = {
            AnimatedPane {
                ChannelList(
                    channels = state.channels,
                    connected = state.connected,
                    selected = state.selected,
                    roomsFull = roomsState.isFull,
                    roomsBusy = roomsState.busy,
                    roomsMessage = roomsState.error ?: roomsState.joinedRoomName,
                    onSelect = { index ->
                        viewModel.select(index)
                        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, index) }
                    },
                    onNewRoom = { showCreateDialog = true },
                    onJoinRoom = {
                        // A leftover "Created Camp" notice would otherwise close
                        // the scanner the moment it opens.
                        roomsViewModel.clearMessages()
                        overlay = RoomsOverlay.Join
                    },
                    onDismissMessage = roomsViewModel::clearMessages,
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val channel = state.selectedChannel
                if (channel == null) {
                    EmptyDetail()
                } else {
                    ChannelChat(
                        state = state,
                        channel = channel,
                        viewModel = viewModel,
                        onBack = if (chatCoversList) {
                            { scope.launch { navigator.navigateBack() } }
                        } else {
                            null
                        },
                        onInvite = if (channel.isRoom) {
                            { overlay = RoomsOverlay.Invite(channel.id, channel.displayName) }
                        } else {
                            null
                        },
                        onShowMembers = if (channel.isRoom) {
                            { overlay = RoomsOverlay.Members(channel.id, channel.displayName) }
                        } else {
                            null
                        },
                    )
                }
            }
        },
    )
}

/** Screens that take over the whole display rather than sitting in a pane. */
private sealed interface RoomsOverlay {
    data class Invite(val roomId: Int, val roomName: String) : RoomsOverlay
    data class Members(val roomId: Int, val roomName: String) : RoomsOverlay
    data object Join : RoomsOverlay
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelList(
    channels: List<RoomChannel>,
    connected: Boolean,
    selected: Int?,
    roomsFull: Boolean,
    roomsBusy: Boolean,
    roomsMessage: String?,
    onSelect: (Int) -> Unit,
    onNewRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Firepit") }) },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { menuOpen = true }) { Text("+") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (roomsFull) "New room — no free slots" else "New room") },
                        // The radio has eight slots. Leaving a room frees one.
                        enabled = connected && !roomsFull && !roomsBusy,
                        onClick = {
                            menuOpen = false
                            onNewRoom()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (roomsFull) "Scan invite — no free slots" else "Scan invite") },
                        enabled = connected && !roomsFull && !roomsBusy,
                        onClick = {
                            menuOpen = false
                            onJoinRoom()
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Text(
                text = if (connected) "Connected to your node" else "Not connected — open Settings",
                style = MaterialTheme.typography.bodyMedium,
                color = if (connected) FirepitTheme.colors.live else FirepitTheme.colors.stale,
                modifier = Modifier.padding(horizontal = FirepitSpacing.screenMargin),
            )

            roomsMessage?.let { message ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FirepitSpacing.screenMargin, vertical = FirepitSpacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissMessage) { Text("Dismiss") }
                }
            }

            if (channels.isEmpty()) {
                EmptyState("No channels yet. Connect your node in Settings.")
                return@Column
            }

            LazyColumn {
                items(channels.filter { it.role != ChannelRole.DISABLED }, key = { it.index }) { channel ->
                    ListItem(
                        headlineContent = { Text(channel.displayName) },
                        supportingContent = {
                            Text(if (channel.isRoom) "Room · slot ${channel.index}" else "Primary channel")
                        },
                        leadingContent = { RoomAvatar(RoomIcon.forRoomId(channel.id)) },
                        modifier = Modifier
                            .clickable { onSelect(channel.index) }
                            .then(
                                if (channel.index == selected) {
                                    Modifier.fillMaxWidth()
                                } else {
                                    Modifier
                                },
                            ),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelChat(
    state: ChatsUiState,
    channel: RoomChannel,
    viewModel: ChatsViewModel,
    onBack: (() -> Unit)?,
    onInvite: (() -> Unit)?,
    onShowMembers: (() -> Unit)?,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(channel.displayName) },
                navigationIcon = {
                    // Only when the list is hidden behind this pane.
                    onBack?.let { back -> BackButton(onClick = back) }
                },
                actions = {
                    onShowMembers?.let { members ->
                        TextButton(onClick = members) { Text("Members") }
                    }
                    onInvite?.let { invite ->
                        TextButton(onClick = invite) { Text("Invite") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = FirepitSpacing.screenMargin),
                verticalArrangement = Arrangement.spacedBy(FirepitSpacing.xs),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(
                        text = message.text,
                        time = formatTime(message.sentAt),
                        isOutgoing = message.isOutgoing,
                        senderName = state.nodes[message.fromNodeNum]?.displayName,
                        senderNodeNum = message.fromNodeNum,
                        status = message.status.takeIf { message.isOutgoing },
                        footnote = message.hopsAway?.let { hops ->
                            if (hops == 0) "direct" else "$hops hop${if (hops == 1) "" else "s"}"
                        },
                    )
                }
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    color = FirepitTheme.colors.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = FirepitSpacing.screenMargin),
                )
            }

            Composer(state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun Composer(state: ChatsUiState, viewModel: ChatsViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(FirepitSpacing.s),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
    ) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = viewModel::updateDraft,
            modifier = Modifier
                .weight(1f)
                .heightIn(max = 140.dp),
            placeholder = { Text("Message") },
            supportingText = {
                // Only appears near the limit, so the composer stays quiet.
                if (state.draftBytes >= 150) {
                    Text(
                        text = "${state.remainingBytes} bytes left",
                        color = if (state.remainingBytes < 0) {
                            FirepitTheme.colors.danger
                        } else {
                            FirepitTheme.colors.textSecondary
                        },
                    )
                }
            },
            maxLines = 4,
        )
        FilledIconButton(onClick = viewModel::send, enabled = state.canSend) {
            Text("↑", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun EmptyDetail() = EmptyState("Pick a channel to start reading.")

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = FirepitTheme.colors.textSecondary,
            modifier = Modifier.padding(FirepitSpacing.xl),
        )
    }
}

private fun formatTime(epochMillis: Long): String {
    val time = java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalTime()
    return "%02d:%02d".format(time.hour, time.minute)
}
