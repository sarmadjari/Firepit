package com.getfirepit.app.location

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.getfirepit.core.designsystem.adaptive.paneSheetMaxWidth
import com.getfirepit.core.designsystem.adaptive.withinPane
import com.getfirepit.core.designsystem.component.FirepitIcons
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.designsystem.theme.SheetShape
import com.getfirepit.core.protocol.ShareDuration
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * One sentence describing where your position is going, recomputed as the
 * clock runs down so "12m left" does not sit there saying 12 all evening.
 */
@Composable
fun sharingSummary(state: SharingUiState): String = when {
    !state.isSharing -> "Not shared with anyone"
    // Said rather than hidden: somebody who thinks they are being followed on
    // the map by their group should know when they are not.
    state.paused -> "Paused: your phone is away from your radio, or it doesn't carry " +
        "${state.roomName ?: "that room"}. Resumes when it's back"
    else -> sharedWith(state.roomName ?: "a room", state.endsAt)
}

/** "Shared with <label> · 3h 12m left", ticking. */
@Composable
private fun sharedWith(label: String, endsAt: Long?): String {
    if (endsAt == null) return "Shared with $label until you turn it off"
    val remaining by produceState(initialValue = timeLeft(endsAt), endsAt) {
        while (true) {
            value = timeLeft(endsAt)
            delay(TICK)
        }
    }
    return "Shared with $label · $remaining"
}

/**
 * A row for a settings list: says what is happening now, opens the picker.
 *
 * Deliberately states the negative case too. "Not shared with anyone" is the
 * answer to a question people actually have, and a row that only appears when
 * sharing is on cannot answer it.
 *
 * No leading icon or chevron: the rows either side of it in the Map section
 * have neither, and an indent that only one row has reads as a mistake rather
 * than as emphasis. The colour carries the state instead.
 */
@Composable
fun SharingRow(state: SharingUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = { Text("Your location") },
        supportingContent = {
            Text(
                text = sharingSummary(state),
                color = if (state.isSharing) {
                    FirepitTheme.colors.live
                } else {
                    FirepitTheme.colors.textSecondary
                },
            )
        },
        modifier = modifier.clickable(onClick = onClick),
    )
}

/**
 * The same control, answering the question a single room's page raises:
 * "is my location going to *this* room?"
 *
 * Naming the other room when one is chosen matters. "Not shared" next to a room
 * you are in reads as "not sharing at all", which would be a lie.
 */
@Composable
fun SharingRoomRow(
    state: SharingUiState,
    roomId: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isThisRoom = state.roomId == roomId
    val elsewhere = state.roomName?.takeIf { !isThisRoom }
    val summary = when {
        isThisRoom -> sharedWith("this room", state.endsAt)
        elsewhere != null -> "Shared with $elsewhere instead"
        else -> "Not shared"
    }

    ListItem(
        colors = plainListColours(),
        headlineContent = { Text("Your location") },
        supportingContent = {
            Text(
                text = summary,
                color = when {
                    isThisRoom -> FirepitTheme.colors.live
                    elsewhere != null -> FirepitTheme.colors.warn
                    else -> FirepitTheme.colors.textSecondary
                },
            )
        },
        leadingContent = {
            Icon(
                painter = painterResource(FirepitIcons.Locate),
                contentDescription = null,
                tint = if (isThisRoom) FirepitTheme.colors.live else FirepitTheme.colors.textSecondary,
            )
        },
        trailingContent = {
            Icon(
                painter = painterResource(FirepitIcons.Chevron),
                contentDescription = null,
                tint = FirepitTheme.colors.textSecondary,
            )
        },
        modifier = modifier.clickable(onClick = onClick),
    )
}

/**
 * The banner the map carries while sharing is on.
 *
 * Present for as long as sharing is, because the failure this guards against is
 * forgetting — and something you have forgotten is not something you will go
 * looking for in a menu.
 */
@Composable
fun SharingBanner(
    state: SharingUiState,
    onChange: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isSharing) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FirepitSpacing.cardCorner))
            .background(FirepitTheme.colors.surface2)
            .border(
                width = 1.dp,
                color = FirepitTheme.colors.live,
                shape = RoundedCornerShape(FirepitSpacing.cardCorner),
            )
            .clickable(onClick = onChange)
            .padding(start = FirepitSpacing.m, top = FirepitSpacing.s, bottom = FirepitSpacing.s),
    ) {
        Icon(
            painter = painterResource(FirepitIcons.Locate),
            contentDescription = null,
            tint = FirepitTheme.colors.live,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = sharingSummary(state),
            style = MaterialTheme.typography.bodyMedium,
            color = FirepitTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onStop) { Text("Stop") }
    }
}

/**
 * Choose a room and a length of time, or stop.
 *
 * Duration is asked at the same moment as the room rather than hidden behind a
 * later setting: the two answers together are the decision, and a picker that
 * only asks "who" has already decided "forever" on the person's behalf.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareLocationSheet(
    state: SharingUiState,
    onDismiss: () -> Unit,
    onShare: (roomId: Int, choice: ShareDuration) -> Unit,
    onStop: () -> Unit,
) {
    var room by remember(state.roomId, state.rooms) {
        mutableStateOf(state.roomId ?: state.rooms.firstOrNull()?.id)
    }
    var choice by remember(state.choice) { mutableStateOf(state.choice) }

    // Half height puts the confirm buttons under the navigation bar, where a
    // sheet asking about location sharing must not hide its own Stop button.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.withinPane(),
        sheetState = sheet,
        sheetMaxWidth = paneSheetMaxWidth(),
        shape = SheetShape,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(FirepitSpacing.m),
            modifier = Modifier
                .fillMaxWidth()
                // Two lists and a button row outgrow the sheet on a short
                // screen, and the buttons are the part that ends up under the
                // navigation bar.
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FirepitSpacing.screenMargin)
                .padding(bottom = FirepitSpacing.l),
        ) {
            Text("Share your location", style = MaterialTheme.typography.titleMedium)

            when {
                !state.connected -> Dead(
                    text = "Connect your radio first — Settings, then Nodes.",
                    onDismiss = onDismiss,
                )

                !state.hasRooms -> Dead(
                    // Slot 0 is deliberately never used for position: it would
                    // reach every Meshtastic radio in range.
                    text = "You need a private room first. Your location only ever goes to one " +
                        "private room — never the public channel, and never a Meshtastic " +
                        "channel, because those reach people you did not choose.\n\n" +
                        "Create or join a room from Chats, then come back.",
                    onDismiss = onDismiss,
                )

                else -> {
                    Text(
                        text = "Your phone seals where you are with the room's key and sends it " +
                            "through your radio, so only people in the room can see it — " +
                            "including people invited later. It pauses while your phone is away " +
                            "from your radio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FirepitTheme.colors.textSecondary,
                    )

                    Label("Room")
                    Column(Modifier.selectableGroup()) {
                        state.rooms.forEach { candidate ->
                            PickerRow(
                                label = candidate.displayName,
                                selected = room == candidate.id,
                                onSelect = { room = candidate.id },
                            )
                        }
                    }

                    Label("For how long")
                    Column(Modifier.selectableGroup()) {
                        ShareDuration.entries.forEach { entry ->
                            PickerRow(
                                label = entry.label,
                                selected = choice == entry,
                                onSelect = { choice = entry },
                            )
                        }
                    }

                    if (choice == ShareDuration.UNTIL_OFF) {
                        Text(
                            text = "Nothing will turn this off for you.",
                            style = MaterialTheme.typography.bodySmall,
                            color = FirepitTheme.colors.warn,
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isSharing) {
                            TextButton(onClick = onStop) { Text("Stop sharing") }
                        }
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.weight(1f),
                        ) {
                            TextButton(onClick = onDismiss) { Text("Cancel") }
                            TextButton(
                                enabled = room != null,
                                onClick = { room?.let { onShare(it, choice) } },
                            ) { Text(if (state.isSharing) "Update" else "Share") }
                        }
                    }
                }
            }
        }
    }
}

/** A state the sheet cannot act on, with the way out it would otherwise lack. */
@Composable
private fun Dead(text: String, onDismiss: () -> Unit) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onDismiss) { Text("Close") }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = FirepitTheme.colors.textSecondary,
    )
}

@Composable
private fun PickerRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FirepitSpacing.chipCorner))
            .clickable(role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = FirepitSpacing.s),
        )
    }
}

/** Colours a settings list row without the default surface tint. */
@Composable
internal fun plainListColours() = ListItemDefaults.colors(containerColor = FirepitTheme.colors.surface2)

private val TICK = 30.seconds

private fun timeLeft(endsAt: Long): String {
    val remaining = endsAt - System.currentTimeMillis()
    if (remaining <= 0) return "stopping"
    val minutes = remaining / 60_000
    val hours = minutes / 60
    return when {
        hours >= 1 -> "${hours}h ${minutes % 60}m left"
        minutes >= 1 -> "${minutes}m left"
        else -> "less than a minute left"
    }
}
