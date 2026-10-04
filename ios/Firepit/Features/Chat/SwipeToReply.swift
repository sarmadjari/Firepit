import SwiftUI

/// Swipe a message towards the reading end to reply to it (UX §5.4), the gesture most chat apps have taught. The
/// context menu and the Reply accessibility action still reply. Ported from android/app/…/chat/SwipeToReply.kt.
///
/// The message follows the finger a short way with a reply arrow behind it; a tap of haptics says when letting go will
/// reply, and it springs back either way. Simultaneous with scrolling, and only a mostly sideways drag counts, so the
/// list still scrolls under a finger that wanders.
struct SwipeToReply: ViewModifier {
    let onReply: () -> Void

    @Environment(\.layoutDirection) private var direction
    @GestureState private var along: Double = 0
    @State private var landings = 0

    func body(content: Content) -> some View {
        let shown = min(max(along, 0), Self.furthest)
        content
            // Drag translations and offsets run left to right on screen whatever the reading direction.
            .offset(x: direction == .rightToLeft ? -shown : shown)
            .background(alignment: .leading) {
                if shown > 0 {
                    Image(systemName: "arrowshape.turn.up.left")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(FirepitColors.textSecondary)
                        .opacity(min(shown / Self.mark, 1))
                        .padding(.leading, FirepitSpacing.m)
                        .accessibilityHidden(true)
                }
            }
            .animation(.snappy, value: along == 0)
            .sensoryFeedback(.impact(weight: .light), trigger: shown >= Self.mark) { _, armed in armed }
            .simultaneousGesture(
                DragGesture(minimumDistance: 16)
                    .updating($along) { value, state, _ in
                        guard abs(value.translation.width) > abs(value.translation.height) * 1.5 else { return }
                        state = alongReading(value)
                    }
                    .onEnded { value in
                        let sideways = abs(value.translation.width) > abs(value.translation.height) * 1.5
                        if sideways && alongReading(value) >= Self.mark { onReply() }
                    }
            )
    }

    private func alongReading(_ value: DragGesture.Value) -> Double {
        direction == .rightToLeft ? -value.translation.width : value.translation.width
    }

    /// How far the message follows the finger, and how far it must go to reply.
    private static var furthest: Double { 72 }
    private static var mark: Double { 56 }
}
