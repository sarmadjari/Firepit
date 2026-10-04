package com.getfirepit.core.designsystem.adaptive

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement

/**
 * A right-click does what a long press does, for a mouse or trackpad on a
 * desktop window (UX §6.11.9).
 *
 * Place it before the element's clickable. It takes the press on the way in,
 * so the clickable never sees a right-click as a tap.
 */
fun Modifier.onSecondaryClick(action: () -> Unit): Modifier = this then SecondaryClickElement(action)

private data class SecondaryClickElement(val action: () -> Unit) : ModifierNodeElement<SecondaryClickNode>() {
    override fun create() = SecondaryClickNode(action)

    override fun update(node: SecondaryClickNode) {
        node.action = action
    }
}

private class SecondaryClickNode(var action: () -> Unit) : DelegatingNode() {
    init {
        delegate(
            SuspendingPointerInputModifierNode {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            action()
                        }
                    }
                }
            },
        )
    }
}
