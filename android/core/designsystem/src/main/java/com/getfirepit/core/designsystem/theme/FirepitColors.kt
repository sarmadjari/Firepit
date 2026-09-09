package com.getfirepit.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Ember palette. Terracotta primary over warm neutrals — campfire and canvas,
 * deliberately distinct from the greens, blues and purples every other
 * messenger uses. Status hues stay conventional so brand never competes with
 * meaning.
 *
 * Tokens that Material 3 has no slot for (bubbles, mesh status, infrastructure)
 * live here and are reached through [LocalFirepitColors].
 */
@Immutable
data class FirepitColors(
    val bubbleOut: Color,
    val bubbleIn: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    /** Live position markers and the connected node dot. */
    val live: Color,
    /** Stale positions and unknown state. Never the only signal — always paired with an age label. */
    val stale: Color,
    /** Alerts, mesh-busy, node-restarting. Deep gold, distinct from the terracotta primary. */
    val warn: Color,
    /** Failed sends, leave and remove. Crimson, distinct from the primary. */
    val danger: Color,
    /** Base and Router node markers. */
    val infra: Color,
    val mapGround: Color,
    val mapRoad: Color,
    val mapWater: Color,
    val mapPark: Color,
    val isDark: Boolean,
)

val EmberLightColors = FirepitColors(
    bubbleOut = Color(0xFFFBE3D6),
    bubbleIn = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF1A1614),
    textSecondary = Color(0xFF6B625C),
    live = Color(0xFF2BB673),
    stale = Color(0xFFA39E98),
    warn = Color(0xFF9A6B00),
    danger = Color(0xFFC62B4A),
    infra = Color(0xFF4A5B8C),
    mapGround = Color(0xFFEEEAE4),
    mapRoad = Color(0xFFFFFFFF),
    mapWater = Color(0xFFD5E5F0),
    mapPark = Color(0xFFD6E3CF),
    isDark = false,
)

val EmberDarkColors = FirepitColors(
    bubbleOut = Color(0xFF3A2418),
    bubbleIn = Color(0xFF24211E),
    textPrimary = Color(0xFFF1ECE7),
    textSecondary = Color(0xFFA39C95),
    live = Color(0xFF4ED69A),
    stale = Color(0xFF6F6963),
    warn = Color(0xFFF2C94C),
    danger = Color(0xFFF27D8E),
    infra = Color(0xFF93A6DF),
    mapGround = Color(0xFF1C1A17),
    mapRoad = Color(0xFF2C2925),
    mapWater = Color(0xFF1E2C38),
    mapPark = Color(0xFF22301F),
    isDark = true,
)

internal object EmberPalette {
    val PrimaryLight = Color(0xFFC2410C)
    val OnPrimaryLight = Color(0xFFFFFFFF)
    val SurfaceLight = Color(0xFFFAF7F3)
    val Surface2Light = Color(0xFFFFFFFF)
    val OutlineLight = Color(0xFFE8E0D9)

    val PrimaryDark = Color(0xFFF0875A)
    val OnPrimaryDark = Color(0xFF3B1400)
    val SurfaceDark = Color(0xFF141210)
    val Surface2Dark = Color(0xFF1E1B18)
    val OutlineDark = Color(0xFF2E2926)
}
