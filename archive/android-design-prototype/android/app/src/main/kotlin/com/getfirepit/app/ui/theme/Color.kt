package com.getfirepit.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Ember palette — tokens from docs/meshchat-ux-design.md §9.1. Light and dark values are the single source of
 * truth for the app; never hard-code a colour elsewhere.
 */
object EmberLight {
    val primary = Color(0xFFC2410C)
    val onPrimary = Color(0xFFFFFFFF)
    val bubbleOut = Color(0xFFFBE3D6)
    val bubbleIn = Color(0xFFFFFFFF)
    val surface = Color(0xFFFAF7F3)
    val surface2 = Color(0xFFFFFFFF)
    val text = Color(0xFF1A1614)
    val text2 = Color(0xFF6B625C)
    val outline = Color(0xFFE8E0D9)
    val live = Color(0xFF2BB673)
    val stale = Color(0xFFA39E98)
    val warn = Color(0xFF9A6B00)
    val danger = Color(0xFFC62B4A)
    val infra = Color(0xFF4A5B8C)
    val calloutWarn = Color(0xFFF1EADB)   // warn @14% over surface-2, flattened
}

object EmberDark {
    val primary = Color(0xFFF0875A)
    val onPrimary = Color(0xFF3B1400)
    val bubbleOut = Color(0xFF3A2418)
    val bubbleIn = Color(0xFF24211E)
    val surface = Color(0xFF141210)
    val surface2 = Color(0xFF1E1B18)
    val text = Color(0xFFF1ECE7)
    val text2 = Color(0xFFA39C95)
    val outline = Color(0xFF2E2926)
    val live = Color(0xFF4ED69A)
    val stale = Color(0xFF6F6963)
    val warn = Color(0xFFF2C94C)
    val danger = Color(0xFFF27D8E)
    val infra = Color(0xFF93A6DF)
    val calloutWarn = Color(0xFF443A21)   // warn @18% over surface-2, flattened
}

/** Colours Material3's ColorScheme has no slot for. Read them via [LocalFirepitColors]. */
@Immutable
data class FirepitColors(
    val bubbleOut: Color,
    val bubbleIn: Color,
    val surface2: Color,
    val text2: Color,
    val outline: Color,
    val live: Color,
    val stale: Color,
    val warn: Color,
    val danger: Color,
    val infra: Color,
    val calloutWarn: Color,
    /** 12 identity hues; pick with `identity[nodeNum % 12]`. S 50 %, L 42 % (light) / 64 % (dark). */
    val identity: List<Color>,
)

val LocalFirepitColors = staticCompositionLocalOf<FirepitColors> { error("FirepitTheme not applied") }

/** HSL → Color; hue in degrees, s/l in 0..1. Mirrors the formula used for the Figma variables. */
internal fun hsl(h: Float, s: Float, l: Float): Color {
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}

internal fun identityPalette(lightness: Float): List<Color> = List(12) { i -> hsl(i * 30f, 0.5f, lightness) }
