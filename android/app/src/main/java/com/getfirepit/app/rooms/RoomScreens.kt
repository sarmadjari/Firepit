package com.getfirepit.app.rooms

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.core.crypto.InviteCodec
import com.getfirepit.core.designsystem.component.BackButton
import com.getfirepit.core.designsystem.component.FirepitChip
import com.getfirepit.core.designsystem.component.FirepitDetailBar
import com.getfirepit.core.designsystem.component.FirepitIcons
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme

/**
 * What a new room is, before it is named.
 *
 * Firepit's own rooms and plain Meshtastic channels are different protocols
 * with different audiences, so the choice is made up front rather than buried
 * in a setting: one is unreadable to everyone outside it, the other exists
 * precisely so that people outside Firepit can read it.
 *
 * The open mesh is not offered here. Nobody creates it — it already exists — and
 * making it a button beside a private room would put the least private option
 * one mis-tap away.
 */
enum class NewRoomKind(val label: String, val readableBy: String, val blurb: String) {
    FIREPIT(
        label = "Firepit",
        readableBy = "Only the people you invite",
        blurb = "Firepit's own kind of room. Messages are sealed on your phone before the radio " +
            "ever sees them, so nobody on the Meshtastic network can read them — not even " +
            "someone holding one of the radios. Names, colours, and delivery and read status " +
            "all work here. You invite people with a Firepit QR code.",
    ),
    MESHTASTIC_SHARED(
        label = "Meshtastic",
        readableBy = "Anyone who has the channel's key",
        blurb = "A standard Meshtastic channel, for talking to people who are not running " +
            "Firepit. Its key is kept on the radios, so anyone holding one can read everything " +
            "sent here. Firepit's own features are switched off, because other apps would not " +
            "understand them. You share it from the Meshtastic app.",
    ),
}

/** Name entry for a new room. The byte budget is the radio's, not a UI choice. */
@Composable
fun CreateRoomDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onCreateShared: (String) -> Unit = {},
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(NewRoomKind.FIREPIT) }
    val bytes = name.trim().toByteArray().size
    val tooLong = bytes > InviteCodec.MAX_ROOM_NAME_BYTES

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New room") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs)) {
                    NewRoomKind.entries.forEach { option ->
                        FirepitChip(
                            label = option.label,
                            selected = kind == option,
                            onClick = { kind = option },
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Camp") },
                    isError = tooLong,
                    supportingText = {
                        Text(
                            text = "${InviteCodec.MAX_ROOM_NAME_BYTES - bytes} bytes left",
                            color = if (tooLong) {
                                FirepitTheme.colors.danger
                            } else {
                                FirepitTheme.colors.textSecondary
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions.Default,
                )
                Text(
                    text = kind.readableBy,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (kind == NewRoomKind.FIREPIT) {
                        FirepitTheme.colors.textSecondary
                    } else {
                        FirepitTheme.colors.warn
                    },
                )
                Text(
                    text = kind.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = FirepitTheme.colors.textSecondary,
                )
                if (kind == NewRoomKind.FIREPIT) {
                    Text(
                        text = "Everyone you invite gets the room's key. There is no way to remove " +
                            "one person later without making a new key for everybody.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FirepitTheme.colors.textSecondary,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (kind) {
                        NewRoomKind.FIREPIT -> onCreate(name.trim())
                        NewRoomKind.MESHTASTIC_SHARED -> onCreateShared(name.trim())
                    }
                },
                enabled = name.isNotBlank() && !tooLong,
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Rotating QR invite. */
// LocalActivity would be the modern way to reach the window, but it arrived in
// activity-compose 1.10 and this project is on 1.8. The cast the rule warns
// about is a checked one, and a null window simply leaves the flag unset.
@Suppress("ContextCastToActivity")
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

    // The code on screen is a way to ask in, so it must not be capturable by
    // anything on the phone. A camera pointed at it is still a camera, but a
    // screenshot would outlive the rotation and reach a photo backup.
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    LaunchedEffect(roomId) { viewModel.startInviteRotation(roomId) }
    DisposableEffect(roomId) { onDispose { viewModel.stopInvite() } }

    Scaffold(
        modifier = modifier,
        topBar = {
            FirepitDetailBar(title = "Invite to $roomName", onBack = onBack)
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.s),
            ) {
                Icon(
                    painter = painterResource(FirepitIcons.Clock),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = invite?.let { "Refreshes in ${it.secondsRemaining}s" } ?: "Preparing…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FirepitTheme.colors.textSecondary,
                )
            }

            Text(
                text = "Show this to people next to you. It refreshes every few seconds and only " +
                    "works while they are here. It carries no key: a photograph of it only lets " +
                    "someone ask, and you still decide who comes in.",
                style = MaterialTheme.typography.bodyMedium,
                color = FirepitTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            // What the scanning phone checks the code against before it asks:
            // a code is anyone's to print, and this is the part they cannot fake.
            val fingerprint = remember { viewModel.ownFingerprint() }
            fingerprint?.let {
                Text(
                    text = "Your key: $it",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }

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
    val scanned by viewModel.scanned.collectAsStateWithLifecycle()

    LaunchedEffect(state.joinedRoomName) {
        if (state.joinedRoomName != null) {
            viewModel.clearMessages()
            onBack()
        }
    }

    // Leaving the screen abandons a code nobody confirmed.
    DisposableEffect(Unit) { onDispose { viewModel.cancelScan() } }

    Scaffold(
        modifier = modifier,
        topBar = {
            FirepitDetailBar(title = "Scan a code", onBack = onBack)
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
                // asked to do two things at once, or while one waits to be checked.
                enabled = !state.busy && scanned == null,
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
                Text(
                    text = "A Firepit invite joins a private room. A Meshtastic code adds a " +
                        "channel other Meshtastic apps can read — the room will say which.",
                    color = FirepitTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    scanned?.let { code ->
        AlertDialog(
            onDismissRequest = viewModel::cancelScan,
            title = { Text("Ask to join ${code.roomName}?") },
            text = {
                Text(
                    "This code says it is from ${code.inviterId}" +
                        (code.fingerprint?.let { ", with the key $it" } ?: "") + ".\n\n" +
                        "Their screen shows their key under the code. Only ask if the two " +
                        "match: a code is anyone's to print.",
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmScan) { Text("Ask to join") } },
            dismissButton = { TextButton(onClick = viewModel::cancelScan) { Text("Cancel") } },
        )
    }
}
