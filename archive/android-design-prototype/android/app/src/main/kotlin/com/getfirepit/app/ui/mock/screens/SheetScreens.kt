@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.getfirepit.app.ui.mock.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getfirepit.app.ui.components.RoomAvatar
import com.getfirepit.app.ui.components.outlineBorder
import com.getfirepit.app.ui.icons.FirepitIcons
import com.getfirepit.app.ui.theme.FirepitTheme

/** Static rendering of a modal bottom sheet (M3: 28 dp top corners, drag handle, scrim) over a background screen. */
@Composable
fun SheetOver(background: @Composable () -> Unit, sheet: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        background()
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = FirepitTheme.colors.surface2,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BottomSheetDefaults.DragHandle() }
                    sheet()
                }
            }
        }
    }
}

@Composable
fun JoinScreen() {
    val colors = FirepitTheme.colors
    SheetOver(background = { ChatsScreen(showFab = false) }) {
        Text("Join Camp?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface), border = outlineBorder(), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoomAvatar(FirepitIcons.Tent, 44.dp)
                Column {
                    Text("Camp", style = MaterialTheme.typography.titleMedium)
                    Text("Invited by Sam · 6 members", style = MaterialTheme.typography.bodyMedium, color = colors.text2)
                }
            }
        }
        Text("PIN from Sam", style = MaterialTheme.typography.labelLarge, color = colors.text2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            listOf("4", "8", "2", "1", "", "9", "|", "", "").forEachIndexed { i, d ->
                if (i == 4) { Box(Modifier.width(6.dp)); return@forEachIndexed }
                val active = d == "|"
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(if (active) 2.dp else 1.dp, if (active) MaterialTheme.colorScheme.primary else colors.outline),
                    modifier = Modifier.size(38.dp, 48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (active) Box(Modifier.size(2.dp, 22.dp).background(MaterialTheme.colorScheme.primary))
                        else if (d.isNotEmpty()) Text(d, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Text("Ask Sam for the PIN if you didn't get it. A link alone can't open a room.", style = MaterialTheme.typography.bodyMedium, color = colors.text2)
        Button(onClick = {}, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Join room", style = MaterialTheme.typography.titleMedium) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = {}) {
                Icon(FirepitIcons.Qr, null, modifier = Modifier.size(18.dp))
                Text("  Scan a QR code instead", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean) {
    FilterChip(
        selected = selected, onClick = {}, label = { Text(label) }, colors = filterChipColors(),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = FirepitTheme.colors.outline, selectedBorderColor = Color.Transparent),
    )
}

@Composable
fun ShareLocationScreen() {
    val colors = FirepitTheme.colors
    SheetOver(background = { MapScreen(showControls = false) }) {
        Text("Share my location", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Room")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ChoiceChip("Camp", true); ChoiceChip("Trail", false) }
            Text("One room at a time · this stops sharing in Trail", style = MaterialTheme.typography.bodyMedium, color = colors.text2)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("For")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ChoiceChip("15 min", false); ChoiceChip("1 h", true); ChoiceChip("8 h", false); ChoiceChip("Custom", false) }
            Text("Precision: Precise · room setting", style = MaterialTheme.typography.bodyMedium, color = colors.text2)
        }
        Card(colors = CardDefaults.cardColors(containerColor = colors.calloutWarn, contentColor = MaterialTheme.colorScheme.onSurface), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(FirepitIcons.Info, null, tint = colors.warn, modifier = Modifier.size(20.dp))
                Text("Changing the update rate restarts your node for about 10 seconds. Starting or stopping later is instant.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(onClick = {}, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(FirepitIcons.Locate, null, modifier = Modifier.size(20.dp))
            Text("  Start sharing", style = MaterialTheme.typography.titleMedium)
        }
    }
}
