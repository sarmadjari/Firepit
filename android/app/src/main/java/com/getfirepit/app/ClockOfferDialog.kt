package com.getfirepit.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.getfirepit.core.data.ClockOffer
import com.getfirepit.core.data.NodeClock
import kotlin.time.Duration
import kotlinx.coroutines.launch

/**
 * Asks before writing the phone's time to a radio whose clock is wrong.
 *
 * The radio stamps every message it hands over, so a wrong clock puts today's
 * conversation under yesterday. Correcting it changes the device, which is the
 * user's to decide.
 */
@Composable
fun ClockOfferDialog(nodeClock: NodeClock) {
    val offer by nodeClock.offer.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    offer?.let { clock ->
        AlertDialog(
            onDismissRequest = nodeClock::dismiss,
            title = { Text("This radio's clock is wrong") },
            text = { Text(explain(clock)) },
            confirmButton = {
                TextButton(onClick = { scope.launch { nodeClock.sync() } }) {
                    Text("Set from phone")
                }
            },
            dismissButton = {
                TextButton(onClick = nodeClock::dismiss) { Text("Leave it") }
            },
        )
    }
}

private fun explain(offer: ClockOffer): String {
    val direction = if (offer.behind) "behind" else "ahead of"
    return "It is ${describe(offer.drift)} $direction this phone, so messages it " +
        "passes on can appear under the wrong day.\n\n" +
        "Setting it from the phone costs no airtime and does not touch the mesh. " +
        "A radio with its own GPS will keep the better time it already has."
}

private fun describe(drift: Duration): String = with(drift) {
    when {
        inWholeDays >= 1 -> "$inWholeDays ${plural(inWholeDays, "day")}"
        inWholeHours >= 1 -> "$inWholeHours ${plural(inWholeHours, "hour")}"
        inWholeMinutes >= 1 -> "$inWholeMinutes ${plural(inWholeMinutes, "minute")}"
        else -> "under a minute"
    }
}

private fun plural(count: Long, noun: String): String = if (count == 1L) noun else "${noun}s"
