@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.getfirepit.app.ui.mock.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getfirepit.app.ui.components.outlineBorder
import com.getfirepit.app.ui.icons.FirepitIcons
import com.getfirepit.app.ui.theme.FirepitTheme

/** Illustrative QR (same deterministic pattern as the Figma frames). Always dark on white for scannability. */
@Composable
fun QrCanvas(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val n = 25; val m = size.width / n
        var seed = 20240909L
        fun rnd(): Double { seed = (seed * 1103515245L + 12345L) and 0x7fffffffL; return seed / 0x7fffffff.toDouble() }
        val dark = Color(0xFF1A1614)
        fun cell(x: Int, y: Int) = drawRect(dark, Offset(x * m, y * m), Size(m, m))
        fun finder(fx: Int, fy: Int) { for (y in 0..6) for (x in 0..6) { val edge = x == 0 || y == 0 || x == 6 || y == 6; val inner = x in 2..4 && y in 2..4; if (edge || inner) cell(fx + x, fy + y) } }
        finder(0, 0); finder(n - 7, 0); finder(0, n - 7)
        for (y in 0 until n) for (x in 0 until n) {
            val inF = (x < 8 && y < 8) || (x >= n - 8 && y < 8) || (x < 8 && y >= n - 8)
            if (!inF && rnd() < 0.42) cell(x, y)
        }
    }
}

@Composable
fun InviteScreen() {
    val colors = FirepitTheme.colors
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = {}) { Icon(FirepitIcons.ArrowBack, "Back") } },
                title = { Text("Invite to Camp") },
                actions = { TextButton(onClick = {}) { Text("Done", fontWeight = FontWeight.SemiBold) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface2),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    QrCanvas(Modifier.size(220.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(FirepitIcons.Clock, null, tint = Color(0xFFC2410C), modifier = Modifier.size(18.dp))
                        Text("Refreshes in 5 s", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF6B625C))
                    }
                }
            }
            Text(
                "Show this to people next to you. It refreshes every few seconds and only works while they are here.",
                style = MaterialTheme.typography.bodyLarge, color = colors.text2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HorizontalDivider(Modifier.weight(1f), color = colors.outline)
                Text("or", style = MaterialTheme.typography.bodyMedium, color = colors.text2)
                HorizontalDivider(Modifier.weight(1f), color = colors.outline)
            }
            Button(onClick = {}, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(FirepitIcons.Share, null, modifier = Modifier.size(20.dp))
                Text("  Share link", style = MaterialTheme.typography.titleMedium)
            }
            Card(colors = CardDefaults.cardColors(containerColor = colors.surface2, contentColor = MaterialTheme.colorScheme.onSurface), border = outlineBorder(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PIN FOR THIS LINK", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.text2)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("4821  9306", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                        Icon(FirepitIcons.Copy, "Copy", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    }
                    Text(
                        "Send the PIN separately — a link alone can't open the room. Expires in 15 minutes or once used.",
                        style = MaterialTheme.typography.bodyMedium, color = colors.text2, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
