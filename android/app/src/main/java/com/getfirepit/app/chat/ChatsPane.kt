package com.getfirepit.app.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
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
import com.getfirepit.core.designsystem.component.QuotedMessage
import com.getfirepit.core.designsystem.component.RoomAvatar
import com.getfirepit.core.designsystem.component.RoomIcon
import com.getfirepit.core.designsystem.component.SystemChip
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.designsystem.theme.identityColorFor
import com.getfirepit.core.model.ChannelRole
import com.getfirepit.core.model.ChatMessage
import com.getfirepit.core.model.MessageStatus
import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.protocol.MeshConstants
import kotlinx.coroutines.launch

/**
 * Chats as a list-detail pair. On a phone or folded cover screen the detail
 * replaces the list; on a tablet or unfolded book posture they sit side by
 * side, split at the hinge.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalFoundationApi::class)
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
                    unread = state.unread,
                    muted = state.muted,
                    onSelect = { index ->
                        viewModel.select(index)
                        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, index) }
                    },
                    onToggleMute = viewModel::toggleMute,
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
    unread: Map<Int, Int>,
    muted: Set<Int>,
    onSelect: (Int) -> Unit,
    onToggleMute: (Int) -> Unit,
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
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs),
                            ) {
                                if (channel.index in muted) {
                                    Text(
                                        text = "🔕",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                // A muted room still counts unread; it just does
                                // not interrupt.
                                unread[channel.index]?.takeIf { it > 0 }?.let { count ->
                                    Badge { Text(if (count > 99) "99+" else "$count") }
                                }
                            }
                        },
                        modifier = Modifier
                            .combinedClickable(
                                onClick = { onSelect(channel.index) },
                                onLongClick = { onToggleMute(channel.index) },
                                onLongClickLabel = if (channel.index in muted) "Unmute" else "Mute",
                            )
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
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable(channel.index) { mutableStateOf(false) }

    LaunchedEffect(state.messages.size, state.isSearching) {
        // Jumping to the newest message would fight the reader while they scan
        // results, so autoscroll pauses during a search.
        if (state.messages.isNotEmpty() && !state.isSearching) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::updateQuery,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search this room") },
                            singleLine = true,
                        )
                    } else {
                        Text(channel.displayName)
                    }
                },
                navigationIcon = {
                    // Only when the list is hidden behind this pane.
                    onBack?.let { back -> BackButton(onClick = back) }
                },
                actions = {
                    TextButton(
                        onClick = {
                            searching = !searching
                            if (!searching) viewModel.updateQuery("")
                        },
                    ) { Text(if (searching) "Done" else "Search") }
                    if (!searching) {
                        onShowMembers?.let { members ->
                            TextButton(onClick = members) { Text("Members") }
                        }
                        onInvite?.let { invite ->
                            TextButton(onClick = invite) { Text("Invite") }
                        }
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
            val visible = state.visibleMessages
            val items = remember(visible) { buildChatItems(visible) }

            if (state.isSearching && visible.isEmpty()) {
                EmptyState("No messages match \"${state.query.trim()}\".")
                return@Column
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = FirepitSpacing.screenMargin),
                contentPadding = PaddingValues(vertical = FirepitSpacing.s),
            ) {
                items(items, key = { item -> item.key() }) { item ->
                    when (item) {
                        is ChatItem.Day -> SystemChip(
                            text = item.label,
                            modifier = Modifier.padding(vertical = FirepitSpacing.m),
                        )

                        is ChatItem.Bubble -> {
                            val message = item.message
                            val parent = state.repliedTo(message)
                            MessageBubble(
                                text = message.text,
                                time = formatTime(message.rxTime ?: message.sentAt),
                                isOutgoing = message.isOutgoing,
                                senderName = state.nodes[message.fromNodeNum]?.displayName,
                                senderNodeNum = message.fromNodeNum,
                                status = message.status.takeIf { message.isOutgoing },
                                footnote = message.hopsAway?.let { hops ->
                                    if (hops == 0) "direct" else "$hops hop${if (hops == 1) "" else "s"}"
                                },
                                quoted = parent?.let {
                                    QuotedMessage(
                                        senderName = state.nodes[it.fromNodeNum]?.displayName
                                            ?: MeshConstants.formatNodeId(it.fromNodeNum),
                                        senderNodeNum = it.fromNodeNum,
                                        text = it.text,
                                    )
                                },
                                onQuoteClick = parent?.let {
                                    {
                                        val index = items.indexOfFirst { row ->
                                            row is ChatItem.Bubble && row.message.id == it.id
                                        }
                                        if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
                                    }
                                },
                                isFirstInGroup = item.isFirstInGroup,
                                isLastInGroup = item.isLastInGroup,
                                // Tight inside a block, open between speakers.
                                modifier = Modifier
                                    .padding(top = if (item.isFirstInGroup) FirepitSpacing.s else 2.dp)
                                    .combinedClickable(
                                        onClick = { viewModel.inspect(message) },
                                        onLongClick = { viewModel.startReply(message) },
                                        onLongClickLabel = "Reply",
                                    ),
                            )
                        }
                    }
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

    state.inspecting?.let { message ->
        MessageInfoSheet(
            message = message,
            senderName = state.nodes[message.fromNodeNum]?.displayName,
            onDismiss = { viewModel.inspect(null) },
        )
    }
}

/** Composer banner for the message being replied to, matching the in-bubble quote. */
@Composable
private fun ReplyBanner(state: ChatsUiState, parent: ChatMessage, onCancel: () -> Unit) {
    val accent = identityColorFor(parent.fromNodeNum, FirepitTheme.colors.isDark)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FirepitSpacing.s, vertical = FirepitSpacing.xs)
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = if (FirepitTheme.colors.isDark) 0.18f else 0.12f))
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accent),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = FirepitSpacing.s, vertical = FirepitSpacing.xs),
        ) {
            Text(
                text = state.nodes[parent.fromNodeNum]?.displayName
                    ?: MeshConstants.formatNodeId(parent.fromNodeNum),
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = parent.text,
                style = MaterialTheme.typography.bodySmall,
                color = FirepitTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel) {
            Text("✕", style = MaterialTheme.typography.bodyLarge, color = FirepitTheme.colors.textSecondary)
        }
    }
}

@Composable
private fun Composer(state: ChatsUiState, viewModel: ChatsViewModel) {
    Column(Modifier.fillMaxWidth()) {
        state.replyingTo?.let { parent ->
            ReplyBanner(state = state, parent = parent, onCancel = viewModel::cancelReply)
        }

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
                    // Only appears near a limit, so the composer stays quiet.
                    when {
                        state.remainingBytes <= 0 -> Text(
                            text = "Full — 200 bytes is the radio's limit",
                            color = FirepitTheme.colors.danger,
                        )

                        state.losesSignature -> Text(
                            text = "Over ${MeshConstants.SIGNED_BROADCAST_TEXT_BUDGET} bytes: sent unsigned",
                            color = FirepitTheme.colors.warn,
                        )

                        state.draftBytes >= 150 -> Text(
                            text = "${state.remainingBytes} bytes left",
                            color = FirepitTheme.colors.textSecondary,
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
}

/**
 * Delivery detail for one message.
 *
 * Only shows what the radio actually reported: absent values are omitted rather
 * than rendered as zero, because "0 dB SNR" and "not measured" are different
 * claims.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageInfoSheet(message: ChatMessage, senderName: String?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FirepitSpacing.screenMargin)
                .padding(bottom = FirepitSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
        ) {
            Text(
                text = if (message.isOutgoing) "Sent message" else "From ${senderName ?: "Unknown"}",
                style = MaterialTheme.typography.titleMedium,
            )

            InfoRow("Sent", formatTime(message.sentAt))
            message.rxTime?.let { InfoRow("Radio clock", formatTime(it)) }

            if (message.isOutgoing) {
                InfoRow("Status", message.status.label())
                message.failureReason?.let { InfoRow("Reason", it) }
            }

            message.hopsAway?.let { hops ->
                InfoRow("Hops", if (hops == 0) "Direct, no relay" else "$hops")
            }
            message.rxSnr?.let { InfoRow("Signal to noise", "%.2f dB".format(it)) }
            message.rxRssi?.let { InfoRow("Signal strength", "$it dBm") }
            if (message.signed) InfoRow("Signature", "Verified")

            Text(
                text = "Delivery is only ever confirmed as far as the first node that heard it. " +
                    "The mesh cannot tell you who read it.",
                style = MaterialTheme.typography.bodySmall,
                color = FirepitTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = FirepitTheme.colors.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Wording that never implies more than the mesh actually told us. */
private fun MessageStatus.label(): String = when (this) {
    MessageStatus.QUEUED -> "Waiting for the radio"
    MessageStatus.SENT_TO_NODE -> "Handed to your node"
    MessageStatus.UNKNOWN -> "Handed to your node, no confirmation"
    MessageStatus.UNHEARD -> "Nobody repeated it"
    MessageStatus.FAILED -> "Failed"
    MessageStatus.REACHED_MESH -> "Heard by at least one node"
    MessageStatus.DELIVERED -> "Acknowledged by the recipient"
    MessageStatus.RECEIVED -> "Received"
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
