import SwiftUI

/// A choice among a small set, filled when taken — ported from android/…/component/Chips.kt (FirepitChip). Used for
/// both filters and settings so that picking a room and picking a duration feel like the same gesture. The chip is
/// 32 pt tall but its hit area keeps the 44-pt minimum.
struct FirepitChip: View {
    private let label: Text
    let selected: Bool
    var enabled = true
    let action: () -> Void

    init(_ title: LocalizedStringKey, selected: Bool, enabled: Bool = true, action: @escaping () -> Void) {
        self.label = Text(title)
        self.selected = selected
        self.enabled = enabled
        self.action = action
    }

    init(verbatim title: String, selected: Bool, enabled: Bool = true, action: @escaping () -> Void) {
        self.label = Text(verbatim: title)
        self.selected = selected
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: FirepitSpacing.chipCorner, style: .continuous)
        Button(action: action) {
            label
                .font(FirepitFont.bodyMedium.weight(selected ? .semibold : .regular))
                .foregroundStyle(selected ? FirepitColors.onPrimary : FirepitColors.textPrimary)
                .padding(.horizontal, FirepitSpacing.l)
                .frame(minHeight: 32)
                .background(selected ? FirepitColors.primary : FirepitColors.surface2, in: shape)
                .overlay {
                    if !selected { shape.strokeBorder(FirepitColors.outline) }
                }
                .padding(.vertical, 6)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A row of chips that scrolls sideways instead of clipping when large Dynamic Type sizes make it too wide.
struct ChipRow<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: FirepitSpacing.s) { content }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: FirepitSpacing.s) { content }
            }
        }
    }
}

/// Unread count. Stays visible on a muted room, which counts but does not interrupt.
struct UnreadBadge: View {
    let count: Int

    var body: some View {
        if count > 0 {
            Text(verbatim: count > 99 ? "99+" : "\(count)")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(FirepitColors.onPrimary)
                .padding(.horizontal, 6)
                .frame(minWidth: 20, minHeight: 20)
                .background(FirepitColors.primary, in: .capsule)
                .accessibilityLabel(Text("\(count) unread"))
        }
    }
}

/// Small uppercase heading that separates groups without drawing a line. Inside a `List`, prefer a native section
/// header, which this matches.
struct SectionLabel: View {
    private let text: Text

    init(_ title: LocalizedStringKey) {
        self.text = Text(title)
    }

    init(verbatim title: String) {
        self.text = Text(verbatim: title)
    }

    var body: some View {
        text
            .font(FirepitFont.labelMedium)
            .textCase(.uppercase)
            .kerning(0.8)
            .foregroundStyle(FirepitColors.textSecondary)
            .padding(.horizontal, FirepitSpacing.screenMargin)
            .padding(.top, FirepitSpacing.m)
            .padding(.bottom, FirepitSpacing.xs)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

/// Centred pill for day breaks and membership changes.
struct TimelinePill: View {
    let text: String

    var body: some View {
        Text(verbatim: text)
            .font(FirepitFont.bodySmall)
            .foregroundStyle(FirepitColors.textSecondary)
            .multilineTextAlignment(.center)
            .padding(.horizontal, FirepitSpacing.m)
            .padding(.vertical, 6)
            .background(FirepitColors.surface2, in: .capsule)
    }
}

/// Outlined container used for member lists, invites and info panels.
struct FirepitCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: FirepitSpacing.cardCorner, style: .continuous)
        content
            .background(FirepitColors.surface2, in: shape)
            .clipShape(shape)
            .overlay { shape.strokeBorder(FirepitColors.outline) }
    }
}

/// A warning note such as "your radio restarts": warn-tinted fill, text body, warn icon.
struct Callout: View {
    private let text: Text
    var icon: FirepitIcon = .info

    init(_ text: LocalizedStringKey, icon: FirepitIcon = .info) {
        self.text = Text(text)
        self.icon = icon
    }

    init(verbatim text: String, icon: FirepitIcon = .info) {
        self.text = Text(verbatim: text)
        self.icon = icon
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(icon: icon)
                .foregroundStyle(FirepitColors.warn)
            text
                .font(FirepitFont.bodyMedium)
                .foregroundStyle(FirepitColors.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(FirepitColors.warn.opacity(0.14), in: .rect(cornerRadius: FirepitSpacing.cardCorner))
    }
}

#Preview("Chips") {
    VStack(alignment: .leading, spacing: 16) {
        ChipRow {
            FirepitChip(verbatim: "15 min", selected: true) {}
            FirepitChip(verbatim: "1 hour", selected: false) {}
            FirepitChip(verbatim: "Until I stop", selected: false, enabled: false) {}
        }
        HStack {
            UnreadBadge(count: 3)
            UnreadBadge(count: 120)
        }
        SectionLabel(verbatim: "Rooms")
        TimelinePill(text: "Today")
        FirepitCard {
            Text(verbatim: "Card content").padding()
        }
        Callout(verbatim: "Your radio restarts to apply this.")
    }
    .padding()
    .background(FirepitColors.surface)
}
