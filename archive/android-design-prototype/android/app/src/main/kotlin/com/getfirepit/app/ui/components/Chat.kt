@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.getfirepit.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.getfirepit.app.ui.icons.FirepitIcons
import com.getfirepit.app.ui.mock.MockMessage
import com.getfirepit.app.ui.theme.FirepitTheme

@Composable
fun MessageBubble(m: MockMessage) {
    val colors = FirepitTheme.colors
    val shape = if (m.out) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = if (m.out) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = shape,
            color = if (m.out) colors.bubbleOut else colors.bubbleIn,
            border = when {
                m.alert -> BorderStroke(1.5.dp, colors.warn)
                m.out -> null
                else -> BorderStroke(1.dp, colors.outline)
            },
            modifier = Modifier.widthIn(max = 290.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (m.alert) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(FirepitIcons.Bell, null, tint = colors.warn, modifier = Modifier.size(16.dp))
                        Text("ALERT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = colors.warn)
                    }
                }
                m.sender?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.identity[m.identity % 12])
                }
                if (m.location) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(FirepitIcons.Pin, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Column {
                            Text(m.text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text("120 m NE · 2 min ago", style = MaterialTheme.typography.bodyMedium, color = colors.text2)
                        }
                    }
                } else {
                    Text(m.text, style = MaterialTheme.typography.bodyLarge)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (m.location) {
                        Text("Open map", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                    Box(Modifier.weight(1f))
                    Text(m.time, style = MaterialTheme.typography.labelSmall, color = colors.text2)
                    m.status?.let { StatusGlyph(it, 15.dp) }
                }
            }
        }
    }
}

@Composable
fun SystemChip(text: String) {
    val colors = FirepitTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = colors.surface2, border = BorderStroke(1.dp, colors.outline)) {
            Text(text, Modifier.padding(horizontal = 12.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = colors.text2)
        }
    }
}

/** ＋ · Message pill · ⚡ · send (UX §5.4). */
@Composable
fun Composer(modifier: Modifier = Modifier) {
    val colors = FirepitTheme.colors
    Surface(color = colors.surface2, contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 0.dp, shadowElevation = 4.dp, modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = {}) { Icon(FirepitIcons.Plus, "Attach", tint = MaterialTheme.colorScheme.primary) }
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, colors.outline),
                modifier = Modifier.weight(1f).height(44.dp),
            ) {
                Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    Text("Message", style = MaterialTheme.typography.bodyLarge, color = colors.stale)
                }
            }
            IconButton(onClick = {}) { Icon(FirepitIcons.Bolt, "Quick replies", tint = colors.text2) }
            FilledIconButton(onClick = {}) { Icon(FirepitIcons.Send, "Send") }
        }
    }
}
