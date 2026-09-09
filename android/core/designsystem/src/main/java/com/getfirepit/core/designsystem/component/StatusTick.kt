package com.getfirepit.core.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    val (glyph, description) = status.glyphAndLabel()

    Row(modifier = modifier.semantics { contentDescription = description }) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.bodySmall,
            color = status.tint(),
        )
    }
}

@Composable
fun MessageStatus.tint(): Color = when (this) {
    MessageStatus.REACHED_MESH, MessageStatus.DELIVERED -> FirepitTheme.colors.live
    MessageStatus.FAILED -> FirepitTheme.colors.danger
    else -> FirepitTheme.colors.textSecondary
}

fun MessageStatus.glyphAndLabel(): Pair<String, String> = when (this) {
    MessageStatus.QUEUED -> "◷" to "Sending"
    MessageStatus.SENT_TO_NODE, MessageStatus.UNKNOWN -> "✓" to "Sent to your node"
    MessageStatus.REACHED_MESH -> "✓" to "Heard by at least one node"
    MessageStatus.DELIVERED -> "✓✓" to "Delivered to their node"
    MessageStatus.UNHEARD -> "✓" to "No node heard this"
    MessageStatus.FAILED -> "⚠" to "Failed, tap to retry"
    // Nothing to report about a message we are holding.
    MessageStatus.RECEIVED -> "" to ""
}
