package com.getfirepit.core.designsystem.theme

import androidx.compose.ui.graphics.Color

private const val IDENTITY_SLOTS = 12
private const val IDENTITY_SATURATION = 0.50f
private const val IDENTITY_LIGHTNESS_LIGHT = 0.42f
private const val IDENTITY_LIGHTNESS_DARK = 0.64f
private const val IDENTITY_LABEL_LIGHTNESS_DARK = 0.16f

private fun identityHue(nodeNum: Int): Float {
    val slot = ((nodeNum % IDENTITY_SLOTS) + IDENTITY_SLOTS) % IDENTITY_SLOTS
    return slot * (360f / IDENTITY_SLOTS)
}

/**
 * Stable per-person colour, derived from the node number so every device in a
 * room paints the same person the same way without exchanging anything.
 *
 * Colour never carries meaning on its own: the 2-character tag sits inside the
 * avatar and the full name is always in the row or marker label, so a hue
 * collision between two members is cosmetic.
 */
fun identityColorFor(nodeNum: Int, dark: Boolean): Color = Color.hsl(
    hue = identityHue(nodeNum),
    saturation = IDENTITY_SATURATION,
    lightness = if (dark) IDENTITY_LIGHTNESS_DARK else IDENTITY_LIGHTNESS_LIGHT,
)

/**
 * Label colour for the tag inside an identity avatar or map marker. The fill
 * inverts between themes — dark hue in light mode, light hue in dark mode — so
 * the label has to invert with it, as a deep tint of the same hue rather than
 * flat black.
 */
fun onIdentityColorFor(nodeNum: Int, dark: Boolean): Color = if (dark) {
    Color.hsl(
        hue = identityHue(nodeNum),
        saturation = IDENTITY_SATURATION,
        lightness = IDENTITY_LABEL_LIGHTNESS_DARK,
    )
} else {
    Color.White
}
