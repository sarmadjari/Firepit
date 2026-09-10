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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.res.painterResource
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
import com.getfirepit.core.designsystem.component.FirepitChip
import com.getfirepit.core.designsystem.component.FirepitIcons
import com.getfirepit.core.designsystem.component.FirepitTopBar
import com.getfirepit.core.designsystem.component.IdentityAvatar
import com.getfirepit.core.designsystem.component.LiveRing
import com.getfirepit.core.designsystem.component.MessageBubble
import com.getfirepit.core.designsystem.component.QuotedMessage
import com.getfirepit.core.designsystem.component.RoomAvatar
import com.getfirepit.core.designsystem.component.RoomIcon
import com.getfirepit.core.designsystem.component.StatusTick
import com.getfirepit.core.designsystem.component.SystemChip
import com.getfirepit.core.designsystem.component.UnreadBadge
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.designsystem.theme.identityColorFor
import com.getfirepit.core.model.ChannelRole
import com.getfirepit.core.model.ChatMessage
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.model.MessageStatus
import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.protocol.ChannelLoad
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
                muted = current.channelIndex in state.muted,
                onInvite = { overlay = RoomsOverlay.Invite(current.roomId, current.roomName) },
                onToggleMute = { viewModel.toggleMute(current.channelIndex) },
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
                    myNode = state.myNode,
                    myNodeNum = state.myNode?.nodeNum,
                    latest = state.latest,
                    directLatest = state.directLatest,
                    directPeer = state.directPeer,
                    nodes = state.nodes,
                    onSelect = { index ->
                        viewModel.select(index)
                        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, index) }
                    },
                    onToggleMute = viewModel::toggleMute,
                    onOpenDirect = { peer ->
                        viewModel.openDirect(peer)
                        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, peer) }
                    },
                    onNewRoom = { showCreateDialog = true },
                    onJoinRoom = {
                        // A leftover "Created Camp" notice would otherwise close
                        // the scanner the moment it opens.
                        roomsViewModel.clearMessages()
                        overlay = RoomsOverlay.Join
                    },
                    onDismissMessage = roomsViewModel::clearMessages,
                    onSearch = {
                        // Search reads the open conversation, so it needs one open.
                        state.selected?.let { index ->
                            scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, index) }
                        }
                    },
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val channel = state.selectedChannel
                val peer = state.directPeer
                val back = if (chatCoversList) {
                    { scope.launch { navigator.navigateBack() }; Unit }
                } else {
                    null
                }
                when {
                    peer != null -> DirectChat(
                        state = state,
                        peer = peer,
                        viewModel = viewModel,
                        onBack = back,
                    )

                    channel == null -> EmptyDetail()
                    else -> ChannelChat(
                        state = state,
                        channel = channel,
                        viewModel = viewModel,
                        onBack = back,
                        onInvite = if (channel.isRoom) {
                            { overlay = RoomsOverlay.Invite(channel.id, channel.displayName) }
                        } else {
                            null
                        },
                        onShowMembers = if (channel.isRoom) {
                            { overlay = RoomsOverlay.Members(channel.id, channel.displayName, channel.index) }
                        } else {
                            null
                        },
                        memberCount = if (channel.isRoom) {
                            roomsViewModel.members(channel.id)
                                .collectAsStateWithLifecycle(emptyList()).value
                                .size
                                .takeIf { it > 0 }
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
    data class Members(val roomId: Int, val roomName: String, val channelIndex: Int) : RoomsOverlay
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
    myNode: MeshNode?,
    myNodeNum: Int?,
    latest: Map<Int, ChatMessage>,
    directLatest: List<ChatMessage>,
    directPeer: Int?,
    nodes: Map<Int, MeshNode>,
    onSelect: (Int) -> Unit,
    onOpenDirect: (Int) -> Unit,
    onToggleMute: (Int) -> Unit,
    onNewRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    onSearch: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(ChannelFilter.ALL) }

    Scaffold(
        topBar = {
            FirepitTopBar(
                title = myNode?.displayName ?: "Firepit",
                leading = {
                    myNode?.let { node ->
                        IdentityAvatar(
                            nodeNum = node.nodeNum,
                            tag = node.shortName,
                            name = node.displayName,
                        )
                    }
                },
                subtitle = { NodeStatusLine(connected = connected, myNode = myNode) },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(painterResource(FirepitIcons.Search), contentDescription = "Search messages")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(FirepitIcons.More), contentDescription = "More")
                    }
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
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (connected && !roomsFull && !roomsBusy) onNewRoom() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(painterResource(FirepitIcons.Add), contentDescription = "New room")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(
                modifier = Modifier.padding(
                    horizontal = FirepitSpacing.screenMargin,
                    vertical = FirepitSpacing.m,
                ),
                horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
            ) {
                ChannelFilter.entries.forEach { option ->
                    FirepitChip(
                        label = option.label,
                        selected = filter == option,
                        onClick = { filter = option },
                    )
                }
            }

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

            val visible = channels
                .filter { it.role != ChannelRole.DISABLED }
                .filter { filter != ChannelFilter.ROOMS || it.isRoom }
            val showRooms = filter != ChannelFilter.DIRECT
            val showDirect = filter != ChannelFilter.ROOMS

            if ((!showRooms || visible.isEmpty()) && (!showDirect || directLatest.isEmpty())) {
                EmptyState(
                    when {
                        filter == ChannelFilter.DIRECT ->
                            "No direct messages yet. Open a node from Settings → Radio to start one."
                        channels.none { it.role != ChannelRole.DISABLED } ->
                            "No channels yet. Connect your node in Settings."
                        else -> "No rooms yet. Create one with the + button."
                    },
                )
                return@Column
            }

            LazyColumn {
                if (showRooms) {
                    items(visible, key = { "room-${it.index}" }) { channel ->
                        ChannelRow(
                            channel = channel,
                            latest = latest[channel.index],
                            senderName = latest[channel.index]?.let { message ->
                                nodes[message.fromNodeNum]?.displayName
                            },
                            unread = unread[channel.index] ?: 0,
                            muted = channel.index in muted,
                            selected = channel.index == selected,
                            onSelect = { onSelect(channel.index) },
                            onToggleMute = { onToggleMute(channel.index) },
                        )
                        HorizontalDivider(
                            color = FirepitTheme.colors.outline,
                            modifier = Modifier.padding(start = 76.dp),
                        )
                    }
                }

                if (showDirect) {
                    items(directLatest, key = { "direct-${it.peerOf(myNodeNum)}" }) { message ->
                        val peer = message.peerOf(myNodeNum)
                        DirectRow(
                            peer = peer,
                            node = nodes[peer],
                            latest = message,
                            selected = peer == directPeer,
                            onSelect = { onOpenDirect(peer) },
                        )
                        HorizontalDivider(
                            color = FirepitTheme.colors.outline,
                            modifier = Modifier.padding(start = 76.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Whoever is not us. Outgoing names the recipient, incoming names the sender. */
private fun ChatMessage.peerOf(myNodeNum: Int?): Int =
    if (isOutgoing || fromNodeNum == myNodeNum) toNodeNum else fromNodeNum

@Composable
private fun DirectRow(
    peer: Int,
    node: MeshNode?,
    latest: ChatMessage,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val name = node?.displayName ?: MeshConstants.formatNodeId(peer)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) {
                    FirepitTheme.colors.bubbleOut.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = FirepitSpacing.screenMargin, vertical = FirepitSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.m),
    ) {
        IdentityAvatar(nodeNum = peer, tag = node?.shortName, name = name)

        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs),
            ) {
                if (latest.isOutgoing) StatusTick(latest.status)
                Text(
                    text = latest.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FirepitTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            text = MessageTimestamp.listFormat(latest.sentAt),
            style = MaterialTheme.typography.bodySmall,
            color = FirepitTheme.colors.textSecondary,
        )
    }
}

/** Rooms, and one-to-one conversations. */
private enum class ChannelFilter(val label: String) {
    ALL("All"),
    ROOMS("Rooms"),
    DIRECT("Direct"),
}

/**
 * Which node we are speaking through, and how much charge it has left.
 *
 * The radio is a separate object that can be left behind or run flat, so its
 * name and battery belong on the first screen rather than buried in settings.
 */
@Composable
private fun NodeStatusLine(connected: Boolean, myNode: MeshNode?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs),
    ) {
        LiveRing(size = 8.dp, live = connected)
        Text(
            text = when {
                !connected -> "Not connected — open Settings"
                myNode == null -> "Connected"
                // The name above is the person; this says which radio is
                // carrying them today.
                else -> buildString {
                    myNode.hwModel?.takeIf { it.isNotBlank() }?.let { append(prettyHardware(it)) }
                    myNode.batteryLevel?.let { level ->
                        if (isNotEmpty()) append(" · ")
                        append(if (level > 100) "powered" else "$level%")
                    }
                    if (isEmpty()) append("Connected")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (connected) FirepitTheme.colors.textSecondary else FirepitTheme.colors.stale,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** WISMESH_TAG reads as shouting; the radio's own name does not. */
private fun prettyHardware(model: String): String = model
    .split('_')
    .filter { it.isNotBlank() }
    .joinToString(" ") { part -> part.lowercase().replaceFirstChar(Char::uppercase) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: RoomChannel,
    latest: ChatMessage?,
    senderName: String?,
    unread: Int,
    muted: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggleMute: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // A wash, not a slab: on a phone the row is left behind the moment
            // it is tapped, so a strong highlight only flashes and distracts.
            .background(
                if (selected) {
                    FirepitTheme.colors.bubbleOut.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onToggleMute,
                onLongClickLabel = if (muted) "Unmute" else "Mute",
            )
            .padding(horizontal = FirepitSpacing.screenMargin, vertical = FirepitSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.m),
    ) {
        RoomAvatar(RoomIcon.forRoomId(channel.id))

        Column(Modifier.weight(1f)) {
            Text(
                text = channel.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs),
            ) {
                if (latest?.isOutgoing == true) StatusTick(latest.status)
                Text(
                    text = when {
                        latest == null && channel.isRoom -> "Room · slot ${channel.index}"
                        latest == null -> "Primary channel"
                        latest.isOutgoing -> latest.text
                        senderName != null -> "$senderName: ${latest.text}"
                        else -> latest.text
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = FirepitTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = latest?.let { MessageTimestamp.listFormat(it.sentAt) }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (unread > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    FirepitTheme.colors.textSecondary
                },
            )
            Spacer(Modifier.height(FirepitSpacing.xs))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs),
            ) {
                if (muted) {
                    Icon(
                        painter = painterResource(FirepitIcons.Mute),
                        contentDescription = "Muted",
                        tint = FirepitTheme.colors.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                // A muted room still counts unread; it just does not interrupt.
                UnreadBadge(unread)
            }
        }
    }
}

/**
 * One-to-one conversation.
 *
 * Shares the bubbles and composer with a room so a private word looks like a
 * word, not a different product; the header names the person instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectChat(
    state: ChatsUiState,
    peer: Int,
    viewModel: ChatsViewModel,
    onBack: (() -> Unit)?,
) {
    val listState = rememberLazyListState()
    val node = state.nodes[peer]
    val name = node?.displayName ?: MeshConstants.formatNodeId(peer)

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.m),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IdentityAvatar(
                            nodeNum = peer,
                            tag = node?.shortName,
                            name = name,
                            size = 40.dp,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Direct message",
                                style = MaterialTheme.typography.bodySmall,
                                color = FirepitTheme.colors.textSecondary,
                            )
                        }
                    }
                },
                navigationIcon = { onBack?.let { back -> BackButton(onClick = back) } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            val items = remember(state.messages) { buildChatItems(state.messages) }

            if (state.messages.isEmpty()) {
                EmptyState("No messages with $name yet.")
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = FirepitSpacing.screenMargin),
                ) {
                    items(items, key = { it.key() }) { item ->
                        when (item) {
                            is ChatItem.Day -> SystemChip(
                                text = item.label,
                                modifier = Modifier.padding(vertical = FirepitSpacing.m),
                            )
                            is ChatItem.Bubble -> {
                                val message = item.message
                                MessageBubble(
                                    text = message.text,
                                    time = MessageTimestamp.bubbleFormat(message.rxTime ?: message.sentAt),
                                    isOutgoing = message.isOutgoing,
                                    senderName = null,
                                    senderNodeNum = message.fromNodeNum,
                                    status = message.status.takeIf { message.isOutgoing },
                                    isFirstInGroup = item.isFirstInGroup,
                                    isLastInGroup = item.isLastInGroup,
                                    modifier = Modifier.padding(
                                        top = if (item.isFirstInGroup) FirepitSpacing.s else 2.dp,
                                    ),
                                )
                            }
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
    memberCount: Int?,
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.m),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RoomAvatar(RoomIcon.forRoomId(channel.id), size = 40.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = channel.displayName,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs),
                                ) {
                                    memberCount?.let { count ->
                                        Text(
                                            text = if (count == 1) "1 member · " else "$count members · ",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = FirepitTheme.colors.textSecondary,
                                        )
                                    }
                                    LiveRing(size = 8.dp, live = state.connected)
                                    Text(
                                        text = if (state.connected) "connected" else "not connected",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = FirepitTheme.colors.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    // Only when the list is hidden behind this pane.
                    onBack?.let { back -> BackButton(onClick = back) }
                },
                actions = {
                    IconButton(
                        onClick = {
                            searching = !searching
                            if (!searching) viewModel.updateQuery("")
                        },
                    ) {
                        Icon(
                            painter = painterResource(
                                if (searching) FirepitIcons.Close else FirepitIcons.Search,
                            ),
                            contentDescription = if (searching) "Close search" else "Search messages",
                        )
                    }
                    // Room actions live in Room info, so the bar stays narrow
                    // enough for the room's name and status to fit.
                    if (!searching && onShowMembers != null) {
                        IconButton(onClick = onShowMembers) {
                            Icon(
                                painter = painterResource(FirepitIcons.More),
                                contentDescription = "Room info",
                            )
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
                                time = MessageTimestamp.bubbleFormat(message.rxTime ?: message.sentAt),
                                isOutgoing = message.isOutgoing,
                                senderName = state.nodes[message.fromNodeNum]?.displayName,
                                senderNodeNum = message.fromNodeNum,
                                status = message.status.takeIf { message.isOutgoing },
                                // Only when it is worth knowing: a relayed
                                // message may be slow or stale, a direct one is
                                // unremarkable and said so on every bubble.
                                footnote = message.hopsAway
                                    ?.takeIf { it > 0 }
                                    ?.let { hops -> "$hops hop${if (hops == 1) "" else "s"}" },
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

/**
 * Warns when the air is too busy to speak into.
 *
 * Shown above the composer rather than after sending, because the useful moment
 * is before you rely on a message arriving, not once it has already stalled.
 */
@Composable
private fun CongestionNotice(load: ChannelLoad?) {
    if (load == null || load == ChannelLoad.CLEAR) return
    val congested = load == ChannelLoad.CONGESTED
    Text(
        text = if (congested) {
            "Channel is congested — messages may not get through"
        } else {
            "Channel is busy — messages may take longer"
        },
        style = MaterialTheme.typography.labelMedium,
        color = if (congested) FirepitTheme.colors.stale else FirepitTheme.colors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FirepitSpacing.m, vertical = FirepitSpacing.xs),
    )
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
            Icon(
                painter = painterResource(FirepitIcons.Close),
                contentDescription = "Cancel reply",
                tint = FirepitTheme.colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun Composer(state: ChatsUiState, viewModel: ChatsViewModel) {
    Column(Modifier.fillMaxWidth()) {
        CongestionNotice(state.channelLoad)

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
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = FirepitTheme.colors.surface2,
                    unfocusedContainerColor = FirepitTheme.colors.surface2,
                    focusedBorderColor = FirepitTheme.colors.outline,
                    unfocusedBorderColor = FirepitTheme.colors.outline,
                ),
            )
            FilledIconButton(
                onClick = viewModel::send,
                enabled = state.canSend,
                shape = CircleShape,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    // Dimmed rather than greyed: the send button should still
                    // look like itself while the composer is empty.
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    painter = painterResource(FirepitIcons.Send),
                    contentDescription = "Send",
                    modifier = Modifier.size(20.dp),
                )
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

private fun formatTime(epochMillis: Long): String = MessageTimestamp.format(epochMillis)
