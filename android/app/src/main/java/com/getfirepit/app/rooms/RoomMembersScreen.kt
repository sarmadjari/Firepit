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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.core.designsystem.component.BackButton
import com.getfirepit.core.designsystem.component.FirepitIcons
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
    onInvite: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    viewModel: RoomsViewModel = hiltViewModel(),
) {
    val membersFlow = remember(roomId) { viewModel.members(roomId) }
    val members by membersFlow.collectAsStateWithLifecycle(emptyList())
    val trace by viewModel.trace.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Room info") },
                navigationIcon = { BackButton(onClick = onBack) },
            )
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
                        text = if (members.size == 1) "1 member" else "${members.size} members",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FirepitTheme.colors.textSecondary,
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
                    RoomAction(
                        icon = FirepitIcons.Qr,
                        label = "Invite",
                        onClick = onInvite,
                        modifier = Modifier.weight(1f),
                    )
                    RoomAction(
                        icon = if (muted) FirepitIcons.Bell else FirepitIcons.Mute,
                        label = if (muted) "Unmute" else "Mute",
                        onClick = onToggleMute,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item { SectionLabel("Members") }

            item {
                Text(
                    text = "Anyone with the room's key can read and post. Members are listed as " +
                        "Firepit hears them, or when another member reports them, so this may be " +
                        "incomplete.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FirepitTheme.colors.textSecondary,
                    modifier = Modifier.padding(
                        horizontal = FirepitSpacing.screenMargin,
                        vertical = FirepitSpacing.s,
                    ),
                )
            }

            if (members.isEmpty()) {
                item {
                    Text(
                        text = "Nobody heard yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FirepitTheme.colors.textSecondary,
                        modifier = Modifier.padding(FirepitSpacing.screenMargin),
                    )
                }
            }

            items(members, key = { it.member.nodeNum }) { row ->
                ListItem(
                    headlineContent = {
                        Text(if (row.isSelf) "${row.displayName} (you)" else row.displayName)
                    },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = row.invitedByName?.let { "Invited by $it" }
                                    ?: if (row.member.isVouched) {
                                        "Created this room"
                                    } else {
                                        "Invite unknown"
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (row.member.isVouched) {
                                    FirepitTheme.colors.textSecondary
                                } else {
                                    FirepitTheme.colors.stale
                                },
                            )
                            Text(
                                text = row.member.lastHeard
                                    ?.let { "Last heard ${relativeTime(it)}" }
                                    // Somebody else's word, not our own observation.
                                    ?: "Listed by another member, not heard yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (row.member.isFirstHand) {
                                    FirepitTheme.colors.textSecondary
                                } else {
                                    FirepitTheme.colors.stale
                                },
                            )
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
                        if (!row.isSelf) {
                            TextButton(
                                onClick = { viewModel.checkPath(row.member.nodeNum, row.displayName) },
                                enabled = trace !is TraceState.Running,
                            ) {
                                Text(
                                    if ((trace as? TraceState.Running)?.nodeNum == row.member.nodeNum) {
                                        "Checking…"
                                    } else {
                                        "Check path"
                                    },
                                )
                            }
                        }
                    },
                )
                HorizontalDivider(color = FirepitTheme.colors.outline)
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
