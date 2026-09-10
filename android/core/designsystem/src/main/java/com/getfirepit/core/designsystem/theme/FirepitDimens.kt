package com.getfirepit.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object FirepitSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Screen side margin. */
    val screenMargin = 16.dp

    /**
     * Material's minimum is 48dp and the UX spec asks for 44pt; 48 satisfies
     * both, so every interactive target uses this floor.
     */
    val minTouchTarget = 48.dp

    val listRowHeight = 68.dp
    val avatarSize = 48.dp

    val chipCorner = 10.dp
    val cardCorner = 14.dp

    val bubblePaddingVertical = 10.dp
    val bubblePaddingHorizontal = 14.dp

    /** Chat bubbles never exceed this share of the pane width, at any screen size. */
    const val BUBBLE_MAX_WIDTH_FRACTION = 0.78f
}

val FirepitShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Chat bubble corner radius, per UX spec §9.3. */
val BubbleShape = RoundedCornerShape(16.dp)

/** Bottom sheets and the invite card. */
val SheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
