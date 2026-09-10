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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.getfirepit.core.designsystem.component.FirepitIcons
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.saveable.rememberSaveable
import com.getfirepit.core.data.Owner
import com.getfirepit.core.protocol.OwnerName
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.core.designsystem.component.BackButton
import com.getfirepit.core.designsystem.component.FirepitChip
import com.getfirepit.core.designsystem.component.FirepitDetailBar
import com.getfirepit.core.designsystem.component.IdentityAvatar
import com.getfirepit.core.designsystem.component.SectionLabel
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.protocol.MeshConstants
import com.getfirepit.core.protocol.DeviceTransport
import com.getfirepit.core.protocol.NodeRole
import com.getfirepit.core.protocol.SavedRadio
import com.getfirepit.core.transport.LinkState

/** The Meshtastic devices this phone can talk to, and which one it is using. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
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
            FirepitDetailBar(title = "Devices", onBack = onBack)
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
            val nearby = state.found.filter { found ->
                state.saved.none { it.identifier == found.identifier }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s)) {
                savedRadioSection(
                    saved = state.saved,
                    connectedTo = state.connectedTo,
                    onConnect = viewModel::connectSaved,
                    onRole = viewModel::setRole,
                    onForget = viewModel::forget,
                )
                if (nearby.isNotEmpty()) {
                    item(key = "nearby-label") { SectionLabel("Nearby") }
                }
                items(nearby, key = { it.identifier }) { radio ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.connect(radio) },
                    ) {
                        Column(Modifier.padding(FirepitSpacing.m)) {
                            Text(
                                text = radio.name ?: "(unnamed device)",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "${DeviceTransport.BLUETOOTH.label} · ${radio.identifier} · ${radio.rssi} dBm",
                                style = MaterialTheme.typography.bodySmall,
                                color = FirepitTheme.colors.textSecondary,
                            )
                        }
                    }
                }
                if (details != null) {
                    connectedDeviceSection(
                        details = details,
                        owner = state.owner,
                        onRename = viewModel::renameNode,
                    )
                }
            }
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

private fun LazyListScope.connectedDeviceSection(
    details: RadioDetails,
    owner: Owner?,
    onRename: (String, String) -> Unit,
) {
    item(key = "connected-details") {
        Column(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.xs)) {
            SectionLabel("Connected device")
            NodeNameFields(owner = owner, onRename = onRename)
            Field("Node", details.nodeId, monospace = true)
            Field("Firmware", details.firmware)
            Field("Hardware", prettyName(details.hardware))
            Field("Region", details.region.replace('_', ' '))
            Field("Encryption keys", if (details.capabilities.supportsPki) "Yes" else "No")
            Field("Signed messages", if (details.capabilities.supportsSigning) "Yes" else "No")
            HorizontalDivider(Modifier.padding(vertical = FirepitSpacing.s))
            SectionLabel("Channels")
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
}

/** WISMESH_TAG reads as shouting; the radio's own name does not. */
fun prettyName(raw: String): String = raw
    .split('_')
    .filter { it.isNotBlank() }
    .joinToString(" ") { part -> part.lowercase().replaceFirstChar(Char::uppercase) }

/** Nodes report their own last_heard and some have no clock, so an unheard node says so plainly. */
fun nodeDetail(node: MeshNode): String = buildList {
    add(MeshConstants.formatNodeId(node.nodeNum))
    node.hopsAway?.let { add(if (it == 0) "direct" else "$it hops") }
    node.snr?.let { add("%.1f dB".format(it)) }
    // The firmware reports above 100 for a node running on mains, not a full battery.
    node.batteryLevel?.let { add(if (it > 100) "powered" else "$it%") }
    add(node.lastHeard?.let { "heard ${shortAge(it)}" } ?: "not heard yet")
}.joinToString(" · ")

/** Compact enough to sit on one line beside everything else. */
fun shortAge(epochMillis: Long): String {
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
fun Field(label: String, value: String, monospace: Boolean = false) {
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

/**
 * A radio this phone administers. Tapping the card reconnects to it; the chips
 * say what the radio is for, and change it in one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedRadioRow(
    radio: SavedRadio,
    connected: Boolean,
    onConnect: () -> Unit,
    onRole: (NodeRole) -> Unit,
    onForget: () -> Unit,
) {
    // Forgetting is irreversible and sits a thumb's width from the row you tap
    // to connect, so it asks first.
    var confirming by remember { mutableStateOf(false) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Forget ${radio.name}?") },
            text = {
                Text(
                    "Firepit will stop administering this radio. The radio keeps " +
                        "its settings, and you can add it again by scanning.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onForget() }) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Keep") }
            },
        )
    }
    Card(modifier = Modifier.fillMaxWidth(), onClick = onConnect) {
        Column(Modifier.padding(FirepitSpacing.m)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
            ) {
                RoleBadge(radio.role)
                Text(
                    text = radio.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { confirming = true }) { Text("Forget") }
            }
            Text(
                text = listOfNotNull(
                    radio.transport.label,
                    radio.identifier,
                    "Connected".takeIf { connected },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (connected) {
                    FirepitTheme.colors.live
                } else {
                    FirepitTheme.colors.textSecondary
                },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs),
                modifier = Modifier.padding(top = FirepitSpacing.s),
            ) {
                NodeRole.entries.forEach { role ->
                    FirepitChip(
                        label = role.label,
                        selected = radio.role == role,
                        onClick = { onRole(role) },
                    )
                }
            }
        }
    }
}

/**
 * The devices this phone administers.
 *
 * A [LazyListScope] extension rather than a composable so it can sit inside
 * either list, and stay visible while a device is connected — roles are worth
 * changing most when you are standing next to the radio you just connected to.
 */
private fun LazyListScope.savedRadioSection(
    saved: List<SavedRadio>,
    connectedTo: String?,
    onConnect: (SavedRadio) -> Unit,
    onRole: (SavedRadio, NodeRole) -> Unit,
    onForget: (SavedRadio) -> Unit,
) {
    if (saved.isEmpty()) return
    item(key = "saved-label") { SectionLabel("Your devices") }
    items(saved, key = { it.identifier }) { radio ->
        SavedRadioRow(
            radio = radio,
            connected = connectedTo == radio.identifier,
            onConnect = { onConnect(radio) },
            onRole = { role -> onRole(radio, role) },
            onForget = { onForget(radio) },
        )
    }
}

/** What the device is for, at a glance, without reading all three chips. */
@Composable
private fun RoleBadge(role: NodeRole) {
    val icon = when (role) {
        NodeRole.PERSONAL -> FirepitIcons.RolePersonal
        NodeRole.BASE -> FirepitIcons.RoleBase
        NodeRole.ROUTER -> FirepitIcons.RoleRouter
    }
    // Infrastructure reads as infrastructure; the one you carry reads as you.
    val tint = if (role == NodeRole.PERSONAL) {
        FirepitTheme.colors.textPrimary
    } else {
        FirepitTheme.colors.infra
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(FirepitTheme.colors.surface2, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = role.label,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * What this radio calls itself.
 *
 * Device configuration, not personal identity: it is what people running the
 * official app see, since nothing outside Firepit knows a person from a radio.
 */
@Composable
private fun NodeNameFields(owner: Owner?, onRename: (String, String) -> Unit) {
    var longName by rememberSaveable(owner) { mutableStateOf(owner?.longName.orEmpty()) }
    var shortName by rememberSaveable(owner) { mutableStateOf(owner?.shortName.orEmpty()) }

    val changed = owner != null &&
        (longName.trim() != owner.longName || shortName.trim() != owner.shortName)
    val tooLong = !OwnerName.fits(longName.trim(), shortName.trim())

    Column(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s)) {
        OutlinedTextField(
            value = longName,
            onValueChange = { longName = it },
            label = { Text("Node name") },
            singleLine = true,
            isError = tooLong,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = shortName,
            onValueChange = { shortName = it },
            label = { Text("Node tag") },
            singleLine = true,
            isError = tooLong,
            supportingText = {
                Text(
                    text = if (tooLong) {
                        "The radio allows ${OwnerName.MAX_LONG_BYTES} and " +
                            "${OwnerName.MAX_SHORT_BYTES} bytes"
                    } else {
                        "Stored on the radio, and seen by apps that are not Firepit"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tooLong) {
                        FirepitTheme.colors.danger
                    } else {
                        FirepitTheme.colors.textSecondary
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onRename(longName, shortName) },
            enabled = changed && !tooLong && longName.isNotBlank(),
        ) {
            Text("Rename device")
        }
        HorizontalDivider(Modifier.padding(vertical = FirepitSpacing.s))
    }
}
