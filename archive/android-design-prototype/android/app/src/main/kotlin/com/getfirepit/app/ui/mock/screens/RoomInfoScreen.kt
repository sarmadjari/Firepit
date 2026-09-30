@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.getfirepit.app.ui.mock.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.getfirepit.app.ui.components.InfraAvatar
import com.getfirepit.app.ui.components.RoomAvatar
import com.getfirepit.app.ui.components.TagAvatar
import com.getfirepit.app.ui.components.outlineBorder
import com.getfirepit.app.ui.icons.FirepitIcons
import com.getfirepit.app.ui.mock.Mock
import com.getfirepit.app.ui.theme.FirepitTheme

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = FirepitTheme.colors.text2, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

@Composable
fun SectionCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = FirepitTheme.colors.surface2, contentColor = MaterialTheme.colorScheme.onSurface), border = outlineBorder(), modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = FirepitTheme.colors.surface2, contentColor = MaterialTheme.colorScheme.onSurface), border = outlineBorder(), modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun RoomInfoScreen() {
    val colors = FirepitTheme.colors
    val rowColors = ListItemDefaults.colors(containerColor = colors.surface2)
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = {}) { Icon(FirepitIcons.ArrowBack, "Back") } },
                title = { Text("Room info") },
                actions = { IconButton(onClick = {}) { Icon(FirepitIcons.More, "More") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface2),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RoomAvatar(FirepitIcons.Tent, 88.dp)
                Text("Camp", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("7 members · precise location", style = MaterialTheme.typography.bodyMedium, color = colors.text2)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(FirepitIcons.Qr, "Invite", Modifier.weight(1f))
                ActionTile(FirepitIcons.Share, "Link", Modifier.weight(1f))
                ActionTile(FirepitIcons.BellOff, "Mute", Modifier.weight(1f))
            }
            SectionLabel("Members")
            SectionCard {
                Mock.members.forEachIndexed { i, m ->
                    ListItem(
                        headlineContent = { Text(m.name) },
                        supportingContent = { Text(m.sub, style = MaterialTheme.typography.bodyMedium, color = colors.text2) },
                        leadingContent = { if (m.infra) InfraAvatar(40.dp) else TagAvatar(m.tag, m.identity, 40.dp) },
                        trailingContent = { m.trailing?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.text2) } },
                        colors = rowColors,
                    )
                    HorizontalDivider(Modifier.padding(start = 72.dp), color = colors.outline)
                }
                ListItem(
                    headlineContent = { Text("See all 7 members", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) },
                    trailingContent = { Icon(FirepitIcons.ChevronRight, null, tint = colors.stale, modifier = Modifier.size(18.dp)) },
                    colors = rowColors,
                )
            }
            SectionLabel("Pending invites")
            SectionCard {
                ListItem(
                    headlineContent = { Text("Link · created 12:31") },
                    supportingContent = { Text("Pending · expires in 9 min", color = colors.text2) },
                    leadingContent = { Icon(FirepitIcons.Share, null, tint = colors.text2) },
                    trailingContent = { Icon(FirepitIcons.ChevronRight, null, tint = colors.stale, modifier = Modifier.size(18.dp)) },
                    colors = rowColors,
                )
            }
            SectionCard {
                ListItem(
                    headlineContent = { Text("Location sharing") },
                    supportingContent = { Text("Precise · all rooms", color = colors.text2) },
                    colors = rowColors,
                )
                HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.outline)
                ListItem(
                    headlineContent = { Text("Notifications") },
                    trailingContent = {
                        Switch(checked = true, onCheckedChange = {}, colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary, checkedThumbColor = MaterialTheme.colorScheme.onPrimary))
                    },
                    colors = rowColors,
                )
            }
            SectionLabel("Privacy")
            Text(
                "Anyone with Camp's key can read messages and see shared locations. Location requests are answered automatically by each node.",
                style = MaterialTheme.typography.bodyMedium, color = colors.text2, modifier = Modifier.padding(horizontal = 4.dp),
            )
            SectionCard {
                ListItem(
                    headlineContent = { Text("Remove someone…") },
                    supportingContent = { Text("Creates a new key and re-invites everyone else", color = colors.text2) },
                    colors = rowColors,
                )
                HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.outline)
                ListItem(headlineContent = { Text("Leave room", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium) }, colors = rowColors)
            }
        }
    }
}
