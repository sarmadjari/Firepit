package com.getfirepit.app.radio

import android.Manifest
import android.os.Build
import android.text.format.DateUtils
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.core.designsystem.component.BackButton
import com.getfirepit.core.designsystem.component.IdentityAvatar
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.protocol.MeshConstants
import com.getfirepit.core.transport.LinkState

/** Connect a radio, and show what it and the mesh around it look like. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: RadioViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        // Notifications are bundled into the same prompt but are not required
        // to scan, so denying them must not block the radio.
        val required = granted.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
        if (required.values.all { it }) viewModel.startScan()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Radio") },
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = FirepitSpacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(FirepitSpacing.m),
        ) {
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
                                Text(
                                    text = radio.name ?: "(unnamed radio)",
                                    style = MaterialTheme.typography.titleMedium,
                                )
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
                Field("Node", details.nodeId, monospace = true)
                Field("Firmware", details.firmware)
                Field("Hardware", prettyName(details.hardware))
                Field("Region", details.region.replace('_', ' '))
                Field("Encryption keys", if (details.capabilities.supportsPki) "Yes" else "No")
                Field("Signed messages", if (details.capabilities.supportsSigning) "Yes" else "No")
                HorizontalDivider(Modifier.padding(vertical = FirepitSpacing.s))
                Text("Channels", style = MaterialTheme.typography.titleMedium)
            }
        }
        // Empty slots are the radio's business, not the reader's: six lines of
        // DISABLED buried the two channels actually in use.
        items(details.channels.filter { it.role != "DISABLED" }, key = { it.index }) { channel ->
            Field(
                "${channel.index}  ${channel.role.lowercase().replaceFirstChar(Char::uppercase)}",
                channel.name,
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
                text = if (isSelf) "${node.displayName} (this radio)" else node.displayName,
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
}

/** WISMESH_TAG reads as shouting; the radio's own name does not. */
private fun prettyName(raw: String): String = raw
    .split('_')
    .filter { it.isNotBlank() }
    .joinToString(" ") { part -> part.lowercase().replaceFirstChar(Char::uppercase) }

/** Nodes report their own last_heard and some have no clock, so an unheard node says so plainly. */
private fun nodeDetail(node: MeshNode): String = buildList {
    add(MeshConstants.formatNodeId(node.nodeNum))
    node.hopsAway?.let { add(if (it == 0) "direct" else "$it hops") }
    node.snr?.let { add("%.1f dB".format(it)) }
    // The firmware reports above 100 for a node running on mains, not a full battery.
    node.batteryLevel?.let { add(if (it > 100) "powered" else "$it%") }
    add(node.lastHeard?.let { "heard ${shortAge(it)}" } ?: "not heard yet")
}.joinToString(" · ")

/** Compact enough to sit on one line beside everything else. */
private fun shortAge(epochMillis: Long): String {
    val minutes = (System.currentTimeMillis() - epochMillis) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)}d ago"
        else -> "long ago"
    }
}

@Composable
private fun Field(label: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = FirepitTheme.colors.textSecondary)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            // Only for things read out character by character.
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
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
