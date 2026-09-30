@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.getfirepit.app.ui.mock.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.getfirepit.app.ui.components.Composer
import com.getfirepit.app.ui.components.MessageBubble
import com.getfirepit.app.ui.components.RoomAvatar
import com.getfirepit.app.ui.components.SystemChip
import com.getfirepit.app.ui.icons.FirepitIcons
import com.getfirepit.app.ui.mock.Mock
import com.getfirepit.app.ui.theme.FirepitTheme

@Composable
fun RoomChatScreen() {
    val colors = FirepitTheme.colors
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = {}) { Icon(FirepitIcons.ArrowBack, "Back") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RoomAvatar(FirepitIcons.Tent, 38.dp)
                        Column {
                            Text("Camp", style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("7 members ·", style = MaterialTheme.typography.labelSmall, color = colors.text2)
                                Box(Modifier.size(7.dp).background(colors.live, CircleShape))
                                Text("connected", style = MaterialTheme.typography.labelSmall, color = colors.text2)
                            }
                        }
                    }
                },
                actions = { IconButton(onClick = {}) { Icon(FirepitIcons.More, "More") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface2),
            )
        },
        bottomBar = { Composer() },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Mock.messages) { m -> if (m.system) SystemChip(m.text) else MessageBubble(m) }
        }
    }
}
