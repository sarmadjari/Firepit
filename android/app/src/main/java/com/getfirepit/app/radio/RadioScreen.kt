package com.getfirepit.app.radio

import com.getfirepit.core.protocol.MeshConstants
import com.getfirepit.core.model.MeshNode
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import android.text.format.DateUtils
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.transport.LinkState

/**
 * Stage 1 harness: scan, connect, and show what the config download returned.
 * Replaced by the real shell in Stage 3.
 */
@Composable
fun RadioScreen(modifier: Modifier = Modifier, viewModel: RadioViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        // Notifications are bundled into the same prompt but are not required
        // to scan, so denying them must not block the radio.
        val required = granted.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
        if (required.values.all { it }) viewModel.startScan()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(FirepitSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(FirepitSpacing.m),
    ) {
        Text("Firepit — radio link", style = MaterialTheme.typography.headlineLarge)
        LinkStatus(state.link)

        Row(horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.s)) {
            Button(onClick = { permissionLauncher.launch(blePermissions()) }) {
                Text(if (state.scanning) "Scanning…" else "Scan")
            }
            OutlinedButton(onClick = viewModel::disconnect) { Text("Disconnect") }
        }

        state.error?.let { message ->
            Text(message, color = FirepitTheme.colors.danger, style = MaterialTheme.typography.bodyMedium)
        }

        val details = state.details
        if (details == null) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s)) {
                items(state.found, key = { it.identifier }) { radio ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.connect(radio) },
                    ) {
                        Column(Modifier.padding(FirepitSpacing.m)) {
                            Text(radio.name ?: "(unnamed radio)", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${radio.identifier} · ${radio.rssi} dBm",
                                style = MaterialTheme.typography.bodySmall,
                                color = FirepitTheme.colors.textSecondary,
                            )
                        }
                    }
                }
            }
        } else {
            RadioDetailsView(details = details, state = state, onCheckPath = viewModel::checkPath)
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
private fun LinkStatus(link: LinkState) {
    val (label, color) = when (link) {
        LinkState.Disconnected -> "Not connected" to FirepitTheme.colors.stale
        is LinkState.Connecting -> "Connecting…" to FirepitTheme.colors.warn
        LinkState.Downloading -> "Reading your node…" to FirepitTheme.colors.warn
        is LinkState.Ready -> "Connected" to FirepitTheme.colors.live
        is LinkState.Reconnecting -> "Reconnecting (attempt ${link.attempt}) · ${link.cause}" to FirepitTheme.colors.warn
    }
    Text(label, color = color, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun RadioDetailsView(
    details: RadioDetails,
    state: RadioUiState,
    onCheckPath: (MeshNode) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.xs)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.xs)) {
                Field("Node", "${details.nodeId}  (${details.nodeNum})")
                Field("Firmware", details.firmware)
                Field("Hardware", details.hardware)
                Field("Region", details.region)
                Field("Reboots", details.rebootCount.toString())
                Field("PKI", details.capabilities.supportsPki.toString())
                Field("Signing (2.8)", details.capabilities.supportsSigning.toString())
                HorizontalDivider(Modifier.padding(vertical = FirepitSpacing.s))
                Text("Channels", style = MaterialTheme.typography.titleMedium)
            }
        }
        items(details.channels, key = { it.index }) { channel ->
            Field(
                "${channel.index}  ${channel.role}",
                "${channel.name}  precision ${channel.precision}",
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.xs)) {
                HorizontalDivider(Modifier.padding(vertical = FirepitSpacing.s))
                Text(
                    "Nodes (${state.nodes.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        items(state.nodes, key = { it.nodeNum }) { node ->
            NodeRow(
                node = node,
                isSelf = node.nodeNum == state.myNodeNum,
                tracing = state.tracing == node.nodeNum,
                enabled = state.tracing == null,
                onCheckPath = { onCheckPath(node) },
            )
        }
    }
}

@Composable
private fun NodeRow(
    node: MeshNode,
    isSelf: Boolean,
    tracing: Boolean,
    enabled: Boolean,
    onCheckPath: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = FirepitSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = if (isSelf) "${node.displayName} (this radio)" else node.displayName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = nodeDetail(node),
                style = MaterialTheme.typography.bodySmall,
                color = FirepitTheme.colors.textSecondary,
            )
        }
        if (!isSelf) {
            TextButton(onClick = onCheckPath, enabled = enabled) {
                Text(if (tracing) "Checking…" else "Check path")
            }
        }
    }
}

/** Nodes report their own last_heard and some have no clock, so an unheard node says so plainly. */
private fun nodeDetail(node: MeshNode): String = buildList {
    add(MeshConstants.formatNodeId(node.nodeNum))
    node.hopsAway?.let { add(if (it == 0) "direct" else "$it hops") }
    node.snr?.let { add("%.1f dB".format(it)) }
    // The firmware reports above 100 for a node running on mains, not a full battery.
    node.batteryLevel?.let { add(if (it > 100) "powered" else "$it%") }
    add(node.lastHeard?.let { "heard " + DateUtils.getRelativeTimeSpanString(it) } ?: "not heard yet")
}.joinToString(" · ")

@Composable
private fun Field(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = FirepitTheme.colors.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

private fun blePermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        // Android 10-11 return no scan results without location permission.
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    // Asked here because connecting follows immediately and the session runs
    // behind a persistent notification. Denying it only hides that notification.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()
