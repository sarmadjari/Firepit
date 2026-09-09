package com.getfirepit.core.designsystem.adaptive

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.HingePolicy
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

/**
 * The narrowest width Firepit must stay usable at: a folded cover display.
 * Nothing may declare a fixed width above this.
 */
val MinimumSupportedWidth: Dp = 320.dp

/**
 * Pane split that never lands content across a fold.
 *
 * Two deviations from the Material defaults, both deliberate:
 *
 * - Two panes from **medium** width (600dp) rather than expanded (840dp). An
 *   unfolded Galaxy Fold inner display is ~752dp, so the stock threshold would
 *   leave the largest phones we support permanently single-pane.
 * - The hinge is treated as a boundary to sit at, not draw through, so a
 *   book-posture split never lands content in the fold.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun foldAwarePaneDirective(
    adaptiveInfo: WindowAdaptiveInfo = currentWindowAdaptiveInfoV2(),
): PaneScaffoldDirective {
    val base = calculatePaneScaffoldDirective(
        windowAdaptiveInfo = adaptiveInfo,
        verticalHingePolicy = HingePolicy.AvoidSeparating,
    )
    val wideEnoughForTwoPanes = adaptiveInfo.windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    return if (wideEnoughForTwoPanes && base.maxHorizontalPartitions < 2) {
        base.copy(maxHorizontalPartitions = 2)
    } else {
        base
    }
}
