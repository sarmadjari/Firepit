package com.getfirepit.app.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.model.Reactions

/**
 * The reactions under a message (UX §5.4): each emoji, how many chose it when
 * more than one did, and yours outlined. Tapping someone else's adds yours.
 */
@Composable
fun ReactionRow(
    counts: List<Reactions.Count>,
    isOutgoing: Boolean,
    onReact: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = if (isOutgoing) {
            Arrangement.spacedBy(FirepitSpacing.xs, Alignment.End)
        } else {
            Arrangement.spacedBy(FirepitSpacing.xs)
        },
    ) {
        counts.forEach { count ->
            Surface(
                onClick = { if (!count.mine) onReact(count.emoji) },
                shape = CircleShape,
                color = if (count.mine) FirepitTheme.colors.bubbleOut else FirepitTheme.colors.surface2,
                border = BorderStroke(
                    1.dp,
                    if (count.mine) MaterialTheme.colorScheme.primary else FirepitTheme.colors.outline,
                ),
                modifier = Modifier.semantics {
                    contentDescription = buildString {
                        append(count.emoji)
                        append(if (count.count == 1) ", 1 person" else ", ${count.count} people")
                        if (count.mine) append(", including you")
                    }
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = FirepitSpacing.s, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(count.emoji, fontSize = 14.sp)
                    if (count.count > 1) {
                        Text(
                            text = count.count.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = FirepitTheme.colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * What a long press on a message offers (UX §5.4): the six reactions in one
 * row, one tap each, then Send again for a message that failed, and Reply,
 * Copy and Message info where the conversation has them.
 */
@Composable
fun ReactionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onCopy: () -> Unit,
    onReply: (() -> Unit)? = null,
    onInfo: (() -> Unit)? = null,
    onSendAgain: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // Six 48 dp targets: 296 dp in all, so the row fits the narrowest phone.
        Row(modifier = Modifier.padding(horizontal = FirepitSpacing.xs)) {
            Reactions.CHOICES.forEach { emoji ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClickLabel = "React", role = Role.Button) {
                            onDismiss()
                            onReact(emoji)
                        },
                ) { Text(emoji, fontSize = 24.sp) }
            }
        }
        HorizontalDivider()
        onSendAgain?.let { again ->
            DropdownMenuItem(text = { Text("Send again") }, onClick = { onDismiss(); again() })
        }
        onReply?.let { reply -> DropdownMenuItem(text = { Text("Reply") }, onClick = { onDismiss(); reply() }) }
        DropdownMenuItem(text = { Text("Copy") }, onClick = { onDismiss(); onCopy() })
        onInfo?.let { info -> DropdownMenuItem(text = { Text("Message info") }, onClick = { onDismiss(); info() }) }
    }
}
