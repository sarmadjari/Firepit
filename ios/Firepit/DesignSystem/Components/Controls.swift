import SwiftUI

/// The title block of a bar, ported from android/…/component/AppBars.kt (FirepitTopBar's title slot).
///
/// iOS draws the bar itself: top-level tabs use a large navigation title, detail screens an inline one with the system
/// back button. This view is for bars that carry more than a word — a chat header is the same shape as a tab header
/// wearing an avatar, a badge and a second line at once — and goes in the toolbar's principal placement.
struct FirepitBarTitle<Leading: View, Badge: View, Subtitle: View>: View {
    let title: String
    @ViewBuilder var leading: Leading
    @ViewBuilder var badge: Badge
    @ViewBuilder var subtitle: Subtitle

    var body: some View {
        HStack(spacing: FirepitSpacing.m) {
            leading
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: FirepitSpacing.xs) {
                    Text(verbatim: title)
                        .font(FirepitFont.titleMedium)
                        .foregroundStyle(FirepitColors.textPrimary)
                        .lineLimit(1)
                    badge
                }
                subtitle
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.textSecondary)
                    .lineLimit(1)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

extension FirepitBarTitle where Badge == EmptyView {
    init(title: String, @ViewBuilder leading: () -> Leading, @ViewBuilder subtitle: () -> Subtitle) {
        self.init(title: title, leading: leading, badge: { EmptyView() }, subtitle: subtitle)
    }
}

/// Buttons in Ember colours: anything filled with `primary` labels its text **and** icon with `onPrimary`.
struct FirepitButtonStyle: ButtonStyle {
    enum Kind {
        /// Full-width primary action.
        case prominent
        /// Full-width secondary action on outlined `surface2`.
        case outlined
        /// Full-width destructive action: leave, remove, forget.
        case destructive
        /// Capsule that floats over content, such as "Share my location" on the map.
        case pill
    }

    let kind: Kind
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        let shape =
            kind == .pill
            ? AnyShape(Capsule())
            : AnyShape(RoundedRectangle(cornerRadius: FirepitRadius.large, style: .continuous))
        configuration.label
            .font(.headline)
            .foregroundStyle(foreground)
            .padding(.horizontal, FirepitSpacing.xl)
            .frame(maxWidth: kind == .pill ? nil : .infinity, minHeight: FirepitSpacing.buttonHeight)
            .background(background, in: shape)
            .overlay {
                if kind == .outlined { shape.stroke(FirepitColors.outline) }
            }
            .shadow(color: kind == .pill ? FirepitColors.shadow : .clear, radius: 10, y: 4)
            .contentShape(shape)
            .opacity(configuration.isPressed ? 0.8 : isEnabled ? 1 : 0.45)
    }

    private var foreground: Color {
        switch kind {
        case .prominent, .pill: FirepitColors.onPrimary
        case .outlined: FirepitColors.primary
        case .destructive: Color(hex: 0xFFFFFF)
        }
    }

    private var background: Color {
        switch kind {
        case .prominent, .pill: FirepitColors.primary
        case .outlined: FirepitColors.surface2
        case .destructive: FirepitColors.danger
        }
    }
}

extension ButtonStyle where Self == FirepitButtonStyle {
    static var prominent: FirepitButtonStyle { FirepitButtonStyle(kind: .prominent) }
    static var outlined: FirepitButtonStyle { FirepitButtonStyle(kind: .outlined) }
    static var destructive: FirepitButtonStyle { FirepitButtonStyle(kind: .destructive) }
    static var pill: FirepitButtonStyle { FirepitButtonStyle(kind: .pill) }
}

extension View {
    /// Presents a sheet at exactly its content's height with a grabber, scrolling instead of clipping when Dynamic
    /// Type makes the content taller than the screen.
    func fittedSheet() -> some View {
        modifier(FittedSheet())
    }

    /// Background for controls floating over the map: Liquid Glass on iOS 26 and later, an elevated `surface2` before.
    @ViewBuilder
    func floatingControlBackground(in shape: some Shape) -> some View {
        if #available(iOS 26.0, *) {
            glassEffect(.regular.interactive(), in: shape)
        } else {
            background(FirepitColors.surface2, in: shape)
                .shadow(color: FirepitColors.shadow, radius: 6, y: 2)
        }
    }

    /// Opens a conversation at its newest message and keeps following new ones, while a short conversation still
    /// starts at the top. iOS 17 has no per-role anchors, so there everything anchors to the bottom.
    @ViewBuilder
    func chatScrollAnchor() -> some View {
        if #available(iOS 18.0, *) {
            defaultScrollAnchor(.bottom, for: .initialOffset)
                .defaultScrollAnchor(.bottom, for: .sizeChanges)
        } else {
            defaultScrollAnchor(.bottom)
        }
    }
}

private struct FittedSheet: ViewModifier {
    @State private var height: CGFloat = 0

    func body(content: Content) -> some View {
        ScrollView {
            content
                .onGeometryChange(for: CGFloat.self) { proxy in
                    proxy.size.height
                } action: { newHeight in
                    height = newHeight
                }
        }
        .scrollBounceBehavior(.basedOnSize)
        .presentationDetents(height > 0 ? [.height(height)] : [.medium])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(FirepitRadius.sheet)
        .presentationBackground(FirepitColors.surface2)
    }
}

#Preview("Buttons") {
    VStack(spacing: 12) {
        Button("Connect a radio") {}.buttonStyle(.prominent)
        Button("Scan a code") {}.buttonStyle(.outlined)
        Button("Leave room") {}.buttonStyle(.destructive)
        Button("Share my location") {}.buttonStyle(.pill)
        Button("Disabled") {}.buttonStyle(.prominent).disabled(true)
    }
    .padding()
    .background(FirepitColors.surface)
}
