package com.getfirepit.app.rooms

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The two ends of being let into a room.
 *
 * Global rather than part of the invite screen: a request arrives whenever the
 * other person scans, which may be after whoever showed the code has put the
 * phone down and gone somewhere else in the app.
 */
@Composable
fun RoomJoinPrompts(viewModel: RoomsViewModel = hiltViewModel()) {
    val pending by viewModel.pendingJoins.collectAsStateWithLifecycle()
    val awaiting by viewModel.awaiting.collectAsStateWithLifecycle()

    // One at a time, oldest first: two people scanning at once should be two
    // deliberate answers, not a stack of dialogs to dismiss blindly.
    pending.minByOrNull { it.askedAt }?.let { request ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Let them in?") },
            text = {
                Text(
                    "${request.nodeId} scanned your code and is asking to join." +
                        (request.fingerprint?.let { "\n\nTheir key: $it" } ?: "") + "\n\n" +
                        "Ask them to read the key on their screen, and only say yes if it " +
                        "matches and they are the person in front of you. Letting them in " +
                        "hands over the room's key, and nothing can take it back.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.approveJoin(request.nodeNum) }) {
                    Text("Let in")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.declineJoin(request.nodeNum) }) {
                    Text("No")
                }
            },
        )
    }

    awaiting?.let { room ->
        AlertDialog(
            onDismissRequest = viewModel::stopWaiting,
            title = { Text(if (room.declined) "Not let in" else "Asking to join ${room.roomName}") },
            text = {
                Text(
                    if (room.declined) {
                        "Whoever showed you the code said no. Nothing was added to your radio."
                    } else {
                        "Waiting for them to let you in. The code carries no key, so the room " +
                            "only arrives once they say yes." +
                            (room.ownFingerprint?.let { "\n\nYour key: $it — read it out so they can check it." } ?: "")
                    },
                )
            },
            icon = if (room.declined) null else ({ CircularProgressIndicator() }),
            confirmButton = {
                TextButton(onClick = viewModel::stopWaiting) {
                    Text(if (room.declined) "Close" else "Stop waiting")
                }
            },
        )
    }
}
