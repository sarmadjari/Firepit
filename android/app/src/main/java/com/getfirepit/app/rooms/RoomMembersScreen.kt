package com.getfirepit.app.rooms

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.app.location.ShareLocationSheet
import com.getfirepit.app.location.SharingRoomRow
import com.getfirepit.app.location.SharingViewModel
import com.getfirepit.core.designsystem.component.BackButton
import com.getfirepit.core.designsystem.component.FirepitDetailBar
import com.getfirepit.core.designsystem.component.FirepitIcons
import com.getfirepit.core.model.RoomKind
import com.getfirepit.core.designsystem.component.IdentityAvatar
import com.getfirepit.core.designsystem.component.RoomAvatar
import com.getfirepit.core.designsystem.component.RoomIcon
import com.getfirepit.core.designsystem.component.SectionLabel
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import java.util.concurrent.TimeUnit

/**
 * Who we have seen in a room.
 *
 * The wording is deliberately observational. A Meshtastic channel has no
 * membership list to read, so this cannot claim who is "in" the room — only
 * who has spoken and who was vouched for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomMembersScreen(
    roomId: Int,
    roomName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    kind: RoomKind = RoomKind.FIREPIT,
    onInvite: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    onLeft: () -> Unit = {},
    viewModel: RoomsViewModel = hiltViewModel(),
    sharingViewModel: SharingViewModel = hiltViewModel(),
) {
    val membersFlow = remember(roomId) { viewModel.members(roomId) }
    val members by membersFlow.collectAsStateWithLifecycle(emptyList())
    val trace by viewModel.trace.collectAsStateWithLifecycle()
    val rotation by viewModel.rotation.collectAsStateWithLifecycle()
    val sharing by sharingViewModel.state.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<MemberRow?>(null) }
    var confirmingLeave by remember { mutableStateOf(false) }
    var pickingRoom by remember { mutableStateOf(false) }

    if (pickingRoom) {
        ShareLocationSheet(
            state = sharing,
            onDismiss = { pickingRoom = false },
            onStop = {
                pickingRoom = false
                sharingViewModel.stop()
            },
            onShare = { chosen, choice ->
                pickingRoom = false
                sharingViewModel.share(chosen, choice)
            },
        )
    }

    if (confirmingLeave) {
        AlertDialog(
            onDismissRequest = { confirmingLeave = false },
            title = { Text("Leave this room?") },
            text = {
                Text(
                    "The key goes with it, so you will need a new invitation to " +
                        "come back, and this room's messages are deleted from this phone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingLeave = false
                        viewModel.leaveRoom(roomId)
                        onLeft()
                    },
                ) { Text("Leave") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLeave = false }) { Text("Stay") }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            FirepitDetailBar(title = "Room info", onBack = onBack)
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
                    modifier = Modifier.padding(vertical = FirepitSpacing.l),
                ) {
                    RoomAvatar(RoomIcon.forRoomId(roomId), size = 88.dp)
                    Text(roomName, style = MaterialTheme.typography.headlineLarge)
                    Text(
                        text = kind.readableBy,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (kind.isPrivate) {
                            FirepitTheme.colors.textSecondary
                        } else {
                            FirepitTheme.colors.warn
                        },
                    )
                    Text(
                        text = kind.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = FirepitTheme.colors.textSecondary,
                        modifier = Modifier.padding(horizontal = FirepitSpacing.screenMargin),
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FirepitSpacing.screenMargin),
                    horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.m),
                ) {
                    // Only a Firepit room can be invited to: its QR carries a
                    // room key that the Meshtastic link format cannot hold.
                    if (kind.isPrivate) {
                        RoomAction(
                            icon = FirepitIcons.Qr,
                            label = "Invite",
                            onClick = onInvite,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    RoomAction(
                        icon = if (muted) FirepitIcons.Bell else FirepitIcons.Mute,
                        label = if (muted) "Unmute" else "Mute",
                        onClick = onToggleMute,
                        modifier = Modifier.weight(1f),
                    )
                    RoomAction(
                        icon = FirepitIcons.Close,
                        label = "Leave",
                        onClick = { confirmingLeave = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Membership is a Firepit idea: on a Meshtastic channel there is no
            // roster, and anyone with the key can be on it unannounced.
            if (kind.isPrivate) {
                item {
                    SharingRoomRow(
                        state = sharing,
                        roomId = roomId,
                        onClick = { pickingRoom = true },
                    )
                }
                item { SectionLabel("Members") }
            }

            if (members.isEmpty() && kind.isPrivate) {
                item {
                    Text(
                        text = "Nobody heard yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FirepitTheme.colors.textSecondary,
                        modifier = Modifier.padding(FirepitSpacing.screenMargin),
                    )
                }
            }

            items(members.takeIf { kind.isPrivate }.orEmpty(), key = { it.member.nodeNum }) { row ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = FirepitTheme.colors.surface2),
                    headlineContent = {
                        Text(
                            text = buildString {
                                append(row.displayName)
                                row.invitedByName?.let { append(" · invited by $it") }
                            },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = memberDetail(row),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (row.member.isFirstHand) {
                                    FirepitTheme.colors.textSecondary
                                } else {
                                    FirepitTheme.colors.stale
                                },
                            )
                            if (!row.member.isVouched) {
                                Text(
                                    text = "Invite unknown",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FirepitTheme.colors.stale,
                                )
                            }
                        }
                    },
                    leadingContent = {
                        IdentityAvatar(
                            nodeNum = row.member.nodeNum,
                            tag = row.shortName,
                            name = row.displayName,
                        )
                    },
                    trailingContent = {
                        if (row.isSelf) {
                            Text(
                                text = "You",
                                style = MaterialTheme.typography.bodySmall,
                                color = FirepitTheme.colors.textSecondary,
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { viewModel.checkPath(row.member.nodeNum, row.displayName) },
                                    enabled = trace !is TraceState.Running,
                                ) {
                                    Text(
                                        if ((trace as? TraceState.Running)?.nodeNum == row.member.nodeNum) {
                                            "…"
                                        } else {
                                            "Path"
                                        },
                                    )
                                }
                                TextButton(onClick = { removing = row }) {
                                    Text("Remove", color = FirepitTheme.colors.danger)
                                }
                            }
                        }
                    },
                )
                HorizontalDivider(color = FirepitTheme.colors.outline)
            }

            item {
                Text(
                    text = "Anyone with the room's key can read and post. Members appear as Firepit " +
                        "hears them, or when another member reports them, so this list may be " +
                        "incomplete.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FirepitTheme.colors.textSecondary,
                    modifier = Modifier.padding(FirepitSpacing.screenMargin),
                )
            }
        }
    }

    (trace as? TraceState.Done)?.let { done ->
        AlertDialog(
            onDismissRequest = viewModel::clearTrace,
            title = { Text("Path check") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s)) {
                    Text(done.summary, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "A path is not a delivery receipt. It shows the mesh could reach " +
                            "them just now, not that they read anything.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FirepitTheme.colors.textSecondary,
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::clearTrace) { Text("Close") } },
        )
    }
    removing?.let { row ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${row.displayName}?") },
            text = {
                Text(
                    "Nothing can take the old key back from them, so the room moves to a new " +
                        "one instead. Everyone still here is sent it privately; ${row.displayName} " +
                        "is not, and can read nothing from now on.\n\n" +
                        "They keep whatever they already received.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeMember(roomId, row.member.nodeNum)
                    removing = null
                }) {
                    Text("Remove and change the key", color = FirepitTheme.colors.danger)
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }

    rotation?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearRotation,
            title = { Text("The room has a new key") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::clearRotation) { Text("Done") } },
        )
    }
}

private fun relativeTime(epochMillis: Long): String {
    val elapsed = System.currentTimeMillis() - epochMillis
    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    val days = TimeUnit.MILLISECONDS.toDays(elapsed)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        else -> "${days}d ago"
    }
}

/**
 * The node id first, then hardware, charge and when we last heard them.
 *
 * The id leads because a name in this list may be one the holder simply typed:
 * a card is sealed to the room, which proves membership, not who somebody is.
 * The node number is the only part the mesh attests, so it stays on screen.
 */
private fun memberDetail(row: MemberRow): String = buildList {
    add(row.nodeId)
    row.node?.hwModel?.takeIf { it.isNotBlank() }?.let { add(prettyHardware(it)) }
    row.node?.batteryLevel?.let { add(if (it > 100) "powered" else "$it%") }
    add(
        row.member.lastHeard?.let { "heard ${relativeTime(it)}" }
            // Somebody else's word, not our own observation.
            ?: "not heard yet",
    )
}.joinToString(" · ")

/** WISMESH_TAG reads as shouting in a list; the radio's own name does not. */
private fun prettyHardware(model: String): String = model
    .split('_')
    .joinToString(" ") { part -> part.lowercase().replaceFirstChar(Char::uppercase) }

/** One of the room's headline actions, drawn as an outlined card. */
@Composable
private fun RoomAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(FirepitSpacing.cardCorner),
        color = FirepitTheme.colors.surface2,
        border = BorderStroke(1.dp, FirepitTheme.colors.outline),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
            modifier = Modifier.padding(vertical = FirepitSpacing.l),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
