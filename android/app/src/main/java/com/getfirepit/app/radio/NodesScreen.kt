package com.getfirepit.app.radio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.core.designsystem.component.FirepitDetailBar
import com.getfirepit.core.designsystem.component.IdentityAvatar
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.protocol.KeyFingerprint
import com.getfirepit.core.protocol.MeshConstants

/** Everyone the mesh can currently see, and what is known about each of them. */
@Composable
fun NodesScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onMessage: (Int) -> Unit = {},
    viewModel: RadioViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = { FirepitDetailBar(title = "Nodes", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = FirepitSpacing.screenMargin),
        ) {
            if (state.nodes.isEmpty()) {
                Text(
                    text = if (state.details == null) {
                        "Connect a device to see the mesh around it."
                    } else {
                        "Nobody has been heard yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = FirepitTheme.colors.textSecondary,
                    modifier = Modifier.padding(vertical = FirepitSpacing.m),
                )
            }
            LazyColumn {
                items(state.nodes, key = { it.nodeNum }) { node ->
                    NodeRow(
                        node = node,
                        isSelf = node.nodeNum == state.myNodeNum,
                        tracing = state.tracing == node.nodeNum,
                        enabled = state.tracing == null,
                        expanded = expanded == node.nodeNum,
                        onToggle = {
                            expanded = if (expanded == node.nodeNum) null else node.nodeNum
                        },
                        onCheckPath = { viewModel.checkPath(node) },
                        onMessage = { onMessage(node.nodeNum) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    state.traceResult?.let { summary ->
        AlertDialog(
            onDismissRequest = viewModel::clearTrace,
            title = { Text("Path check") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s)) {
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "A path is not a delivery receipt. It shows the mesh could reach them " +
                            "just now, not that anyone read anything.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FirepitTheme.colors.textSecondary,
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::clearTrace) { Text("Close") } },
        )
    }
}

@Composable
private fun NodeRow(
    node: MeshNode,
    isSelf: Boolean,
    tracing: Boolean,
    enabled: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCheckPath: () -> Unit,
    onMessage: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = FirepitSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.m),
        ) {
            IdentityAvatar(
                nodeNum = node.nodeNum,
                tag = node.shortName,
                name = node.displayName,
                size = 36.dp,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (isSelf) "${node.displayName} (this device)" else node.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = nodeDetail(node),
                    style = MaterialTheme.typography.bodySmall,
                    color = FirepitTheme.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!isSelf) {
                TextButton(onClick = onCheckPath, enabled = enabled) {
                    Text(if (tracing) "…" else "Path")
                }
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(bottom = FirepitSpacing.m),
                verticalArrangement = Arrangement.spacedBy(FirepitSpacing.xs),
            ) {
                nodeFacts(node).forEach { (label, value) ->
                    Field(label, value, monospace = label in MONOSPACE)
                }
                // Where a conversation with one person starts, besides a room's members.
                if (!isSelf && !node.isUnmessagable) {
                    FilledTonalButton(
                        onClick = onMessage,
                        modifier = Modifier.padding(top = FirepitSpacing.s),
                    ) { Text("Message") }
                }
            }
        }
    }
}

/** Only what the node actually reported: a blank row teaches nothing. */
private fun nodeFacts(node: MeshNode): List<Pair<String, String>> = buildList {
    add("Node ID" to MeshConstants.formatNodeId(node.nodeNum))
    node.hwModel?.let { add("Hardware" to prettyName(it)) }
    node.role?.let { add("Device role" to prettyName(it)) }
    add("Hops away" to (node.hopsAway?.let { if (it == 0) "Direct" else "$it" } ?: "Unknown"))
    node.snr?.let { add("Signal to noise" to "%.1f dB".format(it)) }
    node.rssi?.takeIf { it != 0 }?.let { add("Signal strength" to "$it dBm") }
    node.batteryLevel?.let {
        add("Battery" to if (it > 100) "Powered from mains" else "$it%")
    }
    node.voltage?.takeIf { it > 0f }?.let { add("Voltage" to "%.2f V".format(it)) }
    node.channelUtilization?.let { add("Channel busy" to "%.1f%%".format(it)) }
    node.airUtilTx?.let { add("Air time sending" to "%.1f%%".format(it)) }
    if (node.hasPosition) {
        add("Position" to "%.5f, %.5f".format(node.latitude, node.longitude))
        node.altitude?.let { add("Altitude" to "$it m") }
        node.groundSpeed?.takeIf { it > 0 }?.let { add("Speed" to "$it km/h") }
        node.groundTrack?.let { add("Heading" to "$it°") }
    }
    add("Encryption key" to if (node.publicKey.isNullOrBlank()) "Not shared" else "Shared")
    // The only claim on a mesh that cannot be forged by typing a name.
    KeyFingerprint.of(node.publicKey)?.let { add("Key fingerprint" to it) }
    if (node.isUnmessagable) add("Messages" to "Does not accept them")
    add("Last heard" to (node.lastHeard?.let { shortAge(it) } ?: "Not heard yet"))
}

/** Values meant to be compared character by character, not read as words. */
private val MONOSPACE = setOf("Node ID", "Key fingerprint")
