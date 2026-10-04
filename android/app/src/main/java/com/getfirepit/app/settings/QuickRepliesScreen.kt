package com.getfirepit.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.getfirepit.core.designsystem.component.FirepitIcons
import com.getfirepit.core.designsystem.component.FirepitTopBar
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.protocol.MeshConstants
import com.getfirepit.core.protocol.QuickReplies

/**
 * Settings › Quick replies (UX §5.4, §8): the ready-made messages ⚡ offers
 * beside the message box.
 */
@Composable
fun QuickRepliesScreen(
    replies: List<String>,
    onChange: (List<String>) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The reply being edited, by its place in the list; ADDING for a new one.
    var editing by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = { FirepitTopBar(title = "Quick replies", onBack = onBack) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            itemsIndexed(replies) { index, reply ->
                ListItem(
                    headlineContent = { Text(reply) },
                    trailingContent = {
                        IconButton(onClick = { onChange(replies.filterIndexed { at, _ -> at != index }) }) {
                            Icon(painterResource(FirepitIcons.Close), contentDescription = "Delete $reply")
                        }
                    },
                    modifier = Modifier.clickable(onClickLabel = "Edit $reply") { editing = index },
                )
                HorizontalDivider(color = FirepitTheme.colors.outline)
            }
            item {
                Column(
                    modifier = Modifier.padding(FirepitSpacing.screenMargin),
                    verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
                ) {
                    if (replies.size < QuickReplies.MAX_COUNT) {
                        TextButton(onClick = { editing = ADDING }) { Text("Add a quick reply") }
                    }
                    TextButton(onClick = onReset) { Text("Back to the defaults") }
                    Text(
                        text = "Each one goes out as a message of its own, so they are kept short: " +
                            "${QuickReplies.MAX_BYTES} bytes at most. ⚡ beside the message box sends one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FirepitTheme.colors.textSecondary,
                    )
                }
            }
        }
    }

    editing?.let { index ->
        QuickReplyDialog(
            initial = replies.getOrNull(index).orEmpty(),
            onSave = { text ->
                onChange(if (index == ADDING) replies + text else replies.mapIndexed { at, old -> if (at == index) text else old })
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun QuickReplyDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val left = QuickReplies.MAX_BYTES - text.toByteArray(Charsets.UTF_8).size
    val cleaned = QuickReplies.clean(text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isEmpty()) "Add a quick reply" else "Edit quick reply") },
        text = {
            OutlinedTextField(
                value = text,
                // Cut at the byte limit as it is typed, never splitting a character.
                onValueChange = { text = MeshConstants.truncateToBytes(it, QuickReplies.MAX_BYTES) },
                singleLine = true,
                placeholder = { Text("On my way") },
                supportingText = { Text("$left bytes left") },
            )
        },
        confirmButton = {
            TextButton(onClick = { cleaned?.let(onSave) }, enabled = cleaned != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val ADDING = -1
