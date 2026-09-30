package com.getfirepit.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getfirepit.app.ui.icons.FirepitIcons
import com.getfirepit.app.ui.mock.MsgStatus
import com.getfirepit.app.ui.theme.FirepitTheme

/** Person avatar: 2-char tag on the identity colour (UX §7.1). */
@Composable
fun TagAvatar(tag: String, identity: Int, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val colors = FirepitTheme.colors
    Box(
        modifier = modifier.size(size).background(colors.identity[identity % 12], CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(tag, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.34f).sp)
    }
}

/** Room avatar: fixed icon in primary on the outgoing-bubble tint (U-5). */
@Composable
fun RoomAvatar(icon: ImageVector, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val colors = FirepitTheme.colors
    Box(
        modifier = modifier.size(size).background(colors.bubbleOut, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * 0.5f))
    }
}

/** Infrastructure avatar (Base / Router): house on the reserved slate colour. */
@Composable
fun InfraAvatar(size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val colors = FirepitTheme.colors
    Box(modifier = modifier.size(size).background(colors.infra, CircleShape), contentAlignment = Alignment.Center) {
        Icon(FirepitIcons.House, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

/** Honest ticks (UX §7.2): grey ◷ / grey ✓ / green ✓ / green ✓✓ / red ⚠. */
@Composable
fun StatusGlyph(status: MsgStatus, size: Dp = 16.dp) {
    val colors = FirepitTheme.colors
    val (icon, tint) = when (status) {
        MsgStatus.QUEUED -> FirepitIcons.Clock to colors.stale
        MsgStatus.SENT -> FirepitIcons.Check to colors.stale
        MsgStatus.HEARD -> FirepitIcons.Check to colors.live
        MsgStatus.DELIVERED -> FirepitIcons.CheckDouble to colors.live
        MsgStatus.FAILED -> FirepitIcons.Warning to colors.danger
    }
    Icon(icon, contentDescription = status.name, tint = tint, modifier = Modifier.size(size))
}

/** "● Sam's T-Echo · 78%" — node status line under the Chats title (UX §7.4). */
@Composable
fun NodeStatusLine(text: String, modifier: Modifier = Modifier) {
    val colors = FirepitTheme.colors
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(colors.live, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun outlineBorder(): BorderStroke = BorderStroke(1.dp, FirepitTheme.colors.outline)

fun Modifier.outlined(color: Color, shape: androidx.compose.ui.graphics.Shape) = this.border(1.dp, color, shape)
