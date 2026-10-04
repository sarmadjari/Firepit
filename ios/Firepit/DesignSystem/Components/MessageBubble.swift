import FirepitModel
import SwiftUI

/// The message a bubble is replying to, shown as a quote inside it.
nonisolated struct QuotedMessage: Hashable, Sendable {
    let senderName: String
    let senderNodeNum: Int32?
    let text: String
}

/// A chat bubble, ported from android/core/designsystem/…/component/MessageBubble.kt.
///
/// Width is capped as a fraction of the available space rather than a fixed width, so it behaves the same on every
/// screen size. `isFirstInGroup` and `isLastInGroup` describe a run of consecutive messages from one sender: only the
/// first repeats the name and only the last gets the tail corner, so a burst reads as one block instead of four
/// separate shouts.
struct MessageBubble: View {
    let text: String
    let time: String
    let isOutgoing: Bool
    var senderName: String?
    var senderNodeNum: Int32?
    var status: MessageStatus?
    var footnote: String?
    var isAlert = false
    var quoted: QuotedMessage?
    var onQuoteTap: (() -> Void)?
    /// Runs to paint as UTF-16 offsets into `text` (Kotlin string indices), decided by the caller that owns the search.
    var highlight: [Range<Int>] = []
    var isFirstInGroup = true
    var isLastInGroup = true
    /// The sender's radio signed it and ours verified the signature (Meshtastic 2.8).
    var signed = false

    @Environment(\.identitySlots) private var identitySlots

    var body: some View {
        BubbleWidthLayout(isOutgoing: isOutgoing) {
            BubbleStack(spacing: 3) {
                if isAlert {
                    HStack(spacing: FirepitSpacing.xs) {
                        Image(icon: .bell)
                            .font(.footnote)
                        Text("ALERT")
                            .font(FirepitFont.labelMedium)
                    }
                    .foregroundStyle(FirepitColors.warn)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                if let senderName, !isOutgoing, isFirstInGroup {
                    Text(verbatim: senderName)
                        .font(FirepitFont.labelMedium)
                        .foregroundStyle(
                            senderNodeNum.map { IdentityColors.color(for: $0, slot: identitySlots[$0]) }
                                ?? FirepitColors.textSecondary
                        )
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if let quoted {
                    QuotedBlock(quoted: quoted, onTap: onQuoteTap)
                }
                Text(highlighted)
                    .font(FirepitFont.bodyLarge)
                    .foregroundStyle(FirepitColors.textPrimary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
                // Its own line: sharing one with the text stretched short messages into a wide, cramped strip. At the
                // largest text sizes the footnote goes above the time rather than crushing both into each other.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 4) {
                        if let footnote {
                            Text(verbatim: footnote).lineLimit(1)
                        }
                        timeAndStatus.lineLimit(1)
                    }
                    VStack(alignment: .trailing, spacing: 2) {
                        if let footnote {
                            Text(verbatim: footnote)
                        }
                        timeAndStatus
                    }
                }
                .font(FirepitFont.labelSmall)
                .foregroundStyle(FirepitColors.textSecondary)
                .frame(maxWidth: .infinity, alignment: .trailing)
            }
            .padding(.horizontal, FirepitSpacing.bubblePaddingHorizontal)
            .padding(.vertical, FirepitSpacing.bubblePaddingVertical)
            .background(isOutgoing ? FirepitColors.bubbleOut : FirepitColors.bubbleIn, in: shape)
            .overlay { border }
        }
    }

    private var timeAndStatus: some View {
        HStack(spacing: 4) {
            if signed {
                Image(icon: .signed)
                    .imageScale(.small)
                    .accessibilityLabel(Text("Signed by the sender's radio"))
            }
            Text(verbatim: time)
            if let status {
                StatusTick(status)
            }
        }
    }

    /// Paints the search runs, leaving the rest of the message alone.
    private var highlighted: AttributedString {
        var attributed = AttributedString(text)
        let utf16 = text.utf16
        for run in highlight {
            // A stale range from a query that changed under us must not crash.
            let from = min(max(run.lowerBound, 0), utf16.count)
            let to = min(max(run.upperBound, from), utf16.count)
            guard from < to,
                let lower = utf16.index(utf16.startIndex, offsetBy: from, limitedBy: utf16.endIndex),
                let upper = utf16.index(utf16.startIndex, offsetBy: to, limitedBy: utf16.endIndex),
                let range = Range(lower..<upper, in: attributed)
            else { continue }
            attributed[range].backgroundColor = FirepitColors.warn.opacity(0.35)
        }
        return attributed
    }

    /// Square-ish tail on the sender's side, and only on the last of a run.
    private var shape: UnevenRoundedRectangle {
        guard isLastInGroup else {
            return UnevenRoundedRectangle(
                cornerRadii: RectangleCornerRadii(
                    topLeading: FirepitRadius.bubble, bottomLeading: FirepitRadius.bubble,
                    bottomTrailing: FirepitRadius.bubble, topTrailing: FirepitRadius.bubble), style: .continuous)
        }
        let round = FirepitRadius.bubbleRound
        let tail = FirepitRadius.bubbleTail
        return UnevenRoundedRectangle(
            cornerRadii: RectangleCornerRadii(
                topLeading: round,
                bottomLeading: isOutgoing ? round : tail,
                bottomTrailing: isOutgoing ? tail : round,
                topTrailing: round), style: .continuous)
    }

    @ViewBuilder private var border: some View {
        if isAlert {
            shape.strokeBorder(FirepitColors.warn, lineWidth: 2)
        } else if !isOutgoing {
            shape.strokeBorder(FirepitColors.outline, lineWidth: 1)
        }
    }
}

/// The quoted message, tinted and bar-marked so it reads as a quote at a glance rather than as part of the reply's own
/// text.
private struct QuotedBlock: View {
    let quoted: QuotedMessage
    let onTap: (() -> Void)?

    @Environment(\.identitySlots) private var identitySlots
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let accent =
            quoted.senderNodeNum.map { IdentityColors.color(for: $0, slot: identitySlots[$0]) }
            ?? FirepitColors.textSecondary
        let content = HStack(spacing: 0) {
            accent.frame(width: 3)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: quoted.senderName)
                    .font(FirepitFont.labelMedium)
                    .foregroundStyle(accent)
                    .lineLimit(1)
                Text(verbatim: quoted.text)
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.textSecondary)
                    .lineLimit(2)
            }
            .padding(.horizontal, FirepitSpacing.s)
            .padding(.vertical, FirepitSpacing.xs)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .fixedSize(horizontal: false, vertical: true)
        // Neutral wash, not the sender's hue: a full-strength identity colour behind the quote fought the bubble.
        .background(FirepitColors.textSecondary.opacity(colorScheme == .dark ? 0.14 : 0.07))
        .clipShape(.rect(cornerRadius: FirepitRadius.quote))

        if let onTap {
            Button(action: onTap) { content }
                .buttonStyle(.plain)
                .accessibilityHint(Text("Shows the original message"))
        } else {
            content
        }
    }
}

/// Centred pill used for date separators and system events.
struct SystemChip: View {
    let text: String

    var body: some View {
        Text(verbatim: text)
            .font(FirepitFont.bodySmall)
            .foregroundStyle(FirepitColors.textSecondary)
            .multilineTextAlignment(.center)
            .padding(.horizontal, FirepitSpacing.m)
            .padding(.vertical, FirepitSpacing.xs)
            .overlay { Capsule().strokeBorder(FirepitColors.outline) }
            .frame(maxWidth: .infinity)
    }
}

/// Caps the bubble at a share of the offered width and pins it to the sender's side, per the UX spec.
private struct BubbleWidthLayout: Layout {
    let isOutgoing: Bool

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let bubble = subviews.first else { return .zero }
        let width = proposal.width ?? bubble.sizeThatFits(.unspecified).width
        let size = bubble.sizeThatFits(
            ProposedViewSize(
                width: width * FirepitSpacing.bubbleMaxWidthFraction,
                height: nil))
        return CGSize(width: width, height: size.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard let bubble = subviews.first else { return }
        let offered = ProposedViewSize(width: bounds.width * FirepitSpacing.bubbleMaxWidthFraction, height: nil)
        let size = bubble.sizeThatFits(offered)
        let x = isOutgoing ? bounds.maxX - size.width : bounds.minX
        bubble.place(at: CGPoint(x: x, y: bounds.minY), anchor: .topLeading, proposal: ProposedViewSize(size))
    }
}

/// Stacks a bubble's rows at the width of its widest row, capped by the space offered: short messages stay short, long
/// ones wrap, and every row — including the time line — spans the bubble.
private struct BubbleStack: Layout {
    var spacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = width(for: proposal, subviews: subviews)
        let heights = subviews.map { $0.sizeThatFits(ProposedViewSize(width: width, height: nil)).height }
        return CGSize(width: width, height: heights.reduce(0, +) + spacing * CGFloat(max(subviews.count - 1, 0)))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for subview in subviews {
            let height = subview.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil)).height
            subview.place(
                at: CGPoint(x: bounds.minX, y: y), anchor: .topLeading,
                proposal: ProposedViewSize(width: bounds.width, height: height))
            y += height + spacing
        }
    }

    private func width(for proposal: ProposedViewSize, subviews: Subviews) -> CGFloat {
        let ideal = subviews.map { $0.sizeThatFits(.unspecified).width }.max() ?? 0
        return min(ideal, proposal.width ?? ideal)
    }
}

#Preview("Bubbles") {
    ScrollView {
        VStack(spacing: 2) {
            SystemChip(text: "Today")
            MessageBubble(
                text: "Anyone at the lake yet?", time: "09:41", isOutgoing: false, senderName: "Maya",
                senderNodeNum: 0x5EED, isLastInGroup: false)
            MessageBubble(
                text: "Bring the big tent", time: "09:41", isOutgoing: false, senderName: "Maya",
                senderNodeNum: 0x5EED, isFirstInGroup: false)
            MessageBubble(
                text: "On my way, 10 minutes", time: "09:42", isOutgoing: true, status: .reachedMesh,
                quoted: QuotedMessage(
                    senderName: "Maya", senderNodeNum: 0x5EED,
                    text: "Anyone at the lake yet?"),
                highlight: [0..<2])
            MessageBubble(
                text: "Storm coming in from the west", time: "09:44", isOutgoing: false,
                senderName: "Sam", senderNodeNum: 7, isAlert: true)
            MessageBubble(text: "Failed one", time: "09:45", isOutgoing: true, status: .failed)
        }
        .padding()
    }
    .background(FirepitColors.surface)
}
