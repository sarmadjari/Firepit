package com.getfirepit.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.getfirepit.core.designsystem.theme.BubbleShape
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.designsystem.theme.identityColorFor
import com.getfirepit.core.model.MessageStatus

/**
 * A chat bubble.
 *
 * Width is capped as a fraction of the available space rather than a fixed dp,
 * so it behaves identically on a folded cover display and an unfolded pane.
 */
@Composable
fun MessageBubble(
    text: String,
    time: String,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier,
    senderName: String? = null,
    senderNodeNum: Int? = null,
    status: MessageStatus? = null,
    footnote: String? = null,
    isAlert: Boolean = false,
) {
    val dark = FirepitTheme.colors.isDark

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .constrainToBubbleWidth()
                .background(
                    if (isOutgoing) FirepitTheme.colors.bubbleOut else FirepitTheme.colors.bubbleIn,
                    BubbleShape,
                )
                .border(
                    width = when {
                        isAlert -> 2.dp
                        isOutgoing -> 0.dp
                        else -> 1.dp
                    },
                    color = if (isAlert) FirepitTheme.colors.warn else MaterialTheme.colorScheme.outline,
                    shape = BubbleShape,
                )
                .padding(
                    horizontal = FirepitSpacing.bubblePaddingHorizontal,
                    vertical = FirepitSpacing.bubblePaddingVertical,
                ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (isAlert) {
                Text(
                    text = "🔔 ALERT",
                    style = MaterialTheme.typography.bodySmall,
                    color = FirepitTheme.colors.warn,
                )
            }
            if (senderName != null && !isOutgoing) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.bodySmall,
                    color = senderNodeNum
                        ?.let { identityColorFor(it, dark) }
                        ?: FirepitTheme.colors.textSecondary,
                )
            }

            Text(text, style = MaterialTheme.typography.bodyLarge)

            Row(
                horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.End),
            ) {
                footnote?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = FirepitTheme.colors.textSecondary)
                }
                Text(time, style = MaterialTheme.typography.bodySmall, color = FirepitTheme.colors.textSecondary)
                status?.let { StatusTick(it) }
            }
        }
    }
}

/** Caps the bubble at a share of the parent width, per the UX spec. */
private fun Modifier.constrainToBubbleWidth() = layout { measurable, constraints ->
    val maxWidth = (constraints.maxWidth * FirepitSpacing.BUBBLE_MAX_WIDTH_FRACTION).toInt()
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = maxWidth))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** Centred pill used for date separators and system events. */
@Composable
fun SystemChip(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = FirepitTheme.colors.textSecondary,
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                .padding(horizontal = FirepitSpacing.m, vertical = FirepitSpacing.xs),
        )
    }
}
