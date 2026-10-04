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
/// Dragging the divider shows where it will land without resizing either side; both take their new width once, on
/// release, because a map redrawn on every frame of a drag stutters.
struct SidePanes<Chat: View, Map: View>: View {
    let layout: PaneLayout.SideBySide
    let window: WindowShape
    let chatMin: Double
    let onSettle: (DividerSettle) -> Void
    let onReset: () -> Void
    let onSwapSides: () -> Void
    @ViewBuilder let chat: () -> Chat
    @ViewBuilder let map: () -> Map

    @Environment(\.layoutDirection) private var direction
    /// The chat side's width the divider would leave if let go now; nil while untouched, and back to nil by
    /// itself when a drag is cancelled.
    @GestureState private var dragging: Double?
    @State private var landings = 0

    private var chatFirst: Bool { layout.mapSide == .end }
    private var startWidth: Double { chatFirst ? layout.chatWidth : layout.mapWidth }

    var body: some View {
        HStack(spacing: 0) {
            chat()
                .frame(width: layout.chatWidth)
                .environment(\.layoutDirection, direction)
                .accessibilityElement(children: .contain)
                .accessibilityLabel(Text("Chat"))
            if layout.gap > 0 {
                FirepitColors.surface.frame(width: layout.gap)
            }
            map()
                .frame(width: layout.mapWidth)
                // The keyboard belongs to the chat side: the map is covered, not squeezed (UX §6.11.8).
                .ignoresSafeArea(.keyboard)
                .environment(\.layoutDirection, direction)
                .accessibilityElement(children: .contain)
                .accessibilityLabel(Text("Map"))
        }
        // Flipped, the row puts the map first without moving either view.
        .environment(\.layoutDirection, chatFirst ? direction : direction.flipped)
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
