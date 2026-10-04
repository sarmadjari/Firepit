package com.getfirepit.app.chat

import android.content.ClipData
import android.view.KeyCharacterMap
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
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
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldPredictiveBackHandler
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.app.location.SharingChip
import com.getfirepit.app.location.SharingViewModel
import com.getfirepit.app.map.Following
import com.getfirepit.app.ui.ChatTarget
import com.getfirepit.app.rooms.CreateRoomDialog
import com.getfirepit.app.rooms.MakeRadioPrivateDialog
import com.getfirepit.app.rooms.InviteScreen
import com.getfirepit.app.rooms.JoinRoomScreen
import com.getfirepit.app.rooms.RoomMembersScreen
import com.getfirepit.app.rooms.RoomsViewModel
import com.getfirepit.core.designsystem.adaptive.WideScreenActions
import com.getfirepit.core.designsystem.adaptive.foldAwarePaneDirective
import com.getfirepit.core.designsystem.adaptive.onSecondaryClick
import com.getfirepit.core.designsystem.adaptive.paneSheetMaxWidth
import com.getfirepit.core.designsystem.adaptive.withinPane
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
import com.getfirepit.core.designsystem.theme.SheetShape
import com.getfirepit.core.designsystem.theme.identityColorFor
import com.getfirepit.core.model.ChannelRole
import com.getfirepit.core.model.ChatMessage
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.model.PaneLayouts
import com.getfirepit.core.model.Reactions
import com.getfirepit.core.model.Receipt
import com.getfirepit.core.model.ReceiptState
import com.getfirepit.core.protocol.Person
import com.getfirepit.core.model.MessageStatus
import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.model.RoomKind
import com.getfirepit.core.protocol.ChannelLoad
import com.getfirepit.core.protocol.MeshConstants
import kotlinx.coroutines.launch

/**
 * Chats as a list-detail pair. On a phone or folded cover screen the detail
 * replaces the list; on a tablet or unfolded book posture they sit side by
 * side, split at the hinge. As the chat side of a wider layout it is told how
 * to split by [paneDirective], measured by its pane rather than the window.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsPane(
    modifier: Modifier = Modifier,
    paneDirective: PaneScaffoldDirective = foldAwarePaneDirective(),
    onChatOpenChange: (Boolean) -> Unit = {},
    /** True while Chats wants the whole window, beside the map or not: scanning an invite (UX §6.11.6). */
    onWholeWindowChange: (Boolean) -> Unit = {},
    /** False while the map beside it was touched last: back belongs to that side (UX §6.11.7). */
    backEnabled: Boolean = true,
    /** Ctrl+F asked for a search: the open room's, or the selected one's. Cleared once taken. */
    searchAsked: Boolean = false,
    onSearchTaken: () -> Unit = {},
    /** The map sits beside the chat, so the conversation can say what it shares with it. */
    besideMap: Boolean = false,
    /** The conversation now open, which the map beside it follows (UX §6.11.6); null with only the list showing. */
    onOpenConversationChange: (Following?) -> Unit = {},
    /** A conversation asked for from outside Chats; opened once, then handed back through [onTargetOpened]. */
    openTarget: ChatTarget? = null,
    onTargetOpened: () -> Unit = {},
    viewModel: ChatsViewModel = hiltViewModel(),
    roomsViewModel: RoomsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val roomsState by roomsViewModel.uiState.collectAsStateWithLifecycle()
    val navigator = rememberListDetailPaneScaffoldNavigator<Int>(
        scaffoldDirective = paneDirective,
    )
    val scope = rememberCoroutineScope()

    var overlay by remember { mutableStateOf<RoomsOverlay?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    // The list's search and Ctrl+F open the selected room with its search
    // already open. Search reads a room's conversation, so it needs one.
    var searchOnOpen by remember { mutableStateOf(false) }
    suspend fun searchSelected() {
        val index = state.selected ?: return
        if (state.directPeer != null) return
        searchOnOpen = true
        if (navigator.currentDestination?.pane != ListDetailPaneScaffoldRole.Detail) {
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, index)
        }
    }

    LaunchedEffect(openTarget) {
        val target = openTarget ?: return@LaunchedEffect
        overlay = null
        when (target) {
            is ChatTarget.Channel -> {
                viewModel.select(target.index)
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, target.index)
            }
            is ChatTarget.Direct -> {
                viewModel.openDirect(target.peer)
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, target.peer)
            }
        }
        onTargetOpened()
    }

    // True only when the detail covers the list, i.e. single-pane. Side by side
    // the list is still reachable, so navigation should stay.
    val chatCoversList = navigator.canNavigateBack()
    // Both the QR screens want the whole display, same as an open chat does.
    LaunchedEffect(chatCoversList, overlay) { onChatOpenChange(chatCoversList || overlay != null) }
    LaunchedEffect(overlay) { onWholeWindowChange(overlay == RoomsOverlay.Join) }

    // Only a Firepit room has a roster to follow: a Meshtastic channel would
    // leave nobody but you on the map.
    val detailShowing = navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded
    val openConversation: Following? = when {
        !detailShowing -> null
        else -> state.directPeer?.let { peer -> Following.Direct(peer, state.nameOf(peer)) }
            ?: state.selectedChannel
                ?.takeIf { it.isRoom && it.kind.isPrivate }
                ?.let { channel -> Following.Room(channel.id, channel.displayName) }
    }
    LaunchedEffect(openConversation) { onOpenConversationChange(openConversation) }

    overlay?.let { current ->
        val dismiss = {
            overlay = null
            roomsViewModel.clearMessages()
        }
        // Scanning takes the whole window, so its back is always the chat side's.
        BackHandler(enabled = backEnabled || current == RoomsOverlay.Join, onBack = dismiss)
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
                channelIndex = current.channelIndex,
                roomName = current.roomName,
                onBack = dismiss,
                modifier = modifier,
                muted = current.channelIndex in state.muted,
                kind = state.channels.firstOrNull { it.index == current.channelIndex }?.kind
                    ?: RoomKind.MESHTASTIC_PUBLIC,
                onInvite = { overlay = RoomsOverlay.Invite(current.roomId, current.roomName) },
                onToggleMute = { viewModel.toggleMute(current.channelIndex) },
                onLeft = dismiss,
                onMessage = { peer ->
                    dismiss()
                    viewModel.openDirect(peer)
                    scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, peer) }
                },
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
            onCreateShared = { name ->
                showCreateDialog = false
                roomsViewModel.addSharedChannel(name)
            },
        )
    }

    val askToMakeRadioPrivate by roomsViewModel.askToMakeRadioPrivate
        .collectAsStateWithLifecycle()
    if (askToMakeRadioPrivate) {
        MakeRadioPrivateDialog(
            onMakePrivate = roomsViewModel::makeRadioPrivate,
            onKeepPublic = roomsViewModel::keepRadioPublic,
        )
    }

    LaunchedEffect(searchAsked) {
        if (searchAsked) {
            onSearchTaken()
            searchSelected()
        }
    }

    // The scaffold's own predictive back, registered only while back is this
    // side's: the navigable scaffold always registers it, map touched last or not.
    if (backEnabled) {
        ThreePaneScaffoldPredictiveBackHandler(navigator, BackNavigationBehavior.PopUntilScaffoldValueChange)
    }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        scaffoldState = navigator.scaffoldState,
        modifier = modifier,
        listPane = {
            // Beside the conversation the list keeps to its own width (UX §9.3);
            // alone, it fills the pane whatever this says.
            AnimatedPane(modifier = Modifier.preferredWidth(PaneLayouts.LIST_WIDTH.dp)) {
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
                    person = state.person,
                    myNodeNum = state.myNode?.nodeNum,
                    latest = state.latest,
                    directLatest = state.directLatest,
                    directPeer = state.directPeer,
                    nodes = state.nodes,
                    nameOf = state::nameOf,
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
                    onSearch = { scope.launch { searchSelected() } },
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
                        startSearch = searchOnOpen,
                        onSearchStarted = { searchOnOpen = false },
                        besideMap = besideMap,
                        // Firepit issues its own invites and no others: they
                        // carry a room key the Meshtastic link format cannot.
                        onInvite = if (channel.kind == RoomKind.FIREPIT) {
                            { overlay = RoomsOverlay.Invite(channel.id, channel.displayName) }
                        } else {
                            null
                        },
                        // Every room needs a way out, whichever protocol it speaks.
                        onShowMembers = if (channel.isRoom) {
                            { overlay = RoomsOverlay.Members(channel.id, channel.displayName, channel.index) }
                        } else {
                            null
                        },
                        memberCount = if (channel.kind == RoomKind.FIREPIT) {
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
    person: Person?,
    myNodeNum: Int?,
    latest: Map<Int, ChatMessage>,
    directLatest: List<ChatMessage>,
    directPeer: Int?,
    nodes: Map<Int, MeshNode>,
    nameOf: (Int) -> String,
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
                title = person?.name ?: myNode?.displayName ?: "Firepit",
                leading = {
                    myNode?.let { node ->
                        IdentityAvatar(
                            nodeNum = node.nodeNum,
                            tag = person?.tag ?: node.shortName,
                            name = person?.name ?: node.displayName,
                        )
                    }
                },
                subtitle = { NodeStatusLine(connected = connected, myNode = myNode) },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(painterResource(FirepitIcons.Search), contentDescription = "Search messages")
                    }
                    WideScreenActions(withSettings = true)
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
                modifier = Modifier
                    // A third of a split screen is narrower than the three
                    // chips: they scroll rather than squeeze.
                    .horizontalScroll(rememberScrollState())
                    .padding(
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
                // The primary sets the radio's frequency and carries NodeInfo.
                // Firepit never converses there, so it is not listed as a chat.
                .filter { it.isRoom }
            val showRooms = filter != ChannelFilter.DIRECT
            val showDirect = filter != ChannelFilter.ROOMS

            if ((!showRooms || visible.isEmpty()) && (!showDirect || directLatest.isEmpty())) {
                EmptyState(
                    when {
                        filter == ChannelFilter.DIRECT ->
                            "No direct messages yet. To start one, tap someone in a room's info, or open " +
                                "them in Settings → Nodes."
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
                                nameOf(message.fromNodeNum)
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
                            name = nameOf(peer),
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
    name: String,
    latest: ChatMessage,
    selected: Boolean,
    onSelect: () -> Unit,
) {
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
            color = FirepitTheme.colors.textSecondary,
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
            .onSecondaryClick(onToggleMute)
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onToggleMute,
                onLongClickLabel = if (muted) "Unmute" else "Mute",
            )
            .padding(horizontal = FirepitSpacing.screenMargin, vertical = FirepitSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.m),
    ) {
        RoomAvatar(RoomIcon.forRoomId(channel.iconSeed))

        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs),
            ) {
                Text(
                    text = channel.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (channel.isRoom) RoomKindBadge(channel.kind)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs),
            ) {
                if (latest?.isOutgoing == true) StatusTick(latest.status)
                Text(
                    text = when {
                        // Before anyone has spoken, say who would be able to hear it.
                        latest == null && channel.isRoom -> channel.kind.readableBy
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
    val name = state.nameOf(peer)
    val items = remember(state.messages) { buildChatItems(state.messages) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    // The message whose reactions are open, from a long press or a right-click.
    var menuFor by remember { mutableStateOf<Int?>(null) }

    OpenAtNewest(conversation = peer, itemCount = items.size, listState = listState, reactions = state.reactions)

    Scaffold(
        topBar = {
            FirepitTopBar(
                title = name,
                onBack = onBack,
                leading = {
                    IdentityAvatar(
                        nodeNum = peer,
                        tag = node?.shortName,
                        name = name,
                        size = 40.dp,
                    )
                },
                subtitle = {
                    Text(
                        text = "Direct message",
                        style = MaterialTheme.typography.bodySmall,
                        color = FirepitTheme.colors.textSecondary,
                    )
                },
                actions = { WideScreenActions() },
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
                            is ChatItem.Notice -> SystemChip(
                                text = item.text,
                                modifier = Modifier.padding(vertical = FirepitSpacing.s),
                            )
                            is ChatItem.Bubble -> Column {
                                val message = item.message
                                Box {
                                    MessageBubble(
                                        text = message.text,
                                        time = MessageTimestamp.bubbleFormat(message.shownAt()),
                                        isOutgoing = message.isOutgoing,
                                        senderName = null,
                                        senderNodeNum = message.fromNodeNum,
                                        status = message.status.takeIf { message.isOutgoing },
                                        isFirstInGroup = item.isFirstInGroup,
                                        isLastInGroup = item.isLastInGroup,
                                        modifier = Modifier
                                            .padding(top = if (item.isFirstInGroup) FirepitSpacing.s else 2.dp)
                                            .onSecondaryClick { menuFor = message.id }
                                            .combinedClickable(
                                                onClick = {},
                                                onLongClick = { menuFor = message.id },
                                                onLongClickLabel = "React",
                                            ),
                                    )
                                    ReactionMenu(
                                        expanded = menuFor == message.id,
                                        onDismiss = { menuFor = null },
                                        onReact = { emoji -> viewModel.react(message, emoji) },
                                        onCopy = {
                                            scope.launch {
                                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Message", message.text)))
                                            }
                                        },
                                        onSendAgain = { viewModel.sendAgain(message) }
                                            .takeIf { message.isOutgoing && message.status.isFailure },
                                    )
                                }
                                state.reactions[message.id]?.let { counts ->
                                    ReactionRow(
                                        counts = counts,
                                        isOutgoing = message.isOutgoing,
                                        onReact = { emoji -> viewModel.react(message, emoji) },
                                    )
                                }
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
/**
 * Which protocol a room speaks, and therefore who can read it.
 *
 * Shown wherever a room is named rather than only on an info screen: the
 * difference between a sealed Firepit room and an ordinary Meshtastic channel
 * is the difference between private and not, and somebody typing has to be able
 * to see it without going to look.
 */
@Composable
private fun RoomKindBadge(kind: RoomKind, modifier: Modifier = Modifier) {
    val colors = FirepitTheme.colors
    val tint = when (kind) {
        RoomKind.FIREPIT -> MaterialTheme.colorScheme.primary
        RoomKind.MESHTASTIC_PRIVATE -> colors.textSecondary
        RoomKind.MESHTASTIC_PUBLIC -> colors.warn
        RoomKind.UNENCRYPTED -> colors.danger
        RoomKind.FIREPIT_KEY_MISSING, RoomKind.FIREPIT_MOVED_ON -> colors.warn
    }
    Text(
        text = kind.label,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
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
    startSearch: Boolean = false,
    onSearchStarted: () -> Unit = {},
    besideMap: Boolean = false,
) {
    val receipts by viewModel.receiptsOnScreen.collectAsStateWithLifecycle()
    val sharingViewModel: SharingViewModel = hiltViewModel()
    val sharing by sharingViewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable(channel.index) { mutableStateOf(false) }
    // The message whose reactions and actions are open, from a long press or a right-click.
    var menuFor by remember { mutableStateOf<Int?>(null) }
    val clipboard = LocalClipboard.current
    LaunchedEffect(startSearch) {
        if (startSearch) {
            searching = true
            onSearchStarted()
        }
    }

    val visible = state.visibleMessages
    val items = remember(visible) { buildChatItems(visible) }

    // Autoscroll pauses during a search: jumping to the newest message would
    // fight the reader while they scan results.
    OpenAtNewest(
        conversation = channel.index,
        itemCount = items.size,
        listState = listState,
        autoScroll = !state.isSearching,
        reactions = state.reactions,
    )

    Scaffold(
        topBar = {
            // Two different bars rather than one wearing a mode flag: searching
            // replaces the whole title, so sharing a shape would mean hiding
            // most of it half the time.
            if (searching) {
                RoomSearchBar(
                    query = state.query,
                    onQuery = viewModel::updateQuery,
                    onClose = {
                        searching = false
                        viewModel.updateQuery("")
                    },
                )
            } else {
                FirepitTopBar(
                    title = channel.displayName,
                    onBack = onBack,
                    leading = { RoomAvatar(RoomIcon.forRoomId(channel.iconSeed), size = 40.dp) },
                    badge = { if (channel.isRoom) RoomKindBadge(channel.kind) },
                    subtitle = {
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
                        if (channel.isRoom) {
                            Text(
                                text = channel.kind.readableBy,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (channel.kind.isPrivate) {
                                    FirepitTheme.colors.textSecondary
                                } else {
                                    FirepitTheme.colors.warn
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { searching = true }) {
                            Icon(
                                painter = painterResource(FirepitIcons.Search),
                                contentDescription = "Search messages",
                            )
                        }
                        WideScreenActions()
                        // Room actions live in Room info, so the bar stays narrow
                        // enough for the room's name and status to fit.
                        onShowMembers?.let { members ->
                            IconButton(onClick = members) {
                                Icon(
                                    painter = painterResource(FirepitIcons.More),
                                    contentDescription = "Room info",
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // The map beside it shows the pill; this says which conversation
            // the location goes to, where the people reading it are.
            if (besideMap && sharing.roomId == channel.id) {
                SharingChip(
                    state = sharing,
                    modifier = Modifier.padding(horizontal = FirepitSpacing.screenMargin, vertical = FirepitSpacing.xs),
                )
            }

            val visible = state.visibleMessages

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
                        is ChatItem.Notice -> SystemChip(
                            text = item.text,
                            modifier = Modifier.padding(vertical = FirepitSpacing.s),
                        )

                        is ChatItem.Bubble -> SwipeToReply(onReply = { viewModel.startReply(item.message) }) {
                            val message = item.message
                            val parent = state.repliedTo(message)
                            Column {
                                Box {
                                    MessageBubble(
                                        text = message.text,
                                        time = MessageTimestamp.bubbleFormat(message.shownAt()),
                                        isOutgoing = message.isOutgoing,
                                        senderName = state.nameOf(message.fromNodeNum),
                                        senderNodeNum = message.fromNodeNum,
                                        status = message.status.takeIf { message.isOutgoing },
                                        // Only when it is worth knowing: a relayed
                                        // message may be slow or stale, a direct one is
                                        // unremarkable and said so on every bubble.
                                        footnote = receiptFootnote(receipts[message.id])
                                            ?: message.hopsAway
                                                ?.takeIf { it > 0 }
                                                ?.let { hops -> "$hops hop${if (hops == 1) "" else "s"}" },
                                        quoted = parent?.let {
                                            QuotedMessage(
                                                senderName = state.nameOf(it.fromNodeNum),
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
                                        signed = message.signed && !message.isOutgoing,
                                        highlight = if (state.isSearching) {
                                            highlightRanges(message.text, state.query)
                                        } else {
                                            emptyList()
                                        },
                                        // Tight inside a block, open between speakers.
                                        modifier = Modifier
                                            .padding(top = if (item.isFirstInGroup) FirepitSpacing.s else 2.dp)
                                            .onSecondaryClick { menuFor = message.id }
                                            .combinedClickable(
                                                onClick = { viewModel.inspect(message) },
                                                onLongClick = { menuFor = message.id },
                                                onLongClickLabel = "React or reply",
                                            ),
                                    )
                                    ReactionMenu(
                                        expanded = menuFor == message.id,
                                        onDismiss = { menuFor = null },
                                        onReact = { emoji -> viewModel.react(message, emoji) },
                                        onReply = { viewModel.startReply(message) },
                                        onCopy = {
                                            scope.launch {
                                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Message", message.text)))
                                            }
                                        },
                                        onInfo = { viewModel.inspect(message) },
                                        onSendAgain = { viewModel.sendAgain(message) }
                                            .takeIf { message.isOutgoing && message.status.isFailure },
                                    )
                                }
                                state.reactions[message.id]?.let { counts ->
                                    ReactionRow(
                                        counts = counts,
                                        isOutgoing = message.isOutgoing,
                                        onReact = { emoji -> viewModel.react(message, emoji) },
                                    )
                                }
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

    state.inspecting?.let { message ->
        val receipts by viewModel.inspectedReceipts.collectAsStateWithLifecycle()
        MessageInfoSheet(
            message = message,
            senderName = state.nameOf(message.fromNodeNum),
            receipts = receipts,
            nameOf = state::nameOf,
            onDismiss = { viewModel.inspect(null) },
            onSendAgain = { viewModel.sendAgain(message) },
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
        color = if (congested) FirepitTheme.colors.warn else FirepitTheme.colors.textSecondary,
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
                text = state.nameOf(parent.fromNodeNum),
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

        // Kept out of the field's own supportingText slot: that slot is part of
        // the field, so the send button aligned to the bottom of the hint and
        // shifted down whenever one appeared.
        val hint: Pair<String, Color>? = when {
            // A room this phone can no longer seal for says exactly why.
            state.kind?.isStalledRoom == true -> state.kind?.summary.orEmpty() to FirepitTheme.colors.warn

            !state.hasPrivateTarget ->
                "Firepit won't send here. Open a room, or a conversation with one person." to
                    FirepitTheme.colors.warn

            state.remainingBytes <= 0 ->
                "Full — ${state.textBudget} bytes is the radio's limit" to FirepitTheme.colors.danger

            // Said on every message, not once on joining: this is the
            // difference between a room and a Meshtastic channel.
            state.isOpenConversation -> state.kind?.summary.orEmpty() to FirepitTheme.colors.warn

            // Somebody who has never shared a room with us: their phone key is
            // unknown, so only the two radios protect this.
            state.directOnlyByRadio ->
                "Only your two radios protect this, so anyone holding either one can read it. " +
                    "Share a room with them to seal it to their phone." to FirepitTheme.colors.warn

            state.losesSignature ->
                "Over ${MeshConstants.SIGNED_BROADCAST_TEXT_BUDGET} bytes: sent unsigned" to
                    FirepitTheme.colors.warn

            state.draftBytes >= 150 ->
                "${state.remainingBytes} bytes left" to FirepitTheme.colors.textSecondary

            else -> null
        }

        // Refused until the field is actually touched. When the detail pane
        // appears the focus system gives focus to the first thing that will
        // take it, and a composer that accepts opens the keyboard over the
        // conversation somebody just asked to read. Clearing it afterwards is a
        // race with that hand-off; declining it is not.
        val requester = remember { FocusRequester() }
        var acceptsFocus by remember(state.selected, state.directPeer) { mutableStateOf(false) }

        // ⚡: ready-made replies, one tap each (UX §5.4). Sent through the same
        // paced queue as anything typed, and never touching the draft.
        val quickReplies by viewModel.quickReplies.collectAsStateWithLifecycle()
        var showingQuick by remember(state.selected, state.directPeer) { mutableStateOf(false) }
        AnimatedVisibility(visible = showingQuick && quickReplies.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = FirepitSpacing.s),
                horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs),
            ) {
                quickReplies.forEach { reply ->
                    FirepitChip(
                        label = reply,
                        selected = false,
                        enabled = state.connected && state.hasPrivateTarget,
                        onClick = {
                            viewModel.sendQuickReply(reply)
                            showingQuick = false
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FirepitSpacing.s, vertical = FirepitSpacing.s),
            // Both are single-height until the field wraps, and the field grows
            // upward from the baseline, so the two stay on one line.
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
        ) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = viewModel::updateDraft,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 140.dp)
                    .focusRequester(requester)
                    .focusProperties { canFocus = acceptsFocus }
                    // A hardware keyboard's Enter sends and Shift+Enter starts a
                    // new line (UX §6.11.9). The on-screen keyboard's Enter still
                    // starts a new line, as it always has.
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        val hardware = native.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD &&
                            native.flags and AndroidKeyEvent.FLAG_SOFT_KEYBOARD == 0
                        val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        if (hardware && enter && !event.isShiftPressed) {
                            if (event.type == KeyEventType.KeyDown && state.canSend) viewModel.send()
                            true
                        } else {
                            false
                        }
                    }
                    .pointerInput(state.selected, state.directPeer) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            if (!acceptsFocus) {
                                acceptsFocus = true
                                requester.requestFocus()
                            }
                        }
                    },
                placeholder = { Text("Message") },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = FirepitTheme.colors.surface2,
                    unfocusedContainerColor = FirepitTheme.colors.surface2,
                    focusedBorderColor = FirepitTheme.colors.outline,
                    unfocusedBorderColor = FirepitTheme.colors.outline,
                ),
            )
            IconButton(
                onClick = { showingQuick = !showingQuick },
                modifier = Modifier.size(FirepitSpacing.minTouchTarget),
            ) {
                Icon(
                    painter = painterResource(FirepitIcons.QuickReply),
                    contentDescription = if (showingQuick) "Hide quick replies" else "Quick replies",
                    tint = if (showingQuick) MaterialTheme.colorScheme.primary else FirepitTheme.colors.textSecondary,
                )
            }
            FilledIconButton(
                onClick = viewModel::send,
                enabled = state.canSend,
                shape = CircleShape,
                modifier = Modifier.size(FirepitSpacing.minTouchTarget),
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

        // Only appears near a limit, so the composer stays quiet. Indented to
        // the field's text rather than its border.
        hint?.let { (text, colour) ->
            Text(
                text = text,
                color = colour,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(
                    start = FirepitSpacing.s + FirepitSpacing.l,
                    end = FirepitSpacing.s + FirepitSpacing.minTouchTarget,
                    bottom = FirepitSpacing.s,
                ),
            )
        }
    }
}

/**
 * Opens a conversation at its newest message, with nothing typing.
 *
 * Counts rendered rows rather than messages: the list also holds day
 * separators and notices, so a message index lands short of the bottom by
 * however many days the conversation spans.
 *
 * The first landing jumps; later arrivals animate. Someone opening a room
 * asked for the conversation, not a scroll through it, but a message arriving
 * while they are reading is worth seeing move.
 *
 * A reaction makes its message taller without adding a row. Whoever was
 * reading the newest message keeps it in view; whoever was reading further up
 * stays where they were.
 */
@Composable
private fun OpenAtNewest(
    conversation: Any,
    itemCount: Int,
    listState: LazyListState,
    autoScroll: Boolean = true,
    reactions: Map<Int, List<Reactions.Count>> = emptyMap(),
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var landed by remember(conversation) { mutableStateOf(false) }
    // Read while composing, so from the layout before a reaction made a message taller.
    val atNewest = !listState.canScrollForward

    // Switching rooms reuses the composer, so a cursor left in it from the last
    // one raises the keyboard over the messages the reader came to see.
    LaunchedEffect(conversation) {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }

    LaunchedEffect(conversation, itemCount, autoScroll) {
        if (itemCount == 0 || !autoScroll) return@LaunchedEffect
        if (landed) {
            listState.animateScrollToItem(itemCount - 1)
        } else {
            listState.scrollToItem(itemCount - 1)
            landed = true
        }
    }

    LaunchedEffect(conversation, reactions) {
        if (landed && atNewest && autoScroll && itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
}

/**
 * The room header while searching.
 *
 * A bar, not a boxed form field: it sits in the header and only ever holds one
 * short phrase.
 */@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomSearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    TopAppBar(
        title = {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = FirepitTheme.colors.textPrimary,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
                decorationBox = { field ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(FirepitTheme.colors.surface2)
                            .padding(horizontal = FirepitSpacing.m, vertical = 10.dp),
                    ) {
                        Icon(
                            painter = painterResource(FirepitIcons.Search),
                            contentDescription = null,
                            tint = FirepitTheme.colors.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text(
                                    text = "Search this room",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = FirepitTheme.colors.textSecondary,
                                )
                            }
                            field()
                        }
                        if (query.isNotEmpty()) {
                            // A full touch target round a small glyph: an 18 dp target was too easy to miss.
                            IconButton(onClick = { onQuery("") }) {
                                Icon(
                                    painter = painterResource(FirepitIcons.Close),
                                    contentDescription = "Clear search",
                                    tint = FirepitTheme.colors.textSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                },
            )
        },
        actions = {
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(FirepitIcons.Close),
                    contentDescription = "Close search",
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/**
 * Delivery detail for one message.
 *
 * Only shows what the radio actually reported: absent values are omitted rather
 * than rendered as zero, because "0 dB SNR" and "not measured" are different
 * claims.
 */@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageInfoSheet(
    message: ChatMessage,
    senderName: String?,
    receipts: List<Receipt>,
    nameOf: (Int) -> String,
    onDismiss: () -> Unit,
    onSendAgain: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.withinPane(),
        sheetMaxWidth = paneSheetMaxWidth(),
        shape = SheetShape,
    ) {
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
                if (message.status.isFailure) {
                    Button(onClick = onSendAgain, modifier = Modifier.fillMaxWidth()) { Text("Send again") }
                }
            }

            message.hopsAway?.let { hops ->
                InfoRow("Hops", if (hops == 0) "Direct, no relay" else "$hops")
            }
            message.rxSnr?.let { InfoRow("Signal to noise", "%.2f dB".format(it)) }
            message.rxRssi?.let { InfoRow("Signal strength", "$it dBm") }
            if (message.signed) InfoRow("Signature", "Verified")

            ReceiptList("Read by", receipts.filter { it.state == ReceiptState.READ }, nameOf)
            ReceiptList("Received by", receipts.filter { it.state == ReceiptState.RECEIVED }, nameOf)

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

/**
 * What the room has told us about a message we sent.
 *
 * Counts only what arrived. A member whose phone said nothing is absent rather
 * than reported as not having read it, because silence carries no information:
 * they may be asleep, out of range, or have receipts turned off.
 */
private fun receiptFootnote(receipts: List<Receipt>?): String? {
    if (receipts.isNullOrEmpty()) return null
    val read = receipts.count { it.state == ReceiptState.READ }
    val held = receipts.size
    return when {
        read > 0 -> "Read by $read"
        else -> "Delivered to $held"
    }
}

/**
 * Who told us they have this message, and when.
 *
 * Only the phones that reported appear. Silence is not evidence of anything, so
 * nobody is ever listed as not having read something.
 */
@Composable
private fun ReceiptList(label: String, receipts: List<Receipt>, nameOf: (Int) -> String) {
    if (receipts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = FirepitTheme.colors.textSecondary,
        )
        receipts.sortedBy { it.at }.forEach { receipt ->
            InfoRow(nameOf(receipt.nodeNum), formatTime(receipt.at))
        }
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
