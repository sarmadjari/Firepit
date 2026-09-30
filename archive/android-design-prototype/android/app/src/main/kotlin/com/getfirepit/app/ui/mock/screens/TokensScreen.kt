@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.getfirepit.app.ui.mock.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.getfirepit.app.ui.components.StatusGlyph
import com.getfirepit.app.ui.mock.MsgStatus
import com.getfirepit.app.ui.theme.FirepitTheme

private fun Color.hex() = "#%06X".format(toArgb() and 0xFFFFFF)

@Composable
private fun Swatch(name: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(44.dp).background(color, RoundedCornerShape(10.dp)).border(1.dp, FirepitTheme.colors.outline, RoundedCornerShape(10.dp)))
        Column {
            Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(color.hex(), style = MaterialTheme.typography.labelSmall, color = FirepitTheme.colors.text2)
        }
    }
}

@Composable
fun TokensScreen() {
    val c = FirepitTheme.colors
    val m = MaterialTheme.colorScheme
    val tokens = listOf(
        "primary" to m.primary, "on-primary" to m.onPrimary, "bubble-out" to c.bubbleOut, "bubble-in" to c.bubbleIn,
        "surface" to m.surface, "surface-2" to c.surface2, "text" to m.onSurface, "text-2" to c.text2,
        "outline" to c.outline, "live" to c.live, "stale" to c.stale, "warn" to c.warn, "danger" to c.danger, "infra" to c.infra,
    )
    Scaffold(topBar = { TopAppBar(title = { Text("Firepit design tokens") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = c.surface2)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Ember palette · Material 3 mapping. Same tokens as iOS; components follow Material 3 on Android.", style = MaterialTheme.typography.bodyMedium, color = c.text2)
            SectionLabel("Semantic colours")
            tokens.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (n, col) -> Swatch(n, col, Modifier.weight(1f)) }
                }
            }
            SectionLabel("Identity hues · avatar, sender name, map marker")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { c.identity.forEach { Box(Modifier.size(24.dp).background(it, CircleShape)) } }
            SectionLabel("Type · Roboto (system) · sp honours font scaling")
            Text("Headline 28 Bold", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Title 20 Semi Bold", style = MaterialTheme.typography.titleLarge)
            Text("Body 16 Regular · messages", style = MaterialTheme.typography.bodyLarge)
            Text("Secondary 14 Regular", style = MaterialTheme.typography.bodyMedium)
            Text("Caption 12 Regular · time, status", style = MaterialTheme.typography.labelSmall)
            SectionLabel("Message status · honest ticks")
            listOf(
                MsgStatus.QUEUED to "Sending (queued on phone)", MsgStatus.SENT to "Sent to your node",
                MsgStatus.HEARD to "Heard by the mesh (implicit ACK) — final for rooms", MsgStatus.DELIVERED to "Delivered to the peer's node — DMs only",
                MsgStatus.FAILED to "Failed — tap to retry",
            ).forEach { (s, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusGlyph(s, 20.dp)
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = c.text2)
                }
            }
        }
    }
}
