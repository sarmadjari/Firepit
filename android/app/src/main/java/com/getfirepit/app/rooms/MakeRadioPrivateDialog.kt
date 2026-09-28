package com.getfirepit.app.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme

/**
 * Asked once, when somebody makes their first private room.
 *
 * A radio may already be someone's Meshtastic node, on a mesh with their
 * friends. Taking its primary channel over would drop it off that mesh with no
 * explanation, so it is a question rather than something the app does quietly.
 *
 * The leading sentence matters: the room they just made is private either way,
 * and this only decides whether the radio's own name and battery are. Somebody
 * who reads nothing else should still not end up thinking their messages were
 * at stake.
 */
@Composable
fun MakeRadioPrivateDialog(
    onMakePrivate: () -> Unit,
    onKeepPublic: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepPublic,
        title = { Text("Make this radio private to Firepit?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FirepitSpacing.s)) {
                Text(
                    text = "Your room is private either way. Nobody can read your messages " +
                        "whichever you choose.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "What is still public is the radio itself. Right now it broadcasts " +
                        "its name and battery level in the open, and any Meshtastic device " +
                        "nearby can see them. Making it private hides those too.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FirepitTheme.colors.textSecondary,
                )
                Text(
                    text = "Say no if you also use this radio on another Meshtastic mesh — " +
                        "making it private would take it off that mesh.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FirepitTheme.colors.textSecondary,
                )
                Text(
                    text = "You can change this any time in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FirepitTheme.colors.textSecondary,
                )
            }
        },
        confirmButton = { Button(onClick = onMakePrivate) { Text("Yes, make it private") } },
        dismissButton = { TextButton(onClick = onKeepPublic) { Text("No, leave it") } },
    )
}
