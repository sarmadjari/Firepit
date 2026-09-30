@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.getfirepit.app.ui.mock.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getfirepit.app.ui.components.FirepitNavBar
import com.getfirepit.app.ui.components.FirepitTab
import com.getfirepit.app.ui.components.outlineBorder
import com.getfirepit.app.ui.icons.FirepitIcons
import com.getfirepit.app.ui.theme.FirepitTheme

private data class MapPalette(val ground: Color, val road: Color, val water: Color, val park: Color, val building: Color)

@Composable
private fun mapPalette() = if (isSystemInDarkTheme())
    MapPalette(Color(0xFF1C1A17), Color(0xFF2C2925), Color(0xFF1E2C38), Color(0xFF22301F), Color(0xFF2A2622))
else MapPalette(Color(0xFFEEEAE4), Color.White, Color(0xFFD5E5F0), Color(0xFFD6E3CF), Color(0xFFDFD9D2))

/** Stylised basemap stand-in (UX doc §9.1 basemap literals). Replaced by MapLibre + OpenFreeMap in the real app. */
@Composable
fun MapCanvas(modifier: Modifier = Modifier) {
    val p = mapPalette()
    Canvas(modifier.fillMaxSize().background(p.ground)) {
        val d = density
        fun dp(v: Float) = v * d
        drawOval(p.water, Offset(dp(250f), dp(40f)), Size(dp(220f), dp(160f)))
        drawOval(p.park, Offset(dp(20f), dp(330f)), Size(dp(200f), dp(130f)))
        listOf(150f, 320f, 470f).forEach { y -> drawRect(p.road, Offset(0f, dp(y)), Size(size.width, dp(5f))) }
        listOf(110f, 260f).forEach { x -> drawRect(p.road, Offset(dp(x), 0f), Size(dp(5f), size.height)) }
        listOf(Offset(130f, 175f) to Size(50f, 30f), Offset(190f, 180f) to Size(40f, 26f), Offset(280f, 340f) to Size(60f, 34f), Offset(30f, 190f) to Size(60f, 40f)).forEach { (o, s) ->
            drawRoundRect(p.building, Offset(dp(o.x), dp(o.y)), Size(dp(s.width), dp(s.height)), CornerRadius(dp(3f)))
        }
    }
}

@Composable
private fun Marker(tag: String, caption: String, color: Color, live: Boolean, stale: Boolean = false, icon: Boolean = false) {
    val colors = FirepitTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(52.dp).background(if (live) colors.live.copy(alpha = 0.28f) else if (stale) colors.stale.copy(alpha = 0.25f) else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp).background(color, CircleShape).border(3.dp, colors.surface2, CircleShape), contentAlignment = Alignment.Center) {
                if (icon) Icon(FirepitIcons.House, null, tint = Color.White, modifier = Modifier.size(22.dp))
                else Text(tag, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
        Surface(shape = RoundedCornerShape(8.dp), color = colors.surface2, border = outlineBorder()) {
            Text(caption, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, color = if (stale) colors.text2 else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun MapScreen(showControls: Boolean = true) {
    val colors = FirepitTheme.colors
    Scaffold(bottomBar = { FirepitNavBar(FirepitTab.Map) {} }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            MapCanvas()
            fun Modifier.at(x: Dp, y: Dp) = this.offset(x, y)
            Box(Modifier.at(150.dp, 200.dp)) { Marker("SA", "Sam", colors.identity[7], live = true) }
            Box(Modifier.at(282.dp, 380.dp)) { Marker("LE", "Lena", colors.identity[3], live = true) }
            Box(Modifier.at(55.dp, 430.dp).alpha(0.6f)) { Marker("AL", "Ali · 2 h", colors.identity[1], live = false, stale = true) }
            Box(Modifier.at(215.dp, 250.dp)) { Marker("", "Camp Base", colors.infra, live = false, icon = true) }
            Column(Modifier.at(90.dp, 300.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(FirepitIcons.PinFill, null, tint = colors.danger, modifier = Modifier.size(32.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = colors.surface2, border = outlineBorder()) {
                    Text("Tent", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            Box(Modifier.at(175.dp, 470.dp).size(60.dp).background(Color(0x261A73E8), CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(20.dp).background(Color(0xFF1A73E8), CircleShape).border(3.dp, Color.White, CircleShape))
            }
            if (showControls) {
                AssistChip(
                    onClick = {}, label = { Text("All rooms") },
                    trailingIcon = { Icon(FirepitIcons.ChevronDown, null, modifier = Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = colors.surface2),
                    border = outlineBorder(), modifier = Modifier.at(16.dp, 12.dp),
                )
                Column(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallFloatingActionButton(onClick = {}, containerColor = colors.surface2, contentColor = MaterialTheme.colorScheme.onSurface) { Icon(FirepitIcons.Locate, "My location") }
                    SmallFloatingActionButton(onClick = {}, containerColor = colors.surface2, contentColor = MaterialTheme.colorScheme.onSurface) { Icon(FirepitIcons.Download, "Offline map") }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExtendedFloatingActionButton(
                        onClick = {}, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                        icon = { Icon(FirepitIcons.Locate, null) }, text = { Text("Share my location", fontWeight = FontWeight.SemiBold) },
                    )
                    Surface(shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), color = colors.surface2, contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            BottomSheetDefaults.DragHandle()
                            Text("7 people · 2 live · 1 pin", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

