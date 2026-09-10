package com.getfirepit.core.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.model.MessageStatus

/**
 * The honest ticks.
 *
 * There is deliberately no blue double tick and no "read" state: a LoRa mesh
 * cannot prove either. A room broadcast stops at "heard by the mesh", which
 * only means some node repeated it.
 *
 * Every state carries a spoken label as well as a colour, so the meaning never
 * depends on colour alone.
 */
@Composable
fun StatusTick(status: MessageStatus, modifier: Modifier = Modifier) {
    val (icon, description) = status.iconAndLabel() ?: return

    Icon(
        painter = painterResource(icon),
        contentDescription = description,
        tint = status.tint(),
        modifier = modifier.size(14.dp),
    )
}

@Composable
fun MessageStatus.tint(): Color = when (this) {
    MessageStatus.REACHED_MESH, MessageStatus.DELIVERED -> FirepitTheme.colors.live
    MessageStatus.FAILED -> FirepitTheme.colors.danger
    else -> FirepitTheme.colors.textSecondary
}

/** Null while we are holding a message of our own: there is nothing to report. */
fun MessageStatus.iconAndLabel(): Pair<Int, String>? = when (this) {
    MessageStatus.QUEUED -> FirepitIcons.Pending to "Sending"
    MessageStatus.SENT_TO_NODE, MessageStatus.UNKNOWN -> FirepitIcons.Tick to "Sent to your node"
    MessageStatus.REACHED_MESH -> FirepitIcons.Tick to "Heard by at least one node"
    MessageStatus.DELIVERED -> FirepitIcons.TickDouble to "Delivered to their node"
    MessageStatus.UNHEARD -> FirepitIcons.Tick to "No node heard this"
    MessageStatus.FAILED -> FirepitIcons.Warning to "Failed, tap to retry"
    MessageStatus.RECEIVED -> null
}
