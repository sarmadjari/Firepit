package com.getfirepit.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.getfirepit.core.designsystem.theme.BubbleShape
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.designsystem.theme.identityColorFor
import com.getfirepit.core.model.MessageStatus

/** The message a bubble is replying to, shown as a quote inside it. */
data class QuotedMessage(
    val senderName: String,
    val senderNodeNum: Int?,
    val text: String,
)

/**
 * A chat bubble.
 *
 * Width is capped as a fraction of the available space rather than a fixed dp,
 * so it behaves identically on a folded cover display and an unfolded pane.
 *
 * [isFirstInGroup] and [isLastInGroup] describe a run of consecutive messages
 * from one sender: only the first repeats the name and only the last gets the
 * tail corner, so a burst reads as one block instead of four separate shouts.
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
    quoted: QuotedMessage? = null,
    onQuoteClick: (() -> Unit)? = null,
    /** Runs to paint, decided by the caller that owns the search. */
    highlight: List<IntRange> = emptyList(),
    isFirstInGroup: Boolean = true,    isLastInGroup: Boolean = true,
) {
    val dark = FirepitTheme.colors.isDark
    val shape = bubbleShapeFor(isOutgoing, isLastInGroup)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .constrainToBubbleWidth()
                .background(
                    if (isOutgoing) FirepitTheme.colors.bubbleOut else FirepitTheme.colors.bubbleIn,
                    shape,
                )
                .border(
                    width = when {
                        isAlert -> 2.dp
                        isOutgoing -> 0.dp
                        else -> 1.dp
                    },
                    color = if (isAlert) FirepitTheme.colors.warn else MaterialTheme.colorScheme.outline,
                    shape = shape,
                )
                .padding(
                    horizontal = FirepitSpacing.bubblePaddingHorizontal,
                    vertical = FirepitSpacing.bubblePaddingVertical,
                ),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (isAlert) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs),
                ) {
                    androidx.compose.material3.Icon(
                        painter = androidx.compose.ui.res.painterResource(FirepitIcons.Bell),
                        contentDescription = null,
                        tint = FirepitTheme.colors.warn,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "ALERT",
                        style = MaterialTheme.typography.labelMedium,
                        color = FirepitTheme.colors.warn,
                    )
                }
            }
            if (senderName != null && !isOutgoing && isFirstInGroup) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = senderNodeNum
                        ?.let { identityColorFor(it, dark) }
                        ?: FirepitTheme.colors.textSecondary,
                )
            }

            quoted?.let { QuotedBlock(it, dark, onQuoteClick) }

            Text(
                text = highlighted(text, highlight, FirepitTheme.colors.warn),
                style = MaterialTheme.typography.bodyLarge,
            )

            // Its own line: sharing one with the text stretched short messages
            // into a wide, cramped strip.
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.End),
            ) {
                footnote?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = FirepitTheme.colors.textSecondary,
                    )
                }
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = FirepitTheme.colors.textSecondary,
                )
                status?.let { StatusTick(it) }
            }
        }
    }
}

/** Paints the search runs, leaving the rest of the message alone. */
@Composable
private fun highlighted(text: String, runs: List<IntRange>, tint: Color): AnnotatedString {
    if (runs.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        runs.forEach { run ->
            // A stale range from a query that changed under us would crash.
            val from = run.first.coerceIn(0, text.length)
            val to = (run.last + 1).coerceIn(from, text.length)
            addStyle(SpanStyle(background = tint.copy(alpha = 0.35f)), from, to)
        }
    }
}

/** Caps the bubble at a share of the parent width, per the UX spec. */private fun Modifier.constrainToBubbleWidth() = layout { measurable, constraints ->
    val maxWidth = (constraints.maxWidth * FirepitSpacing.BUBBLE_MAX_WIDTH_FRACTION).toInt()
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = maxWidth))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * The quoted message, tinted and bar-marked so it reads as a quote at a glance
 * rather than as part of the reply's own text.
 */
@Composable
private fun QuotedBlock(quoted: QuotedMessage, dark: Boolean, onClick: (() -> Unit)?) {
    val accent = quoted.senderNodeNum
        ?.let { identityColorFor(it, dark) }
        ?: FirepitTheme.colors.textSecondary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(QuoteShape)
            // Neutral wash, not the sender's hue: a full-strength identity
            // colour behind the quote fought the bubble it sits inside.
            .background(FirepitTheme.colors.textSecondary.copy(alpha = if (dark) 0.14f else 0.07f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(QuoteBarWidth)
                .fillMaxHeight()
                .background(accent),
        )
        Column(
            modifier = Modifier.padding(
                horizontal = FirepitSpacing.s,
                vertical = FirepitSpacing.xs,
            ),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = quoted.senderName,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = quoted.text,
                style = MaterialTheme.typography.bodySmall,
                color = FirepitTheme.colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val QuoteShape = RoundedCornerShape(6.dp)
private val QuoteBarWidth = 3.dp

/** Square-ish tail on the sender's side, and only on the last of a run. */
private fun bubbleShapeFor(isOutgoing: Boolean, isLastInGroup: Boolean): RoundedCornerShape {
    if (!isLastInGroup) return BubbleShape

    val tail = 5.dp
    val round = 18.dp
    return if (isOutgoing) {
        RoundedCornerShape(topStart = round, topEnd = round, bottomStart = round, bottomEnd = tail)
    } else {
        RoundedCornerShape(topStart = round, topEnd = round, bottomStart = tail, bottomEnd = round)
    }
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
