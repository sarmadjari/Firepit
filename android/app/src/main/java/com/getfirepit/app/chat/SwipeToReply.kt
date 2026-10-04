package com.getfirepit.app.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.getfirepit.core.designsystem.component.FirepitIcons
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Swipe a message towards the reading end to reply to it (UX §5.4), the
 * gesture most chat apps have taught. Long-press still replies, and is what
 * a screen reader announces.
 *
 * The message follows the finger a short way with a reply arrow behind it; a
 * tick says when letting go will reply, and it springs back either way.
 */
@Composable
fun SwipeToReply(onReply: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reply by rememberUpdatedState(onReply)
    val offset = remember { Animatable(0f) }
    val furthest = with(density) { FURTHEST.toPx() }
    val mark = with(density) { MARK.toPx() }
    var armed by remember { mutableStateOf(false) }

    Box(
        modifier.draggable(
            orientation = Orientation.Horizontal,
            // Towards the reading end, which right to left is leftwards on screen.
            reverseDirection = rtl,
            state = rememberDraggableState { delta ->
                val next = (offset.value + delta).coerceIn(0f, furthest)
                scope.launch { offset.snapTo(next) }
                val nowArmed = next >= mark
                if (nowArmed && !armed) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                armed = nowArmed
            },
            onDragStopped = {
                if (armed) reply()
                armed = false
                offset.animateTo(0f)
            },
        ),
    ) {
        if (offset.value > 0f) {
            Icon(
                painter = painterResource(FirepitIcons.Reply),
                contentDescription = null,
                tint = FirepitTheme.colors.textSecondary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = FirepitSpacing.m)
                    .size(20.dp)
                    .alpha((offset.value / mark).coerceIn(0f, 1f)),
            )
        }
        Box(Modifier.offset { IntOffset((if (rtl) -offset.value else offset.value).roundToInt(), 0) }) {
            content()
        }
    }
}

/** How far the message follows the finger, and how far it must go to reply. */
private val FURTHEST = 72.dp
private val MARK = 56.dp
