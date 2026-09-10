package com.getfirepit.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.app.map.OfflineMapsScreen
import com.getfirepit.app.map.PinsScreen
import com.getfirepit.app.radio.RadioScreen
import com.getfirepit.app.radio.RadioViewModel
import com.getfirepit.core.data.Owner
import com.getfirepit.core.designsystem.component.FirepitChip
import com.getfirepit.core.designsystem.component.FirepitTopBar
import com.getfirepit.core.designsystem.component.SectionLabel
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.protocol.OwnerName
import com.getfirepit.core.transport.LinkState

private enum class SettingsSection { NODES, OFFLINE_MAPS, PINS }

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onImmersiveChange: (Boolean) -> Unit = {},
    radioViewModel: RadioViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    var section by remember { mutableStateOf<SettingsSection?>(null) }
    val radioState by radioViewModel.uiState.collectAsStateWithLifecycle()
    val theme by settingsViewModel.theme.collectAsStateWithLifecycle()
    val owner by settingsViewModel.owner.collectAsStateWithLifecycle()
    val connected by settingsViewModel.connected.collectAsStateWithLifecycle()
    val renameError by settingsViewModel.renameError.collectAsStateWithLifecycle()

    // A sub-screen takes the whole display, same as an open chat does.
    LaunchedEffect(section) { onImmersiveChange(section != null) }
    BackHandler(enabled = section != null) { section = null }

    when (section) {
        SettingsSection.NODES -> RadioScreen(
            modifier = modifier.fillMaxSize(),
            onBack = { section = null },
            viewModel = radioViewModel,
        )

        SettingsSection.OFFLINE_MAPS -> OfflineMapsScreen(
            modifier = modifier,
            onBack = { section = null },
        )

        SettingsSection.PINS -> PinsScreen(
            modifier = modifier,
            onBack = { section = null },
        )

        null -> SettingsList(
            modifier = modifier,
            nodeSummary = radioState.link.summary(),
            theme = theme,
            owner = owner,
            connected = connected,
            onRename = settingsViewModel::rename,
            onChooseTheme = settingsViewModel::chooseTheme,
            onOpen = { section = it },
        )
    }

    renameError?.let { message ->
        AlertDialog(
            onDismissRequest = settingsViewModel::clearRenameError,
            title = { Text("Could not save your name") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = settingsViewModel::clearRenameError) { Text("Close") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsList(
    modifier: Modifier,
    nodeSummary: String,
    theme: ThemeChoice,
    owner: Owner?,
    connected: Boolean,
    onRename: (String, String) -> Unit,
    onChooseTheme: (ThemeChoice) -> Unit,
    onOpen: (SettingsSection) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = { FirepitTopBar(title = "Settings") },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionLabel("You")
            OwnerFields(
                owner = owner,
                connected = connected,
                onSave = onRename,
            )
            HorizontalDivider()

            SectionLabel("Radio")
            ListItem(
                headlineContent = { Text("Nodes") },
                supportingContent = { Text(nodeSummary) },
                modifier = Modifier.clickable { onOpen(SettingsSection.NODES) },
            )
            HorizontalDivider()

            SectionLabel("Map")
            ListItem(
                headlineContent = { Text("Offline areas") },
                supportingContent = { Text("Download map tiles so the map works with no signal") },
                modifier = Modifier.clickable { onOpen(SettingsSection.OFFLINE_MAPS) },
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Dropped pins") },
                supportingContent = { Text("Rename or delete the pins on your map") },
                modifier = Modifier.clickable { onOpen(SettingsSection.PINS) },
            )
            HorizontalDivider()

            SectionLabel("Appearance")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = FirepitSpacing.screenMargin,
                        vertical = FirepitSpacing.s,
                    ),
                horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
            ) {
                ThemeChoice.entries.forEach { option ->
                    FirepitChip(
                        label = option.label,
                        selected = theme == option,
                        onClick = { onChooseTheme(option) },
                    )
                }
            }
            Text(
                text = "Dark keeps a torch-lit camp readable and does not flare in your eyes at night.",
                style = MaterialTheme.typography.bodySmall,
                color = FirepitTheme.colors.textSecondary,
                modifier = Modifier.padding(
                    horizontal = FirepitSpacing.screenMargin,
                    vertical = FirepitSpacing.xs,
                ),
            )
            HorizontalDivider()

            SectionLabel("About")
            ListItem(
                headlineContent = { Text("Firepit") },
                supportingContent = { Text("Meshtastic chat, rooms and maps that work off-grid") },
            )
        }
    }
}

/**
 * Your name, as the rest of the mesh sees it.
 *
 * Stored on the radio rather than the phone, so it travels with the node and
 * appears in other people's chats and maps.
 */
@Composable
private fun OwnerFields(
    owner: Owner?,
    connected: Boolean,
    onSave: (String, String) -> Unit,
) {
    var longName by rememberSaveable(owner) { mutableStateOf(owner?.longName.orEmpty()) }
    var shortName by rememberSaveable(owner) { mutableStateOf(owner?.shortName.orEmpty()) }

    val changed = owner != null &&
        (longName.trim() != owner.longName || shortName.trim() != owner.shortName)
    val longBytes = longName.toByteArray(Charsets.UTF_8).size
    val shortBytes = shortName.toByteArray(Charsets.UTF_8).size
    val tooLong = longBytes > OwnerName.MAX_LONG_BYTES || shortBytes > OwnerName.MAX_SHORT_BYTES

    Column(
        modifier = Modifier.padding(
            horizontal = FirepitSpacing.screenMargin,
            vertical = FirepitSpacing.s,
        ),
        verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
    ) {
        OutlinedTextField(
            value = longName,
            onValueChange = { longName = it },
            label = { Text("Name") },
            singleLine = true,
            enabled = connected,
            supportingText = {
                Text(
                    text = if (longBytes > OwnerName.MAX_LONG_BYTES) {
                        "Too long for the radio by ${longBytes - OwnerName.MAX_LONG_BYTES} bytes"
                    } else {
                        "Shown wherever you appear"
                    },
                    color = if (longBytes > OwnerName.MAX_LONG_BYTES) {
                        FirepitTheme.colors.danger
                    } else {
                        FirepitTheme.colors.textSecondary
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = shortName,
            onValueChange = { shortName = it },
            label = { Text("Short tag") },
            singleLine = true,
            enabled = connected,
            supportingText = {
                Text(
                    text = if (shortBytes > OwnerName.MAX_SHORT_BYTES) {
                        "The radio allows ${OwnerName.MAX_SHORT_BYTES} bytes"
                    } else {
                        "Up to ${OwnerName.MAX_SHORT_BYTES} characters, for avatars and map pins"
                    },
                    color = if (shortBytes > OwnerName.MAX_SHORT_BYTES) {
                        FirepitTheme.colors.danger
                    } else {
                        FirepitTheme.colors.textSecondary
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.s)) {
            Button(
                onClick = { onSave(longName, shortName) },
                enabled = connected && changed && !tooLong && longName.isNotBlank(),
            ) {
                Text("Save")
            }
            if (shortName.isBlank() && longName.isNotBlank()) {
                TextButton(onClick = { shortName = OwnerName.suggestShort(longName) }) {
                    Text("Suggest tag")
                }
            }
        }

        if (!connected) {
            Text(
                text = "Connect your node to change this — the name lives on the radio.",
                style = MaterialTheme.typography.bodySmall,
                color = FirepitTheme.colors.textSecondary,
            )
        }
    }
}

private fun LinkState.summary(): String = when (this) {
    is LinkState.Ready -> "Connected"
    LinkState.Downloading -> "Reading settings…"
    is LinkState.Connecting -> "Connecting…"
    is LinkState.Reconnecting -> "Reconnecting…"
    LinkState.Disconnected -> "Not connected"
}
