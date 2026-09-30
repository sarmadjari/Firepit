import SwiftUI

/// Type ramp, ported from android/core/designsystem/…/theme/FirepitTypography.kt onto SF Pro.
///
/// Each Android role maps to the nearest Dynamic Type text style, so every size scales with the user's setting up to
/// the accessibility sizes; the layouts reflow instead of capping. Sizes in comments are Android's at 1× / iOS's at the
/// default setting.
nonisolated enum FirepitFont {
    /// headlineLarge · 28 bold — top-level tab titles.
    static let headlineLarge: Font = .title.bold()
    /// titleLarge · 20 semibold.
    static let titleLarge: Font = .title3.weight(.semibold)
    /// titleMedium · 16 semibold.
    static let titleMedium: Font = .callout.weight(.semibold)
    /// bodyLarge · 16 — message text.
    static let bodyLarge: Font = .callout
    /// bodyMedium · 14 (iOS 15, the nearest text style).
    static let bodyMedium: Font = .subheadline
    /// bodySmall · 12.
    static let bodySmall: Font = .caption
    /// labelMedium · 12 medium — sender names, section labels.
    static let labelMedium: Font = .caption.weight(.medium)
    /// labelSmall · 11 medium — times and footnotes.
    static let labelSmall: Font = .caption2.weight(.medium)
    /// Node ids and PINs: monospaced so they do not jitter while changing.
    static let mono: Font = .system(.subheadline, design: .monospaced).weight(.medium)
}
