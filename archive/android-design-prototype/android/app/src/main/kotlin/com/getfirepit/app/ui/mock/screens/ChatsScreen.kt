@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.getfirepit.app.ui.mock.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Badge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.getfirepit.app.ui.components.FirepitNavBar
import com.getfirepit.app.ui.components.FirepitTab
import com.getfirepit.app.ui.components.NodeStatusLine
import com.getfirepit.app.ui.components.RoomAvatar
import com.getfirepit.app.ui.components.StatusGlyph
import com.getfirepit.app.ui.components.TagAvatar
import com.getfirepit.app.ui.icons.FirepitIcons
import com.getfirepit.app.ui.mock.Mock
import com.getfirepit.app.ui.theme.FirepitTheme

@Composable
fun filterChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = FirepitTheme.colors.surface2,
    labelColor = MaterialTheme.colorScheme.onSurface,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
)

@Composable
fun ChatsScreen(showFab: Boolean = true) {
    val colors = FirepitTheme.colors
    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                TopAppBar(
                    title = { Text("Firepit", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = {}) { Icon(FirepitIcons.Search, "Search") }
                        IconButton(onClick = {}) { Icon(FirepitIcons.More, "More") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                NodeStatusLine("Sam's T-Echo · 78%", Modifier.padding(start = 16.dp, bottom = 8.dp))
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All" to true, "Rooms" to false, "Direct" to false).forEach { (label, on) ->
                        FilterChip(
                            selected = on, onClick = {}, label = { Text(label) }, colors = filterChipColors(),
                            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = on, borderColor = colors.outline, selectedBorderColor = Color.Transparent),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (showFab) FloatingActionButton(onClick = {}, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Icon(FirepitIcons.Plus, "New chat")
            }
        },
        bottomBar = { FirepitNavBar(FirepitTab.Chats) {} },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            itemsIndexed(Mock.chats) { i, chat ->
                ListItem(
                    headlineContent = { Text(chat.title, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            chat.ownStatus?.let { StatusGlyph(it) }
                            Text(chat.preview, style = MaterialTheme.typography.bodyMedium, color = colors.text2)
                        }
                    },
                    leadingContent = {
                        if (chat.icon != null) RoomAvatar(chat.icon, 52.dp) else TagAvatar(chat.tag ?: "?", chat.identity, 52.dp)
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(chat.time, style = MaterialTheme.typography.labelSmall, color = if (chat.unread > 0) MaterialTheme.colorScheme.primary else colors.text2)
                            if (chat.unread > 0) Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) { Text("${chat.unread}") }
                            if (chat.muted) Icon(FirepitIcons.BellOff, "Muted", tint = colors.stale, modifier = Modifier.size(16.dp))
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = colors.surface2),
                )
                if (i < Mock.chats.lastIndex) HorizontalDivider(Modifier.padding(start = 84.dp), color = colors.outline)
            }
        }
    }
}
