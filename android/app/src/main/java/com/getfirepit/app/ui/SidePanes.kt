package com.getfirepit.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.getfirepit.core.designsystem.adaptive.LocalPaneBounds
import com.getfirepit.core.designsystem.adaptive.PaneBounds
import com.getfirepit.core.designsystem.theme.FirepitTheme
import com.getfirepit.core.model.DividerSettle
import com.getfirepit.core.model.MapSide
import com.getfirepit.core.model.PaneLayout
import com.getfirepit.core.model.PaneLayouts
import com.getfirepit.core.model.WindowShape

/** Which side a touch landed on, so folding back to one pane shows the one used last (UX §6.11.5). */
enum class Side { CHAT, MAP }

/**
 * The chat side and the map side next to each other, with the divider between
 * them (UX §6.11.3, §6.11.4, §6.11.11).
 *
 * Each side is one node for as long as the split lasts, placed by padding
 * inside the whole window. Giving the chat the whole window, or swapping
 * sides, only changes that padding: nothing is moved to a new parent, so a
 * focused composer keeps its focus and its keyboard.
 *
 * Dragging the divider shows where it will go without resizing either side;
 * both take their new width once, on release. A map resized on every frame
 * redraws on every frame, and MapLibre's surface stalls the UI thread each time.
 */
@Composable
fun SidePanes(
    layout: PaneLayout.SideBySide,
    window: WindowShape,
    chatWholeWindow: Boolean,
    chat: @Composable () -> Unit,
    map: @Composable () -> Unit,
    onSettle: (DividerSettle) -> Unit,
    onReset: () -> Unit,
    onSwapSides: () -> Unit,
    onTouched: (Side) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatFirst = layout.mapSide == MapSide.END
    val startWidth = if (chatFirst) layout.chatWidth else layout.mapWidth
    val endStart = startWidth + layout.gap
    val whole = PaneBounds(start = 0.dp, width = window.width.dp, windowWidth = window.width.dp)
    val chatBounds = if (chatWholeWindow) {
        whole
    } else {
        PaneBounds(
            start = (if (chatFirst) 0f else endStart).dp,
            width = layout.chatWidth.dp,
            windowWidth = window.width.dp,
        )
    }
    val mapBounds = PaneBounds(
        start = (if (chatFirst) endStart else 0f).dp,
        width = layout.mapWidth.dp,
        windowWidth = window.width.dp,
    )

    Box(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalPaneBounds provides mapBounds) {
            Box(
                Modifier
                    .fillMaxSize()
                    .within(mapBounds)
                    .consumeWindowInsets(innerEdge(atStart = !chatFirst))
                    // Out of reach while the conversation covers it.
                    .then(if (chatWholeWindow) Modifier.clearAndSetSemantics {} else Modifier)
                    .touched { onTouched(Side.MAP) },
            ) { map() }
        }
        if (layout.gap > 0f && !chatWholeWindow) {
            Box(
                Modifier
                    .offset(x = startWidth.dp)
                    .width(layout.gap.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface),
            )
        }
        CompositionLocalProvider(LocalPaneBounds provides chatBounds) {
            // The keyboard belongs to the chat side: it lifts the composer, and
            // the map beside it is covered rather than squeezed.
            Box(
                Modifier
                    .fillMaxSize()
                    .within(chatBounds)
                    .consumeWindowInsets(if (chatWholeWindow) WindowInsets(0) else innerEdge(atStart = chatFirst))
                    .imePadding()
                    .touched { onTouched(Side.CHAT) },
            ) { chat() }
        }
        if (!chatWholeWindow) {
            if (layout.gap == 0f) {
                Box(
                    Modifier
                        .offset(x = startWidth.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(FirepitTheme.colors.outline),
                )
            }
            if (!layout.dividerLocked) {
                DividerHandle(layout, window, chatFirst, startWidth, onSettle, onReset, onSwapSides)
            }
        }
    }
}

/**
 * A side clears only the system bars on its own outer edges: a navigation bar
 * down one edge of the window is not the other side's.
 */
@Composable
private fun innerEdge(atStart: Boolean): WindowInsets =
    WindowInsets.safeDrawing.only(if (atStart) WindowInsetsSides.End else WindowInsetsSides.Start)

/** Places a pane inside a node that fills the window, measured from the start edge. */
private fun Modifier.within(bounds: PaneBounds): Modifier = padding(
    start = bounds.start,
    end = (bounds.windowWidth - bounds.start - bounds.width).coerceAtLeast(0.dp),
)

@Composable
private fun DividerHandle(
    layout: PaneLayout.SideBySide,
    window: WindowShape,
    chatFirst: Boolean,
    startWidth: Float,
    onSettle: (DividerSettle) -> Unit,
    onReset: () -> Unit,
    onSwapSides: () -> Unit,
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val haptics = LocalHapticFeedback.current
    val currentLayout by rememberUpdatedState(layout)
    val currentWindow by rememberUpdatedState(window)
    val settle by rememberUpdatedState(onSettle)
    // The chat side's width the divider would leave if let go now; null while untouched.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shownAt = dragging?.let { chatWidth -> if (chatFirst) chatWidth else window.width - chatWidth } ?: startWidth

    val share = layout.chatWidth / window.width
    val wider = layout.anchors.firstOrNull { it > share + 0.001f }
    val narrower = layout.anchors.lastOrNull { it < share - 0.001f }
    Box(
        Modifier
            .offset(x = (shownAt - HANDLE_TOUCH / 2).dp)
            .width(HANDLE_TOUCH.dp)
            .fillMaxHeight()
            .pointerInput(chatFirst, rtl) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = currentLayout.chatWidth },
                    onDragEnd = {
                        dragging?.let { chatWidth ->
                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            settle(PaneLayouts.settle(chatWidth, currentWindow, currentLayout))
                        }
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                ) { change, dx ->
                    change.consume()
                    val alongReading = (if (rtl) -dx else dx) / density.density
                    val now = dragging ?: currentLayout.chatWidth
                    dragging = (now + if (chatFirst) alongReading else -alongReading)
                        .coerceIn(0f, currentWindow.width)
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) }
            .semantics {
                contentDescription = "Divider. Drag to give the chat or the map more room."
                customActions = listOfNotNull(
                    wider?.let { anchor ->
                        CustomAccessibilityAction("Make the chat wider") {
                            onSettle(DividerSettle.Share(anchor))
                            true
                        }
                    },
                    narrower?.let { anchor ->
                        CustomAccessibilityAction("Make the map wider") {
                            onSettle(DividerSettle.Share(anchor))
                            true
                        }
                    },
                    CustomAccessibilityAction("Swap sides") {
                        onSwapSides()
                        true
                    },
                    CustomAccessibilityAction("Close the map") {
                        onSettle(DividerSettle.CloseMap)
                        true
                    },
                )
            },
    ) {
        if (dragging != null) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(width = 4.dp, height = 48.dp)
                .clip(CircleShape)
                .background(FirepitTheme.colors.textSecondary.copy(alpha = 0.5f)),
        )
    }
}

/**
 * Map above, conversation below, either side of a fold across the screen
 * (UX §6.11.3): the top half for looking, the bottom half for doing. While
 * the keyboard is up the conversation takes the whole window, the same node
 * grown rather than moved, so typing is not interrupted.
 */
@Composable
fun StackedPanes(
    layout: PaneLayout.Stacked,
    window: WindowShape,
    chatWholeWindow: Boolean,
    chat: @Composable () -> Unit,
    map: @Composable () -> Unit,
    onTouched: (Side) -> Unit,
    modifier: Modifier = Modifier,
) {
    val whole = PaneBounds(start = 0.dp, width = window.width.dp, windowWidth = window.width.dp)
    CompositionLocalProvider(LocalPaneBounds provides whole) {
        Box(modifier.fillMaxSize()) {
            // The map meets the status bar and the conversation the navigation
            // bar; neither pads for the bar at the other end of the window.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(layout.mapHeight.dp)
                    .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .then(if (chatWholeWindow) Modifier.clearAndSetSemantics {} else Modifier)
                    .touched { onTouched(Side.MAP) },
            ) { map() }
            if (layout.gap > 0f && !chatWholeWindow) {
                Box(
                    Modifier
                        .offset(y = layout.mapHeight.dp)
                        .fillMaxWidth()
                        .height(layout.gap.dp)
                        .background(MaterialTheme.colorScheme.surface),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = if (chatWholeWindow) 0.dp else (layout.mapHeight + layout.gap).dp)
                    .consumeWindowInsets(
                        if (chatWholeWindow) WindowInsets(0) else WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                    )
                    .imePadding()
                    .touched { onTouched(Side.CHAT) },
            ) { chat() }
        }
    }
}

/** Notes any touch inside without taking it from what sits there. */
private fun Modifier.touched(onTouch: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            onTouch()
        }
    }
}

/** Wide enough to hit without aiming, overlapping both sides. */
private const val HANDLE_TOUCH = 48f
