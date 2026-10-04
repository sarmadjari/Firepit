package com.getfirepit.core.designsystem.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.getfirepit.core.designsystem.component.FirepitIcons
import com.getfirepit.core.model.PaneArrangement
import com.getfirepit.core.model.WindowFold
import com.getfirepit.core.model.WindowShape

/**
 * What a screen needs to know about the wide-screen layout around it (UX §6.11).
 *
 * Provided by the shell; null on a window too narrow to ever show two panes,
 * so phone screens draw nothing extra.
 */
@Stable
class WideScreenControls(
    /** The window could show two panes: the layout menu has something to offer. */
    val canSplit: Boolean,
    val arrangement: PaneArrangement,
    val onArrange: (PaneArrangement) -> Unit,
    /** Null while swapping means nothing: half-folded, the map is always on top. */
    val onSwapSides: (() -> Unit)?,
    /** Set while two panes show and no bar or rail does: Settings opens from the chat side. */
    val onOpenSettings: (() -> Unit)?,
)

val LocalWideScreen = compositionLocalOf<WideScreenControls?> { null }

/**
 * The layout button ◫, and Settings ⚙ where [withSettings] asks for it and
 * the shell has no bar or rail to carry it. Placed in a top bar's actions.
 */
@Composable
fun WideScreenActions(withSettings: Boolean = false) {
    val controls = LocalWideScreen.current ?: return
    if (controls.canSplit) {
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) {
                Icon(painterResource(FirepitIcons.Split), contentDescription = LAYOUT_LABEL)
            }
            LayoutMenu(controls = controls, expanded = open, onDismiss = { open = false })
        }
    }
    if (withSettings) {
        controls.onOpenSettings?.let { openSettings ->
            IconButton(onClick = openSettings) {
                Icon(painterResource(FirepitIcons.Settings), contentDescription = "Settings")
            }
        }
    }
}

/**
 * What ◫ opens: the three arrangements with the current one ticked, then Swap
 * sides while two panes show side by side. Anchored to whichever button opened it.
 */
@Composable
fun LayoutMenu(controls: WideScreenControls, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        PaneArrangement.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label) },
                trailingIcon = if (option == controls.arrangement) {
                    { Icon(painterResource(FirepitIcons.Tick), contentDescription = "Chosen") }
                } else {
                    null
                },
                onClick = {
                    onDismiss()
                    controls.onArrange(option)
                },
            )
        }
        val swap = controls.onSwapSides
        if (swap != null && controls.arrangement == PaneArrangement.CHAT_AND_MAP) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Swap sides") },
                onClick = {
                    onDismiss()
                    swap()
                },
            )
        }
    }
}

/** How ◫ is announced wherever it sits. */
const val LAYOUT_LABEL = "Layout"

/**
 * Where the pane a screen sits in lies across the window, measured from the
 * start edge in reading order, so a sheet it opens covers that pane rather
 * than both.
 */
@Immutable
data class PaneBounds(val start: Dp, val width: Dp, val windowWidth: Dp)

val LocalPaneBounds = compositionLocalOf<PaneBounds?> { null }

/**
 * For a modal sheet's modifier: keeps it over the pane that opened it
 * (UX §6.11.6). Pair with [paneSheetMaxWidth]. The scrim still dims the whole
 * window, because the sheet is still modal.
 */
@Composable
fun Modifier.withinPane(): Modifier {
    val bounds = LocalPaneBounds.current ?: return this
    val end = (bounds.windowWidth - bounds.start - bounds.width).coerceAtLeast(0.dp)
    return padding(start = bounds.start, end = end)
}

/** A modal sheet's widest, so that inside a pane it is exactly as wide as the pane. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun paneSheetMaxWidth(): Dp = LocalPaneBounds.current?.width ?: BottomSheetDefaults.SheetMaxWidth

/**
 * The window as the layout rule sees it: its size in dp and the fold, if
 * there is one, measured from the start edge in reading order.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun currentWindowShape(): WindowShape {
    val posture = currentWindowAdaptiveInfoV2().windowPosture
    val size = currentWindowDpSize()
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val width = size.width.value
    val fold = posture.hingeList.firstOrNull()?.let { hinge ->
        with(density) {
            if (hinge.isVertical) {
                val left = hinge.bounds.left.toDp().value
                val right = hinge.bounds.right.toDp().value
                WindowFold(
                    vertical = true,
                    separating = hinge.isSeparating,
                    start = if (rtl) width - right else left,
                    end = if (rtl) width - left else right,
                )
            } else {
                WindowFold(
                    vertical = false,
                    separating = hinge.isSeparating,
                    start = hinge.bounds.top.toDp().value,
                    end = hinge.bounds.bottom.toDp().value,
                )
            }
        }
    }
    return WindowShape(width, size.height.value, fold)
}

/**
 * The list-detail split for Chats when it is the chat side of a wider layout:
 * measured by the pane, not the window, or it would split again inside a pane
 * half the window's width. The fold is the shell's business by then.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun chatSideDirective(listBesideConversation: Boolean): PaneScaffoldDirective =
    foldAwarePaneDirective().copy(
        maxHorizontalPartitions = if (listBesideConversation) 2 else 1,
        excludedBounds = emptyList(),
    )
