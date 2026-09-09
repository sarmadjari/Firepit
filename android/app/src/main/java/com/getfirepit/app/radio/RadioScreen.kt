package com.getfirepit.app.radio

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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.app.chat.ChatSection
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
    ) { granted -> if (granted.values.all { it }) viewModel.startScan() }

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
            ChatSection(
                availableChannels = details.channels
                    .filter { it.role != "DISABLED" }
                    .map { it.index to it.name },
                modifier = Modifier.weight(1f),
            )
        }
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
private fun RadioDetailsView(details: RadioDetails) {
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
                Field("Known nodes", details.knownNodes.toString())
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
    }
}

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

private fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        // Android 10-11 return no scan results without location permission.
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
