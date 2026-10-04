package com.getfirepit.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme

/**
 * A choice among a small set, filled when taken.
 *
 * Used for both filters and settings so that picking a room and picking a
 * duration feel like the same gesture.
 */
@Composable
fun FirepitChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(FirepitSpacing.chipCorner),
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                // A chip's word is never split: in a narrow window "Direct" broke into "Dire" and "ct".
                maxLines = 1,
                softWrap = false,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = FirepitTheme.colors.surface2,
            labelColor = FirepitTheme.colors.textPrimary,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = if (selected) null else BorderStroke(1.dp, FirepitTheme.colors.outline),
    )
}

/** Unread count. Stays visible on a muted room, which counts but does not interrupt. */
@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else "$count",
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Small uppercase heading that separates groups without drawing a line. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = FirepitTheme.colors.textSecondary,
        letterSpacing = 0.8.sp,
        modifier = modifier.padding(
            start = FirepitSpacing.screenMargin,
            end = FirepitSpacing.screenMargin,
            top = FirepitSpacing.m,
            bottom = FirepitSpacing.xs,
        ),
    )
}

/** Centred pill for day breaks and membership changes. */
@Composable
fun TimelinePill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(FirepitTheme.colors.surface2)
            .padding(horizontal = FirepitSpacing.m, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = FirepitTheme.colors.textSecondary,
        )
    }
}

/** Outlined container used for member lists, invites and info panels. */
@Composable
fun FirepitCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = RoundedCornerShape(FirepitSpacing.cardCorner),
        color = FirepitTheme.colors.surface2,
        border = BorderStroke(1.dp, FirepitTheme.colors.outline),
        content = { Box(Modifier.padding(PaddingValues(0.dp))) { content() } },
    )
}
