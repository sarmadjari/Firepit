package com.getfirepit.app.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.core.designsystem.theme.BubbleShape
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.designsystem.theme.identityColorFor
import com.getfirepit.core.model.ChatMessage
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.model.MessageStatus

@Composable
fun ChatSection(
    availableChannels: List<Pair<Int, String>>,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs)) {
            availableChannels.forEach { (index, name) ->
                FilterChip(
                    selected = state.channel == index,
                    onClick = { viewModel.selectChannel(index) },
                    label = { Text("$index $name") },
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(FirepitSpacing.xs),
        ) {
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(message, state.nodes[message.fromNodeNum])
            }
        }

        state.error?.let { message ->
            Text(message, color = FirepitTheme.colors.danger, style = MaterialTheme.typography.bodySmall)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.draft,
                onValueChange = viewModel::updateDraft,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                supportingText = {
                    // Only appears near the limit, as the composer spec requires.
                    if (state.draftBytes >= 150) {
                        Text(
                            "${state.remainingBytes} bytes left",
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
            IconButton(onClick = viewModel::send, enabled = state.canSend) {
                Text("↑", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, sender: MeshNode?) {
    val dark = FirepitTheme.colors.isDark
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    if (message.isOutgoing) FirepitTheme.colors.bubbleOut else FirepitTheme.colors.bubbleIn,
                    BubbleShape,
                )
                .border(
                    width = if (message.isOutgoing) 0.dp else 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = BubbleShape,
                )
                .padding(
                    horizontal = FirepitSpacing.bubblePaddingHorizontal,
                    vertical = FirepitSpacing.bubblePaddingVertical,
                ),
        ) {
            if (!message.isOutgoing) {
                Text(
                    text = sender?.displayName ?: "!%08x".format(message.fromNodeNum),
                    style = MaterialTheme.typography.bodySmall,
                    color = identityColorFor(message.fromNodeNum, dark),
                )
            }
            Text(message.text, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs)) {
                message.hopsAway?.let {
                    Text(
                        "$it hop${if (it == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = FirepitTheme.colors.textSecondary,
                    )
                }
                if (message.isOutgoing) {
                    Text(
                        text = message.status.glyph(),
                        style = MaterialTheme.typography.bodySmall,
                        color = message.status.tint(),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

/** Deliberately never a double tick for a room message — see UX §7.2. */
private fun MessageStatus.glyph(): String = when (this) {
    MessageStatus.QUEUED -> "◷ sending"
    MessageStatus.SENT_TO_NODE, MessageStatus.UNKNOWN -> "✓ sent to your node"
    MessageStatus.REACHED_MESH -> "✓ heard by the mesh"
    MessageStatus.DELIVERED -> "✓✓ delivered"
    MessageStatus.UNHEARD -> "✓ no one heard this"
    MessageStatus.FAILED -> "⚠ failed"
}

@Composable
private fun MessageStatus.tint() = when (this) {
    MessageStatus.REACHED_MESH, MessageStatus.DELIVERED -> FirepitTheme.colors.live
    MessageStatus.FAILED -> FirepitTheme.colors.danger
    else -> FirepitTheme.colors.textSecondary
}
