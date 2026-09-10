package com.getfirepit.core.designsystem.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private const val IDENTITY_SLOTS = 12
private const val IDENTITY_SATURATION = 0.50f
private const val IDENTITY_LIGHTNESS_LIGHT = 0.42f
private const val IDENTITY_LIGHTNESS_DARK = 0.64f
private const val IDENTITY_LABEL_LIGHTNESS_DARK = 0.16f

/**
 * The slots offered as a choice.
 *
 * Ten of the twelve: the two mustard hues either side of 60° read as muddy at
 * this lightness and are close enough to each other to be confusable, which is
 * the one thing a colour picked to tell people apart must not be.
 */
val IDENTITY_CHOICES: List<Int> = listOf(0, 1, 3, 4, 5, 6, 7, 8, 9, 10)

private fun identityHue(nodeNum: Int): Float = slotHue(slotFor(nodeNum))

private fun slotHue(slot: Int): Float = slot * (360f / IDENTITY_SLOTS)

private fun slotFor(nodeNum: Int): Int =
    ((nodeNum % IDENTITY_SLOTS) + IDENTITY_SLOTS) % IDENTITY_SLOTS

/** The colour of one slot, for drawing the picker. */
fun identityColorForSlot(slot: Int, dark: Boolean): Color = Color.hsl(
    hue = slotHue(slot),
    saturation = IDENTITY_SATURATION,
    lightness = if (dark) IDENTITY_LIGHTNESS_DARK else IDENTITY_LIGHTNESS_LIGHT,
)

/**
 * Stable per-person colour, derived from the node number so every device in a
 * room paints the same person the same way without exchanging anything.
 *
 * [slot] overrides that for one person on one phone. Nothing carries the choice
 * over the air, so it changes only what its owner sees.
 *
 * Colour never carries meaning on its own: the 2-character tag sits inside the
 * avatar and the full name is always in the row or marker label, so a hue
 * collision between two members is cosmetic.
 */
fun identityColorFor(nodeNum: Int, dark: Boolean, slot: Int? = null): Color = Color.hsl(
    hue = slot?.let(::slotHue) ?: identityHue(nodeNum),
    saturation = IDENTITY_SATURATION,
    lightness = if (dark) IDENTITY_LIGHTNESS_DARK else IDENTITY_LIGHTNESS_LIGHT,
)

/**
 * Label colour for the tag inside an identity avatar or map marker. The fill
 * inverts between themes — dark hue in light mode, light hue in dark mode — so
 * the label has to invert with it, as a deep tint of the same hue rather than
 * flat black.
 */
fun onIdentityColorFor(nodeNum: Int, dark: Boolean, slot: Int? = null): Color = if (dark) {
    Color.hsl(
        hue = slot?.let(::slotHue) ?: identityHue(nodeNum),
        saturation = IDENTITY_SATURATION,
        lightness = IDENTITY_LABEL_LIGHTNESS_DARK,
    )
} else {
    Color.White
}

/** Slot chosen per node, provided by the app so avatars can honour a personal pick. */
val LocalIdentitySlots = staticCompositionLocalOf { emptyMap<Int, Int>() }
