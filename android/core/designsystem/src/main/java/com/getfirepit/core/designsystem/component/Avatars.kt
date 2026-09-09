package com.getfirepit.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.designsystem.theme.identityColorFor
import com.getfirepit.core.designsystem.theme.onIdentityColorFor

/**
 * A person, drawn as their 2-character tag on a colour derived from their node
 * number. Colour is decoration only — the name always appears alongside — so a
 * hue collision is cosmetic.
 */
@Composable
fun IdentityAvatar(
    nodeNum: Int,
    tag: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = FirepitSpacing.avatarSize,
) {
    val dark = FirepitTheme.colors.isDark
    val label = tag?.takeIf { it.isNotBlank() } ?: "?"

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(identityColorFor(nodeNum, dark))
            // The name is already read out by the row, so the tag would be noise.
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = onIdentityColorFor(nodeNum, dark),
            fontWeight = FontWeight.SemiBold,
            // Tags from other clients can be 3-4 characters; shrink rather than truncate.
            fontSize = if (label.length > 2) (size.value * 0.30f).sp else (size.value * 0.36f).sp,
        )
    }
}

/** A room, drawn as one of the fixed icons on the outgoing-bubble tint. */
@Composable
fun RoomAvatar(
    icon: RoomIcon,
    modifier: Modifier = Modifier,
    size: Dp = FirepitSpacing.avatarSize,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(FirepitTheme.colors.bubbleOut),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = icon.glyph,
            color = MaterialTheme.colorScheme.primary,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

/** Infrastructure nodes share one reserved colour and differ by glyph. */
@Composable
fun InfraAvatar(isRouter: Boolean, modifier: Modifier = Modifier, size: Dp = FirepitSpacing.avatarSize) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(FirepitTheme.colors.infra),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (isRouter) "▲" else "⌂",
            color = Color.White,
            fontSize = (size.value * 0.40f).sp,
        )
    }
}

/** Live marker ring, used on the map and for the connected dot. */
@Composable
fun LiveRing(modifier: Modifier = Modifier, size: Dp = 12.dp, live: Boolean = true) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (live) FirepitTheme.colors.live else FirepitTheme.colors.stale)
            .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
    )
}
