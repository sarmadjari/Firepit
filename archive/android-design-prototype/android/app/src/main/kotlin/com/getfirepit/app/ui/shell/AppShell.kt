package com.getfirepit.app.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.getfirepit.app.R
import com.getfirepit.app.ui.components.FirepitNavBar
import com.getfirepit.app.ui.components.FirepitTab

/** The three tabs from docs/meshchat-ux-design.md §4.1. Screens are placeholders until each feature lands. */
@Composable
fun AppShell() {
    var selected by rememberSaveable { mutableStateOf(FirepitTab.Chats) }
    Scaffold(bottomBar = { FirepitNavBar(selected) { selected = it } }) { padding ->
        when (selected) {
            FirepitTab.Chats -> ChatsEmptyState(Modifier.padding(padding))
            FirepitTab.Map -> Placeholder(R.string.map_empty, Modifier.padding(padding))
            FirepitTab.Settings -> Placeholder(R.string.settings_placeholder, Modifier.padding(padding))
        }
    }
}

@Composable
private fun ChatsEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.chats_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.chats_empty_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Button(onClick = { /* TODO create room (UX §5.2) */ }) { Text(stringResource(R.string.create_room)) }
        OutlinedButton(onClick = { /* TODO join room (UX §5.3) */ }) { Text(stringResource(R.string.join_room)) }
    }
}

@Composable
private fun Placeholder(stringRes: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(stringRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
