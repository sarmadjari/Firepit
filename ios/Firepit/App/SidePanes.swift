import FirepitModel
import SwiftUI

/// Which side was used last, so going back to one pane shows that one (UX §6.11.5).
enum Side {
    case chat
    case map
}

/// The chat side and the map side next to each other, with the divider between them (UX §6.11.3, §6.11.4,
/// §6.11.11). Ported from android/app/…/ui/SidePanes.kt.
///
/// Both sides keep their place in the view tree for as long as the split lasts. Swapping sides flips the row's
/// direction rather than reordering it, so neither side is rebuilt.
///
/// Dragging the divider shows where it will land without resizing either side. On release, and whenever a side opens
/// or closes, the chat side moves to its new width over 250 ms (UX §6.11.11). The map takes its new size once: at the
/// start when it grows, under the chat side that still covers it, or at the end when it shrinks, because a map
/// redrawn on every frame stutters.
struct SidePanes<Chat: View, Map: View>: View {
    let layout: PaneLayout.SideBySide
    let window: WindowShape
    let chatMin: Double
    /// Where the chat side's width starts when the split opens: the whole window from Chat only, nothing from Map only.
    var enterFrom: Double?
    /// A side being closed: the chat side moves over it, then `onClosed` lets the shell show one pane.
    var closing: Side?
    var onClosed: () -> Void = {}
    let onSettle: (DividerSettle) -> Void
    let onReset: () -> Void
    let onSwapSides: () -> Void
    @ViewBuilder let chat: () -> Chat
    @ViewBuilder let map: () -> Map

    @Environment(\.layoutDirection) private var direction
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// The chat side's width the divider would leave if let go now; nil while untouched, and back to nil by
    /// itself when a drag is cancelled.
    @GestureState private var dragging: Double?
    @State private var landings = 0
    /// What shows now, which moves towards the layout's widths.
    @State private var chatShown: Double?
    @State private var mapShown: Double?

    private var chatFirst: Bool { layout.mapSide == .end }
    private var goal: Double {
        switch closing {
        case .map?: window.width
        case .chat?: 0
        case nil: layout.chatWidth
        }
    }
    private var shownChat: Double { chatShown ?? enterFrom ?? layout.chatWidth }
    private var shownMap: Double { mapShown ?? max(0, window.width - (enterFrom ?? layout.chatWidth) - layout.gap) }
    private var startWidth: Double { chatFirst ? shownChat : window.width - shownChat }

    var body: some View {
        // Each side is anchored to its own outer edge, the chat side on top, so a map wider than what shows slides
        // under it rather than past the window.
        ZStack {
            map()
                .frame(width: shownMap)
                // The keyboard belongs to the chat side: the map is covered, not squeezed (UX §6.11.8).
                .ignoresSafeArea(.keyboard)
                .environment(\.layoutDirection, direction)
                .accessibilityElement(children: .contain)
                .accessibilityLabel(Text("Map"))
                .frame(maxWidth: .infinity, alignment: .trailing)
            chat()
                .frame(width: shownChat)
                .environment(\.layoutDirection, direction)
                .accessibilityElement(children: .contain)
                .accessibilityLabel(Text("Chat"))
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(FirepitColors.surface)
        // Flipped, the chat side goes to the end and the map to the start without moving either view.
        .environment(\.layoutDirection, chatFirst ? direction : direction.flipped)
        .onChange(of: goal, initial: true) { _, next in move(to: next) }
        .onChange(of: window.width) { move(to: goal) }
        .overlay(alignment: .leading) {
            if layout.gap == 0 {
                FirepitColors.outline
                    .frame(width: 1)
                    .padding(.leading, startWidth)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
            }
        }
        .overlay(alignment: .leading) {
            if !layout.dividerLocked {
                handle
                    .padding(.leading, max(0, shownAt - Self.touchWidth / 2))
            }
        }
        .sensoryFeedback(.impact(weight: .light), trigger: landings)
    }

    /// Moves the chat side to `next`, resizing the map once, and tells the shell when a side has finished closing.
    private func move(to next: Double) {
        let finalMap = max(0, window.width - next - layout.gap)
        if next < shownChat { mapShown = finalMap }
        let finish = {
            mapShown = finalMap
            if closing != nil { onClosed() }
        }
        guard !reduceMotion, abs(next - shownChat) > 0.5 else {
            chatShown = next
            finish()
            return
        }
        withAnimation(.timingCurve(0.05, 0.7, 0.1, 1, duration: 0.25)) {
            chatShown = next
        } completion: {
            finish()
        }
    }

    /// Where the divider shows, measured from the start edge.
    private var shownAt: Double {
        guard let dragging else { return startWidth }
        return chatFirst ? dragging : window.width - dragging
    }

    private var share: Double { layout.chatWidth / window.width }

    private var handle: some View {
        ZStack {
            if dragging != nil {
                Color.accentColor.frame(width: 2)
            }
            Capsule()
                .fill(FirepitColors.textSecondary.opacity(0.5))
                .frame(width: 4, height: 48)
        }
        .frame(width: Self.touchWidth)
        .frame(maxHeight: .infinity)
        .contentShape(Rectangle())
        .ignoresSafeArea()
        .gesture(
            // Global: the handle moves with the drag, and a local space moving with it would halve every step.
            DragGesture(minimumDistance: 2, coordinateSpace: .global)
                .updating($dragging) { value, state, _ in state = chatWidth(after: value) }
                .onEnded { value in
                    landings += 1
                    onSettle(PaneLayouts.settle(chatWidth(after: value), window, layout, chatMin: chatMin))
                }
        )
        .onTapGesture(count: 2, perform: onReset)
        .accessibilityElement()
        .accessibilityLabel(Text("Divider"))
        .accessibilityHint(Text("Drag to give the chat or the map more room."))
        .accessibilityAdjustableAction { change in
            switch change {
            case .increment: wider.map { onSettle(.share($0)) }
            case .decrement: narrower.map { onSettle(.share($0)) }
            @unknown default: break
            }
        }
        .accessibilityAction(named: Text("Make the chat wider")) { wider.map { onSettle(.share($0)) } }
        .accessibilityAction(named: Text("Make the map wider")) { narrower.map { onSettle(.share($0)) } }
        .accessibilityAction(named: Text("Swap sides"), onSwapSides)
        .accessibilityAction(named: Text("Close the map")) { onSettle(.closeMap) }
    }

    /// The chat side's width a drag would leave. Measured along the reading direction, which right to left runs the
    /// other way on screen.
    private func chatWidth(after drag: DragGesture.Value) -> Double {
        let along = direction == .rightToLeft ? -drag.translation.width : drag.translation.width
        return min(max(layout.chatWidth + (chatFirst ? along : -along), 0), window.width)
    }

    private var wider: Double? { layout.anchors.first { $0 > share + 0.001 } }
    private var narrower: Double? { layout.anchors.last { $0 < share - 0.001 } }

    /// Wide enough to hit without aiming, overlapping both sides.
    private static var touchWidth: Double { 44 }
}

/// Map above, conversation below, either side of a fold across the screen (UX §6.11.3). Ported from
/// android/app/…/ui/SidePanes.kt. iOS reports such a fold only from iOS 27.1, so until then this is never chosen.
struct StackedPanes<Chat: View, Map: View>: View {
    let mapHeight: Double
    let gap: Double
    @ViewBuilder let chat: () -> Chat
    @ViewBuilder let map: () -> Map

    var body: some View {
        VStack(spacing: 0) {
            map()
                .frame(height: mapHeight)
                .ignoresSafeArea(.keyboard)
                .accessibilityElement(children: .contain)
                .accessibilityLabel(Text("Map"))
            if gap > 0 {
                FirepitColors.surface.frame(height: gap)
            }
            chat()
                .accessibilityElement(children: .contain)
                .accessibilityLabel(Text("Chat"))
        }
    }
}

extension LayoutDirection {
    fileprivate var flipped: LayoutDirection { self == .leftToRight ? .rightToLeft : .leftToRight }
}
