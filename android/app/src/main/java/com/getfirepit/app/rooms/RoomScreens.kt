package com.getfirepit.app.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.core.crypto.InviteCodec
import com.getfirepit.core.designsystem.component.BackButton
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme

/** Name entry for a new room. The byte budget is the radio's, not a UI choice. */
@Composable
fun CreateRoomDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val bytes = name.trim().toByteArray().size
    val tooLong = bytes > InviteCodec.MAX_ROOM_NAME_BYTES

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New room") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Camp") },
                    isError = tooLong,
                    supportingText = {
                        Text(
                            text = "${InviteCodec.MAX_ROOM_NAME_BYTES - bytes} bytes left",
                            color = if (tooLong) FirepitTheme.colors.danger else FirepitTheme.colors.textSecondary,
                        )
                    },
                    keyboardOptions = KeyboardOptions.Default,
                )
                Text(
                    text = "Everyone you invite gets the room's key. There is no way to remove " +
                        "one person later without making a new key for everybody.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FirepitTheme.colors.textSecondary,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank() && !tooLong,
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Rotating QR invite. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteScreen(
    roomId: Int,
    roomName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RoomsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(roomId) { viewModel.startInviteRotation(roomId) }
    DisposableEffect(roomId) { onDispose { viewModel.stopInvite() } }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Invite to $roomName") },
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(FirepitSpacing.screenMargin),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FirepitSpacing.l),
        ) {
            val invite = state.invite
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    // White card in both themes: contrast is what makes it scan.
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .padding(FirepitSpacing.l),
                contentAlignment = Alignment.Center,
            ) {
                if (invite == null) {
                    CircularProgressIndicator()
                } else {
                    QrCode(invite.payload, Modifier.fillMaxSize())
                }
            }

            Text(
                text = invite?.let { "Refreshes in ${it.secondsRemaining}s" } ?: "Preparing…",
                style = MaterialTheme.typography.bodyMedium,
                color = FirepitTheme.colors.textSecondary,
            )

            Text(
                text = "Show this to people next to you. It refreshes every few seconds and only " +
                    "works while they are here.",
                style = MaterialTheme.typography.bodyMedium,
                color = FirepitTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            state.error?.let {
                Text(it, color = FirepitTheme.colors.danger, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Camera scan to join a room. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinRoomScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RoomsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.joinedRoomName) {
        if (state.joinedRoomName != null) {
            viewModel.clearMessages()
            onBack()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Scan an invite") },
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            QrScanner(
                onScanned = viewModel::joinFromScan,
                // Ignore frames while a join is in flight so the radio isn't
                // asked to do two things at once.
                enabled = !state.busy,
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(FirepitSpacing.screenMargin),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
            ) {
                if (state.busy) CircularProgressIndicator()
                state.error?.let {
                    Text(
                        text = it,
                        color = FirepitTheme.colors.danger,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
