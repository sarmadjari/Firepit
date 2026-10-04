import FirepitModel
import SwiftUI
import UIKit

/// The reactions under a message (UX §5.4): each emoji, how many chose it when more than one did, and yours outlined.
/// Tapping someone else's adds yours. Ported from android/app/…/chat/ReactionViews.kt.
struct ReactionRow: View {
    let counts: [Reactions.Count]
    let isOutgoing: Bool
    let onReact: (String) -> Void

    var body: some View {
        HStack(spacing: FirepitSpacing.xs) {
            ForEach(counts, id: \.emoji) { count in
                Button {
                    if !count.mine { onReact(count.emoji) }
                } label: {
                    HStack(spacing: 4) {
                        Text(verbatim: count.emoji).font(.system(size: 14))
                        if count.count > 1 {
                            Text(verbatim: "\(count.count)")
                                .font(FirepitFont.labelSmall)
                                .foregroundStyle(FirepitColors.textSecondary)
                        }
                    }
                    .padding(.horizontal, FirepitSpacing.s)
                    .padding(.vertical, 2)
                    .background(count.mine ? FirepitColors.bubbleOut : FirepitColors.surface2, in: .capsule)
                    .overlay {
                        Capsule().strokeBorder(count.mine ? FirepitColors.primary : FirepitColors.outline)
                    }
                    // A 44-point target round a small pill, without spacing the conversation out.
                    .contentShape(.rect.inset(by: -10))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(label(count)))
            }
        }
        .frame(maxWidth: .infinity, alignment: isOutgoing ? .trailing : .leading)
        .padding(.top, 2)
    }

    private func label(_ count: Reactions.Count) -> String {
        var words = count.count == 1
            ? String(localized: "\(count.emoji), 1 person")
            : String(localized: "\(count.emoji), \(count.count) people")
        if count.mine { words += String(localized: ", including you") }
        return words
    }
}

/// What a long press on a message offers (UX §5.4): the six reactions in one row, one tap each, then Reply, Copy and
/// Message info where the conversation has them. The contents of the message's context menu.
struct ReactionMenu: View {
    let text: String
    let onReact: (String) -> Void
    var onReply: (() -> Void)?
    var onInfo: (() -> Void)?

    var body: some View {
        ReactionPalette(onReact: onReact)
        if let onReply {
            Button(action: onReply) {
                Label("Reply", systemImage: "arrowshape.turn.up.left")
            }
        }
        CopyMessageButton(text: text)
        if let onInfo {
            Button(action: onInfo) {
                Label("Message info", systemImage: FirepitIcon.info.systemName)
            }
        }
    }
}

/// The six reactions as one row at the top of a message's context menu (UX §5.4), one tap each.
struct ReactionPalette: View {
    let onReact: (String) -> Void

    var body: some View {
        ControlGroup {
            ForEach(Reactions.choices, id: \.self) { emoji in
                Button {
                    onReact(emoji)
                } label: {
                    Text(verbatim: emoji)
                }
            }
        }
        .controlGroupStyle(.palette)
    }
}

/// Copy for a message's context menu.
struct CopyMessageButton: View {
    let text: String

    var body: some View {
        Button {
            UIPasteboard.general.string = text
        } label: {
            Label("Copy", systemImage: "doc.on.doc")
        }
    }
}
