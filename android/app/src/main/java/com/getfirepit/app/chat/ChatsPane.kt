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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.core.designsystem.adaptive.foldAwarePaneDirective
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
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigator = rememberListDetailPaneScaffoldNavigator<Int>(
        scaffoldDirective = foldAwarePaneDirective(),
    )
    val scope = rememberCoroutineScope()

    // True only when the detail covers the list, i.e. single-pane. Side by side
    // the list is still reachable, so navigation should stay.
    val chatCoversList = navigator.canNavigateBack()
    LaunchedEffect(chatCoversList) { onChatOpenChange(chatCoversList) }

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
                    onSelect = { index ->
                        viewModel.select(index)
                        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, index) }
                    },
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
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelList(
    channels: List<RoomChannel>,
    connected: Boolean,
    selected: Int?,
    onSelect: (Int) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Firepit") }) }) { padding ->
        Column(Modifier.padding(padding)) {
            Text(
                text = if (connected) "Connected to your node" else "Not connected — open Settings",
                style = MaterialTheme.typography.bodyMedium,
                color = if (connected) FirepitTheme.colors.live else FirepitTheme.colors.stale,
                modifier = Modifier.padding(horizontal = FirepitSpacing.screenMargin),
            )

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
                        leadingContent = { RoomAvatar(RoomIcon.forRoomId(channel.id + channel.index)) },
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
                    onBack?.let { back ->
                        IconButton(onClick = back) {
                            Text("\u2039", style = MaterialTheme.typography.headlineLarge)
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
