package com.getfirepit.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.app.map.OfflineMapsScreen
import com.getfirepit.app.map.PinsScreen
import com.getfirepit.app.radio.RadioScreen
import com.getfirepit.app.radio.RadioViewModel
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.transport.LinkState

private enum class SettingsSection { NODES, OFFLINE_MAPS, PINS }

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onImmersiveChange: (Boolean) -> Unit = {},
    radioViewModel: RadioViewModel = hiltViewModel(),
) {
    var section by remember { mutableStateOf<SettingsSection?>(null) }
    val radioState by radioViewModel.uiState.collectAsStateWithLifecycle()

    // A sub-screen takes the whole display, same as an open chat does.
    LaunchedEffect(section) { onImmersiveChange(section != null) }
    BackHandler(enabled = section != null) { section = null }

    when (section) {
        SettingsSection.NODES -> RadioScreen(
            modifier = modifier.fillMaxSize(),
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
            onOpen = { section = it },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsList(modifier: Modifier, nodeSummary: String, onOpen: (SettingsSection) -> Unit) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Radio")
            ListItem(
                headlineContent = { Text("Nodes") },
                supportingContent = { Text(nodeSummary) },
                modifier = Modifier.clickable { onOpen(SettingsSection.NODES) },
            )
            HorizontalDivider()

            SectionHeader("Map")
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

            SectionHeader("About")
            ListItem(
                headlineContent = { Text("Firepit") },
                supportingContent = { Text("Meshtastic chat, rooms and maps that work off-grid") },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = FirepitTheme.colors.textSecondary,
        modifier = Modifier.padding(
            start = FirepitSpacing.screenMargin,
            top = FirepitSpacing.l,
            bottom = FirepitSpacing.xs,
        ),
    )
}

private fun LinkState.summary(): String = when (this) {
    is LinkState.Ready -> "Connected"
    LinkState.Downloading -> "Reading settings…"
    is LinkState.Connecting -> "Connecting…"
    is LinkState.Reconnecting -> "Reconnecting…"
    LinkState.Disconnected -> "Not connected"
}
