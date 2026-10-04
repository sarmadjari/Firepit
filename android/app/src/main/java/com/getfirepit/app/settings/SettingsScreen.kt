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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.app.location.ShareLocationSheet
import com.getfirepit.app.location.SharingRow
import com.getfirepit.app.location.SharingUiState
import com.getfirepit.app.location.SharingViewModel
import com.getfirepit.app.map.OfflineMapsScreen
import com.getfirepit.app.map.PinsScreen
import com.getfirepit.app.radio.DevicesScreen
import com.getfirepit.app.radio.NodesScreen
import com.getfirepit.app.radio.RadioViewModel
import androidx.compose.material3.Switch
import com.getfirepit.app.ui.ShellViewModel
import com.getfirepit.core.designsystem.adaptive.LocalWideScreen
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
import com.getfirepit.core.model.LayoutChoice
import com.getfirepit.core.model.MapSide
import com.getfirepit.core.model.PaneArrangement
import com.getfirepit.core.protocol.MessageAlerts
import com.getfirepit.core.protocol.MessageRetention
import com.getfirepit.core.protocol.RadioCapabilities
import com.getfirepit.core.protocol.RadioPrivacy
import com.getfirepit.core.protocol.RangeMode
import com.getfirepit.core.protocol.RoomLifetime
import com.getfirepit.core.protocol.OwnerName
import com.getfirepit.core.protocol.Person
import com.getfirepit.core.transport.LinkState

enum class SettingsSection { DEVICES, NODES, OFFLINE_MAPS, PINS, QUICK_REPLIES }

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    openSection: SettingsSection? = null,
    onSectionOpened: () -> Unit = {},
    onImmersiveChange: (Boolean) -> Unit = {},
    onMessage: (Int) -> Unit = {},
    /** Set while Settings covers two panes: its bar gets a back arrow that returns to them. */
    onClose: (() -> Unit)? = null,
    radioViewModel: RadioViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    sharingViewModel: SharingViewModel = hiltViewModel(),
    shellViewModel: ShellViewModel = hiltViewModel(),
) {
    var section by remember { mutableStateOf<SettingsSection?>(null) }
    var pickingRoom by remember { mutableStateOf(false) }
    val sharing by sharingViewModel.state.collectAsStateWithLifecycle()
    val radioState by radioViewModel.uiState.collectAsStateWithLifecycle()
    val theme by settingsViewModel.theme.collectAsStateWithLifecycle()
    val person by settingsViewModel.person.collectAsStateWithLifecycle()
    val connected by settingsViewModel.connected.collectAsStateWithLifecycle()
    val retention by settingsViewModel.retentionChoice.collectAsStateWithLifecycle()
    val roomLifetime by settingsViewModel.roomLifetime.collectAsStateWithLifecycle()
    val showMessageText by settingsViewModel.showMessageText.collectAsStateWithLifecycle()
    val allowScreenCapture by settingsViewModel.allowScreenCapture.collectAsStateWithLifecycle()
    val messageAlerts by settingsViewModel.messageAlerts.collectAsStateWithLifecycle()
    val renameError by settingsViewModel.renameError.collectAsStateWithLifecycle()
    val rangeMode by settingsViewModel.rangeMode.collectAsStateWithLifecycle()
    val radioPrivacy by settingsViewModel.radioPrivacy.collectAsStateWithLifecycle()
    val canRestoreRadio by settingsViewModel.canRestoreRadio.collectAsStateWithLifecycle()
    val layoutChoice by shellViewModel.choice.collectAsStateWithLifecycle()
    val quickReplies by settingsViewModel.quickReplies.collectAsStateWithLifecycle()

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
            onMessage = { peer ->
                // The conversation opens in Chats; coming back to Settings lands on the list, not here.
                section = null
                onMessage(peer)
            },
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

        SettingsSection.QUICK_REPLIES -> QuickRepliesScreen(
            replies = quickReplies,
            onChange = settingsViewModel::setQuickReplies,
            onReset = settingsViewModel::resetQuickReplies,
            onBack = { section = null },
            modifier = modifier,
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
            sharing = sharing,
            onOpenSharing = { pickingRoom = true },
            retention = retention,
            onChooseRetention = settingsViewModel::chooseRetention,
            roomLifetime = roomLifetime,
            onChooseRoomLifetime = settingsViewModel::chooseRoomLifetime,
            quickReplyCount = quickReplies.size,
            showMessageText = showMessageText,
            onShowMessageText = settingsViewModel::setShowMessageText,
            allowScreenCapture = allowScreenCapture,
            onAllowScreenCapture = settingsViewModel::setAllowScreenCapture,
            onEraseHistory = settingsViewModel::eraseHistory,
            messageAlerts = messageAlerts,
            onChooseMessageAlerts = settingsViewModel::chooseMessageAlerts,
            rangeMode = rangeMode,
            radioPrivacy = radioPrivacy,
            canRestoreRadio = canRestoreRadio,
            onChooseRange = settingsViewModel::chooseRange,
            onMakeRadioPrivate = settingsViewModel::makeRadioPrivate,
            onKeepRadioPublic = settingsViewModel::keepRadioPublic,
            onSavePerson = settingsViewModel::savePerson,
            onUseAsNodeName = settingsViewModel::useAsNodeName,
            onChooseIdentity = settingsViewModel::chooseIdentitySlot,
            onChooseTheme = settingsViewModel::chooseTheme,
            layoutChoice = layoutChoice.takeIf { LocalWideScreen.current?.canSplit == true },
            onArrange = shellViewModel::arrange,
            onMapSide = shellViewModel::setMapSide,
            onResetDivider = shellViewModel::resetDivider,
            onClose = onClose,
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

    if (pickingRoom) {
        ShareLocationSheet(
            state = sharing,
            onDismiss = { pickingRoom = false },
            onStop = {
                pickingRoom = false
                sharingViewModel.stop()
            },
            onShare = { roomId, choice ->
                pickingRoom = false
                sharingViewModel.share(roomId, choice)
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
    sharing: SharingUiState,
    onOpenSharing: () -> Unit,
    retention: MessageRetention,
    onChooseRetention: (MessageRetention) -> Unit,
    roomLifetime: RoomLifetime,
    onChooseRoomLifetime: (RoomLifetime) -> Unit,
    quickReplyCount: Int,
    showMessageText: Boolean,
    onShowMessageText: (Boolean) -> Unit,
    allowScreenCapture: Boolean,
    onAllowScreenCapture: (Boolean) -> Unit,
    onEraseHistory: () -> Unit,
    messageAlerts: MessageAlerts,
    onChooseMessageAlerts: (MessageAlerts) -> Unit,
    rangeMode: RangeMode,
    radioPrivacy: RadioPrivacy,
    canRestoreRadio: Boolean,
    onChooseRange: (RangeMode) -> Unit,
    onMakeRadioPrivate: () -> Unit,
    onKeepRadioPublic: () -> Unit,
    onSavePerson: (String, String) -> Unit,
    onUseAsNodeName: () -> Unit,
    onChooseIdentity: (Int?) -> Unit,
    onChooseTheme: (ThemeChoice) -> Unit,
    /** Null unless the window is wide enough for two panes: the choices would change nothing. */
    layoutChoice: LayoutChoice?,
    onArrange: (PaneArrangement) -> Unit,
    onMapSide: (MapSide) -> Unit,
    onResetDivider: () -> Unit,
    onClose: (() -> Unit)?,
    onOpen: (SettingsSection) -> Unit,
) {
    var confirmingErase by remember { mutableStateOf(false) }
    if (confirmingErase) {
        AlertDialog(
            onDismissRequest = { confirmingErase = false },
            title = { Text("Erase history?") },
            text = {
                Text(
                    "Deletes every message, pin, last-known position, name card and browsed map " +
                        "tile on this phone. Your rooms and downloaded areas stay. Everyone else " +
                        "keeps their own copy.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingErase = false
                        onEraseHistory()
                    },
                ) { Text("Erase") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingErase = false }) { Text("Keep") }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = { FirepitTopBar(title = "Settings", onBack = onClose) },
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
            RangeSettings(
                privacy = radioPrivacy,
                mode = rangeMode,
                canRestore = canRestoreRadio,
                onMakePrivate = onMakeRadioPrivate,
                onKeepPublic = onKeepRadioPublic,
                onChooseRange = onChooseRange,
            )
            HorizontalDivider()

            // Grouped by the question being asked — "how do I hear about a
            // message" — rather than by which device the answer is written to.
            SectionLabel("Notifications")
            SettingsGroup {
                SettingRow(
                    label = "Show who and what",
                    caption = "Off shows only that a message arrived, not who sent it, where or " +
                        "what it says. A notification is read by whoever is looking at the phone, " +
                        "which is not always you.",
                ) {
                    Switch(checked = showMessageText, onCheckedChange = onShowMessageText)
                }

                SettingsChoice(
                    label = "Announce a message on",
                    caption = if (connected) {
                        messageAlerts.summary
                    } else {
                        "Connect your radio to change this."
                    },
                    entries = MessageAlerts.entries,
                    selected = messageAlerts,
                    labelOf = MessageAlerts::label,
                    // A radio setting, so there must be a radio to write it to.
                    enabled = connected,
                    onChoose = onChooseMessageAlerts,
                )
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
            ListItem(
                headlineContent = { Text("Quick replies") },
                supportingContent = {
                    Text(if (quickReplyCount == 1) "1 ready to send with ⚡" else "$quickReplyCount ready to send with ⚡")
                },
                modifier = Modifier.clickable { onOpen(SettingsSection.QUICK_REPLIES) },
            )
            HorizontalDivider()

            SectionLabel("Privacy")
            SettingsGroup {
                SettingRow(
                    label = "Allow screenshots",
                    caption = "Off keeps conversations and the map out of screenshots, screen " +
                        "recordings and the recent-apps view. Invite codes are never captured.",
                ) {
                    Switch(checked = allowScreenCapture, onCheckedChange = onAllowScreenCapture)
                }
            }
            ListItem(
                headlineContent = { Text("Erase history on this phone") },
                supportingContent = {
                    Text("Messages, pins, where people were, their names, and the map tiles you looked at")
                },
                modifier = Modifier.clickable { confirmingErase = true },
            )
            HorizontalDivider()

            SectionLabel("Map")
            SharingRow(state = sharing, onClick = onOpenSharing)
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
            layoutChoice?.let { choice ->
                WideScreenSettings(
                    choice = choice,
                    onArrange = onArrange,
                    onMapSide = onMapSide,
                    onResetDivider = onResetDivider,
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

/** The physical side, as the setting names it, whatever the reading direction. */
private enum class PhysicalSide(val label: String) { RIGHT("Right"), LEFT("Left") }

/** Settings › Appearance › Wide screens (UX §6.11.4). */
@Composable
private fun WideScreenSettings(
    choice: LayoutChoice,
    onArrange: (PaneArrangement) -> Unit,
    onMapSide: (MapSide) -> Unit,
    onResetDivider: () -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val mapOnRight = (choice.mapSide == MapSide.END) != rtl
    SectionLabel("Wide screens")
    SettingsGroup {
        SettingsChoice(
            label = "Layout",
            caption = "When the screen is wide enough for two: unfolded, a tablet, or a wide window.",
            entries = PaneArrangement.entries,
            selected = choice.arrangement,
            labelOf = PaneArrangement::label,
            onChoose = onArrange,
        )
        SettingsChoice(
            label = "Map on the",
            entries = PhysicalSide.entries,
            selected = if (mapOnRight) PhysicalSide.RIGHT else PhysicalSide.LEFT,
            labelOf = PhysicalSide::label,
            onChoose = { side -> onMapSide(if ((side == PhysicalSide.RIGHT) != rtl) MapSide.END else MapSide.START) },
            enabled = choice.arrangement == PaneArrangement.CHAT_AND_MAP,
        )
    }
    ListItem(
        headlineContent = { Text("Reset the divider") },
        supportingContent = { Text("Back on the fold, or in the middle when there is no fold") },
        modifier = Modifier.clickable(onClick = onResetDivider),
    )
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
 * What this radio tells the world about itself, and how far its traffic goes.
 *
 * Two separate questions, and conflating them is what makes this confusing:
 * whether the radio's *own* identity is hidden, and which radios carry our
 * messages. Rooms are private regardless of both, which is said first so that
 * nobody reads this and starts doubting their messages.
 */
@Composable
private fun RangeSettings(
    privacy: RadioPrivacy,
    mode: RangeMode,
    canRestore: Boolean,
    onMakePrivate: () -> Unit,
    onKeepPublic: () -> Unit,
    onChooseRange: (RangeMode) -> Unit,
) {
    // No heading of its own: this sits inside "Radio", and a second one under it
    // read as a different piece of hardware.
    SettingsGroup {
        Text(
            text = "Your rooms, messages, pins and locations are always sealed. This only decides " +
                "who can see the radio's own name and battery level: every Meshtastic device, " +
                "or only devices running Firepit.",
            style = MaterialTheme.typography.bodySmall,
            color = FirepitTheme.colors.textSecondary,
        )

        Column(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.xs)) {
            Text("This radio's own identity", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs)) {
                // Not a plain enum choice: each answer runs a different action,
                // and UNDECIDED is a state rather than something to offer.
                FirepitChip(
                    label = RadioPrivacy.OPEN.label,
                    selected = privacy == RadioPrivacy.OPEN,
                    onClick = onKeepPublic,
                )
                FirepitChip(
                    label = RadioPrivacy.FIREPIT.label,
                    selected = privacy == RadioPrivacy.FIREPIT,
                    onClick = onMakePrivate,
                )
            }

            Text(
                // Undecided means nothing has been written, so it is whatever it came as.
                text = if (privacy == RadioPrivacy.FIREPIT) {
                    RadioPrivacy.FIREPIT.summary
                } else {
                    RadioPrivacy.OPEN.summary
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (privacy == RadioPrivacy.FIREPIT) {
                    FirepitTheme.colors.textSecondary
                } else {
                    FirepitTheme.colors.warn
                },
            )

            if (privacy == RadioPrivacy.UNDECIDED) {
                Text(
                    text = "You haven't chosen yet, so this radio is still set up the way " +
                        "you found it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FirepitTheme.colors.textSecondary,
                )
            }

            // Whether going back is a real offer depends on having the old channel
            // to go back to, so it says which case this radio is in.
            if (privacy == RadioPrivacy.FIREPIT) {
                Text(
                    text = if (canRestore) {
                        "Firepit kept this radio's original channel. Choosing " +
                            "\"${RadioPrivacy.OPEN.label}\" puts it back."
                    } else {
                        "Firepit has no earlier channel for this radio, so " +
                            "\"${RadioPrivacy.OPEN.label}\" would leave it on Firepit's and " +
                            "only stop managing it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (canRestore) {
                        FirepitTheme.colors.textSecondary
                    } else {
                        FirepitTheme.colors.warn
                    },
                )
            }
        }

        // Only meaningful once the primary is ours: the mode works by changing
        // that channel's name, which is what the firmware turns into a frequency.
        if (privacy == RadioPrivacy.FIREPIT) {
            SettingsChoice(
                label = "How far messages travel",
                caption = mode.summary + " Everyone in a group has to use the same setting to " +
                    "hear each other; joining by QR code sets it for you.",
                entries = RangeMode.entries,
                selected = mode,
                labelOf = RangeMode::label,
                onChoose = onChooseRange,
            )
        }
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
    var confirmingRadioName by remember { mutableStateOf(false) }
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
                onClick = { confirmingRadioName = true },
                enabled = connected && person != null && !changed,
            ) {
                Text("Use on my radio")
            }
        }
        Text(
            text = "People in your rooms see this name, sealed. Everyone else nearby sees your " +
                "radio's own name, which it broadcasts in the open.",
            style = MaterialTheme.typography.bodySmall,
            color = FirepitTheme.colors.textSecondary,
        )
    }

    if (confirmingRadioName) {
        AlertDialog(
            onDismissRequest = { confirmingRadioName = false },
            title = { Text("Put your name on your radio?") },
            text = {
                Text(
                    "Your radio announces its name every few hours to every radio in range. " +
                        "In private mode that is under a key built into Firepit, so anyone with " +
                        "the app can read it; otherwise every Meshtastic radio can. Only do this " +
                        "if you are happy for strangers nearby to see it.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingRadioName = false
                        onUseAsNodeName()
                    },
                ) { Text("Use it") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRadioName = false }) { Text("Keep the radio's name") }
            },
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
                    text = "Shared with your Firepit rooms, so the people you invited see you " +
                        "in this colour too. Everyone else draws you from your node number.",
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
