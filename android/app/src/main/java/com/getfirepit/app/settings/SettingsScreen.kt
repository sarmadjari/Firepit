package com.getfirepit.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.app.map.OfflineMapsScreen
import com.getfirepit.app.map.PinsScreen
import com.getfirepit.app.radio.DevicesScreen
import com.getfirepit.app.radio.NodesScreen
import com.getfirepit.app.radio.RadioViewModel
import androidx.compose.material3.Switch
import com.getfirepit.core.designsystem.component.FirepitChip
import com.getfirepit.core.designsystem.component.FirepitTopBar
import com.getfirepit.core.designsystem.component.IdentityAvatar
import com.getfirepit.core.designsystem.component.SectionLabel
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.designsystem.theme.IDENTITY_CHOICES
import com.getfirepit.core.designsystem.theme.identityColorFor
import com.getfirepit.core.designsystem.theme.identityColorForSlot
import com.getfirepit.core.designsystem.theme.onIdentityColorFor
import com.getfirepit.core.protocol.MessageRetention
import com.getfirepit.core.protocol.RadioCapabilities
import com.getfirepit.core.protocol.RoomLifetime
import com.getfirepit.core.protocol.OwnerName
import com.getfirepit.core.protocol.Person
import com.getfirepit.core.transport.LinkState

enum class SettingsSection { DEVICES, NODES, OFFLINE_MAPS, PINS }

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    openSection: SettingsSection? = null,
    onSectionOpened: () -> Unit = {},
    onImmersiveChange: (Boolean) -> Unit = {},
    radioViewModel: RadioViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    var section by remember { mutableStateOf<SettingsSection?>(null) }
    val radioState by radioViewModel.uiState.collectAsStateWithLifecycle()
    val theme by settingsViewModel.theme.collectAsStateWithLifecycle()
    val person by settingsViewModel.person.collectAsStateWithLifecycle()
    val connected by settingsViewModel.connected.collectAsStateWithLifecycle()
    val retention by settingsViewModel.retentionChoice.collectAsStateWithLifecycle()
    val roomLifetime by settingsViewModel.roomLifetime.collectAsStateWithLifecycle()
    val showMessageText by settingsViewModel.showMessageText.collectAsStateWithLifecycle()
    val renameError by settingsViewModel.renameError.collectAsStateWithLifecycle()

    // A sub-screen takes the whole display, same as an open chat does.
    LaunchedEffect(section) { onImmersiveChange(section != null) }
    BackHandler(enabled = section != null) { section = null }

    // Asked for from another tab, and cleared once taken so asking twice works.
    LaunchedEffect(openSection) {
        openSection?.let {
            section = it
            onSectionOpened()
        }
    }

    when (section) {
        SettingsSection.DEVICES -> DevicesScreen(
            modifier = modifier.fillMaxSize(),
            onBack = { section = null },
            viewModel = radioViewModel,
        )

        SettingsSection.NODES -> NodesScreen(
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
            deviceSummary = radioState.link.summary(),
            nodeSummary = when {
                radioState.nodes.isEmpty() -> "Nobody heard yet"
                else -> "${radioState.nodes.size} heard on the mesh"
            },
            theme = theme,
            person = person,
            connected = connected,
            retention = retention,
            onChooseRetention = settingsViewModel::chooseRetention,
            roomLifetime = roomLifetime,
            onChooseRoomLifetime = settingsViewModel::chooseRoomLifetime,
            showMessageText = showMessageText,
            onShowMessageText = settingsViewModel::setShowMessageText,
            onSavePerson = settingsViewModel::savePerson,
            onUseAsNodeName = settingsViewModel::useAsNodeName,
            onChooseIdentity = settingsViewModel::chooseIdentitySlot,
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
    deviceSummary: String,
    nodeSummary: String,
    theme: ThemeChoice,
    person: Person?,
    connected: Boolean,
    retention: MessageRetention,
    onChooseRetention: (MessageRetention) -> Unit,
    roomLifetime: RoomLifetime,
    onChooseRoomLifetime: (RoomLifetime) -> Unit,
    showMessageText: Boolean,
    onShowMessageText: (Boolean) -> Unit,
    onSavePerson: (String, String) -> Unit,
    onUseAsNodeName: () -> Unit,
    onChooseIdentity: (Int?) -> Unit,
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
            PersonFields(
                person = person,
                connected = connected,
                onSave = onSavePerson,
                onUseAsNodeName = onUseAsNodeName,
                onChooseIdentity = onChooseIdentity,
            )
            HorizontalDivider()

            SectionLabel("Radio")
            ListItem(
                headlineContent = { Text("Devices") },
                supportingContent = { Text(deviceSummary) },
                modifier = Modifier.clickable { onOpen(SettingsSection.DEVICES) },
            )
            ListItem(
                headlineContent = { Text("Nodes") },
                supportingContent = { Text(nodeSummary) },
                modifier = Modifier.clickable { onOpen(SettingsSection.NODES) },
            )
            HorizontalDivider()

            // Grouped by the question being asked — "how do I hear about a
            // message" — rather than by which device the answer is written to.
            SectionLabel("Notifications")
            SettingsGroup {
                SettingRow(
                    label = "Show message text",
                    caption = "Off shows only who it is from. A notification is read by " +
                        "whoever is looking at the phone, which is not always you.",
                ) {
                    Switch(checked = showMessageText, onCheckedChange = onShowMessageText)
                }
            }
            HorizontalDivider()

            SectionLabel("Messages")
            SettingsGroup {
                SettingsChoice(
                    label = "Keep messages for",
                    caption = "Older messages are always deleted from this phone — there is no " +
                        "keeping them. Everyone else holds their own copy, and nothing on a " +
                        "mesh can delete theirs.",
                    entries = MessageRetention.entries,
                    selected = retention,
                    labelOf = MessageRetention::label,
                    onChoose = onChooseRetention,
                )

                SettingsChoice(
                    label = "Leave quiet rooms",
                    caption = "Leaving takes the room's messages and its key with it, and " +
                        "cannot be undone.",
                    entries = RoomLifetime.entries,
                    selected = roomLifetime,
                    labelOf = RoomLifetime::label,
                    onChoose = onChooseRoomLifetime,
                )
            }
            HorizontalDivider()

            SectionLabel("Map")
            ListItem(
                headlineContent = { Text("Offline areas") },
                supportingContent = { Text("Download map tiles so the map works with no signal") },
                modifier = Modifier.clickable { onOpen(SettingsSection.OFFLINE_MAPS) },
            )
            ListItem(
                headlineContent = { Text("Dropped pins") },
                supportingContent = { Text("Rename or delete the pins on your map") },
                modifier = Modifier.clickable { onOpen(SettingsSection.PINS) },
            )
            HorizontalDivider()

            SectionLabel("Appearance")
            SettingsGroup {
                SettingsChoice(
                    label = "Theme",
                    caption = "Dark keeps a torch-lit camp readable and does not flare in " +
                        "your eyes at night.",
                    entries = ThemeChoice.entries,
                    selected = theme,
                    labelOf = ThemeChoice::label,
                    onChoose = onChooseTheme,
                )
            }
            HorizontalDivider()

            SectionLabel("About")
            ListItem(
                headlineContent = { Text("Firepit") },
                supportingContent = { Text("Meshtastic chat, rooms and maps that work off-grid") },
            )
            ListItem(
                headlineContent = { Text("Works with") },
                supportingContent = {
                    Text(
                        "Meshtastic radios running firmware " +
                            "${RadioCapabilities.MINIMUM_FIRMWARE} or newer. An older node is " +
                            "refused rather than half-supported, because the privacy Firepit " +
                            "describes would not hold on it.",
                    )
                },
            )
        }
    }
}

/**
 * The inset a settings block sits in.
 *
 * A `ListItem` brings its own padding; a hand-built control does not, and every
 * one of these was previously indenting itself by eye.
 */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.padding(
            horizontal = FirepitSpacing.screenMargin,
            vertical = FirepitSpacing.s,
        ),
        verticalArrangement = Arrangement.spacedBy(FirepitSpacing.m),
        content = content,
    )
}

/**
 * One question, its answers, and why it matters.
 *
 * Every chip setting in the app is this shape, so picking a theme and picking
 * how long to keep messages read as the same gesture.
 */
@Composable
private fun <T> SettingsChoice(
    label: String,
    entries: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onChoose: (T) -> Unit,
    caption: String? = null,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.xs)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs)) {
            entries.forEach { entry ->
                FirepitChip(
                    label = labelOf(entry),
                    selected = selected == entry,
                    enabled = enabled,
                    onClick = { onChoose(entry) },
                )
            }
        }
        caption?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = FirepitTheme.colors.textSecondary,
            )
        }
    }
}

/** A setting whose answer is a control rather than a set of chips. */
@Composable
private fun SettingRow(
    label: String,
    caption: String? = null,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FirepitSpacing.xs),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            caption?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = FirepitTheme.colors.textSecondary,
                )
            }
        }
        control()
    }
}

/**
 * You.
 *
 * Only on this phone, and deliberately so: a name here costs nothing, needs no
 * radio, and cannot be truncated by one. The node keeps its own name.
 */
@Composable
private fun PersonFields(
    person: Person?,
    connected: Boolean,
    onSave: (String, String) -> Unit,
    onUseAsNodeName: () -> Unit,
    onChooseIdentity: (Int?) -> Unit,
) {
    var pickingColour by remember { mutableStateOf(false) }
    var name by rememberSaveable(person) { mutableStateOf(person?.name.orEmpty()) }
    var tag by rememberSaveable(person) { mutableStateOf(person?.tag.orEmpty()) }
    // Nothing records whether a stored tag was typed or derived, so ask the
    // rule: initials it would not have produced were chosen deliberately.
    var tagChosen by rememberSaveable(person) {
        mutableStateOf(person != null && person.tag != Person.initialsFor(person.name))
    }

    // An emptied field falls back to the name rather than being rewritten as
    // you delete, which would move the cursor out from under you.
    val effectiveTag = tag.ifBlank { Person.initialsFor(name) }
    val changed = name.trim() != person?.name.orEmpty() || effectiveTag != person?.tag.orEmpty()
    val nameBytes = name.toByteArray(Charsets.UTF_8).size
    val tagBytes = tag.toByteArray(Charsets.UTF_8).size
    val tooLong = nameBytes > OwnerName.MAX_LONG_BYTES || tagBytes > OwnerName.MAX_SHORT_BYTES

    Column(
        modifier = Modifier.padding(
            horizontal = FirepitSpacing.screenMargin,
            vertical = FirepitSpacing.s,
        ),
        verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                if (!tagChosen) tag = Person.initialsFor(it)
            },
            label = { Text("Your name") },
            singleLine = true,
            supportingText = {
                Text(
                    text = if (nameBytes > OwnerName.MAX_LONG_BYTES) {
                        "Longer than a mesh packet can carry by ${nameBytes - OwnerName.MAX_LONG_BYTES} bytes"
                    } else {
                        "How this phone refers to you. The mesh sees your device's name"
                    },
                    color = if (nameBytes > OwnerName.MAX_LONG_BYTES) {
                        FirepitTheme.colors.danger
                    } else {
                        FirepitTheme.colors.textSecondary
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.m),
        ) {
            OutlinedTextField(
                value = tag,
                onValueChange = {
                    tag = it
                    tagChosen = it.isNotBlank()
                },
                label = { Text("Initials") },
                singleLine = true,
                supportingText = {
                    Text(
                        text = if (tagBytes > OwnerName.MAX_SHORT_BYTES) {
                            "Up to ${OwnerName.MAX_SHORT_BYTES} characters"
                        } else if (tag.isBlank()) {
                            "Empty follows your name: $effectiveTag"
                        } else if (tagChosen) {
                            "Yours. Clear it to follow your name again"
                        } else {
                            "Follows your name. Type your own if you prefer"
                        },
                        color = if (tagBytes > OwnerName.MAX_SHORT_BYTES) {
                            FirepitTheme.colors.danger
                        } else {
                            FirepitTheme.colors.textSecondary
                        },
                    )
                },
                modifier = Modifier.weight(1f),
            )

            // Beside the initials because the two make one thing: the dot.
            Box(
                modifier = Modifier
                    .padding(top = FirepitSpacing.s)
                    .clip(CircleShape)
                    .clickable { pickingColour = true }
                    .semantics { contentDescription = "Change your colour" },
            ) {
                IdentityAvatar(
                    nodeNum = person?.id ?: 0,
                    tag = effectiveTag,
                    name = name,
                    size = 56.dp,
                    slot = person?.colourSlot,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.s)) {
            Button(
                onClick = { onSave(name, effectiveTag) },
                enabled = changed && !tooLong && name.isNotBlank(),
            ) {
                Text("Save")
            }
            // Offered rather than done: this is the one action here that leaves
            // the phone and reconfigures hardware.
            TextButton(
                onClick = onUseAsNodeName,
                enabled = connected && person != null && !changed,
            ) {
                Text("Use on my radio")
            }
        }
        Text(
            text = if (connected) {
                "Others see your device's name, not this one. Copy it across if you want to match."
            } else {
                "Others see your device's name, not this one."
            },
            style = MaterialTheme.typography.bodySmall,
            color = FirepitTheme.colors.textSecondary,
        )
    }

    if (pickingColour) {
        IdentityColourDialog(
            nodeNum = person?.id ?: 0,
            tag = tag,
            name = name,
            chosen = person?.colourSlot,
            onChoose = onChooseIdentity,
            onDismiss = { pickingColour = false },
        )
    }
}

/**
 * Your colour.
 *
 * Only on this phone: the hue everyone else draws you in comes from your node
 * number, which is how every device agrees without asking each other.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdentityColourDialog(
    nodeNum: Int,
    tag: String?,
    name: String,
    chosen: Int?,
    onChoose: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your colour") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.m)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.m),
                    verticalArrangement = Arrangement.spacedBy(FirepitSpacing.m),
                ) {
                    IDENTITY_CHOICES.forEach { slot ->
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .then(
                                    if (slot == chosen) {
                                        Modifier.border(3.dp, FirepitTheme.colors.textPrimary, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable {
                                    onChoose(slot)
                                    onDismiss()
                                },
                        ) {
                            IdentityAvatar(
                                nodeNum = nodeNum,
                                tag = tag,
                                name = name,
                                size = 52.dp,
                                slot = slot,
                            )
                        }
                    }
                }

                Text(
                    text = "Only you see this. Everyone else draws you in the colour from your " +
                        "node number, which is how every device agrees without asking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FirepitTheme.colors.textSecondary,
                )
            }
        },
        confirmButton = {
            if (chosen != null) {
                TextButton(
                    onClick = {
                        onChoose(null)
                        onDismiss()
                    },
                ) {
                    Text("Use my node's colour")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun LinkState.summary(): String = when (this) {
    is LinkState.Ready -> "Connected"
    LinkState.Downloading -> "Reading settings…"
    is LinkState.Connecting -> "Connecting…"
    is LinkState.Reconnecting -> "Reconnecting…"
    is LinkState.Unsupported ->
        "Not connected · firmware ${version ?: "unknown"} is older than " +
            "${RadioCapabilities.MINIMUM_FIRMWARE}"
    LinkState.Disconnected -> "Not connected"
}
