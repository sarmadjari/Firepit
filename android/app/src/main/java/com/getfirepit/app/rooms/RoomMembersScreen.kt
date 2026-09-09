package com.getfirepit.app.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.core.designsystem.component.BackButton
import com.getfirepit.core.designsystem.component.IdentityAvatar
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
    viewModel: RoomsViewModel = hiltViewModel(),
) {
    val membersFlow = remember(roomId) { viewModel.members(roomId) }
    val members by membersFlow.collectAsStateWithLifecycle(emptyList())

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(roomName) },
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
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

            if (members.isEmpty()) {
                Text(
                    text = "Nobody heard yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FirepitTheme.colors.textSecondary,
                    modifier = Modifier.padding(FirepitSpacing.screenMargin),
                )
                return@Column
            }

            LazyColumn {
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
                    )
                    HorizontalDivider()
                }
            }
        }
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
