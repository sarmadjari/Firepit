package com.getfirepit.app.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.core.designsystem.component.BackButton
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.model.MapPin

/**
 * Every pin currently on the map, with the actions the map itself makes fiddly.
 *
 * Tapping a small marker to rename it is awkward; a list is not. Pins placed by
 * other people appear here too, read-only, so the map's contents are never a
 * mystery.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: PinsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<MapPin?>(null) }
    var removing by remember { mutableStateOf<MapPin?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Dropped pins") },
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Text(
                text = "Pins are shared with everyone on the channel. You can only change the " +
                    "ones you placed.",
                style = MaterialTheme.typography.bodySmall,
                color = FirepitTheme.colors.textSecondary,
                modifier = Modifier.padding(FirepitSpacing.screenMargin),
            )

            state.error?.let {
                Text(
                    text = it,
                    color = FirepitTheme.colors.danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = FirepitSpacing.screenMargin),
                )
            }

            if (state.pins.isEmpty()) {
                Text(
                    text = "No pins yet. Long-press the map to drop one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FirepitTheme.colors.textSecondary,
                    modifier = Modifier.padding(FirepitSpacing.screenMargin),
                )
                return@Column
            }

            LazyColumn {
                items(state.pins, key = { it.id }) { pin ->
                    val mine = pin.canEdit(state.myNodeNum)
                    ListItem(
                        headlineContent = { Text(pin.name.ifBlank { "Pin" }) },
                        supportingContent = {
                            Text(
                                text = "%.5f, %.5f%s".format(
                                    pin.latitude,
                                    pin.longitude,
                                    if (mine) "" else " · placed by someone else",
                                ),
                            )
                        },
                        trailingContent = {
                            if (mine) {
                                Row {
                                    TextButton(onClick = { renaming = pin }) { Text("Rename") }
                                    TextButton(onClick = { removing = pin }) { Text("Delete") }
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    renaming?.let { pin ->
        RenamePinDialog(
            pin = pin,
            onDismiss = { renaming = null },
            onRename = { name ->
                viewModel.rename(pin, name)
                renaming = null
            },
        )
    }

    removing?.let { pin ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Delete ${pin.name.ifBlank { "this pin" }}?") },
            // Deletion travels the mesh, so it is not a local undo.
            text = { Text("It disappears for everyone on the channel.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.remove(pin)
                        removing = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RenamePinDialog(pin: MapPin, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(pin.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename pin") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= PIN_NAME_LIMIT) name = it },
                singleLine = true,
                supportingText = { Text("${PIN_NAME_LIMIT - name.length} characters left") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank() && name.trim() != pin.name,
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
