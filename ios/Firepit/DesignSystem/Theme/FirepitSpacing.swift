import CoreGraphics

/// Layout constants, ported from android/core/designsystem/…/theme/FirepitDimens.kt (1 dp = 1 pt).
nonisolated enum FirepitSpacing {
    static let xs: CGFloat = 4
    static let s: CGFloat = 8
    static let m: CGFloat = 12
    static let l: CGFloat = 16
    static let xl: CGFloat = 24
    static let xxl: CGFloat = 32

    /// Screen side margin.
    static let screenMargin: CGFloat = 16

    /// The UX spec asks for 44 pt, which is also Apple's minimum; Android uses Material's 48 dp.
    static let minTouchTarget: CGFloat = 44

    static let listRowHeight: CGFloat = 68
    static let avatarSize: CGFloat = 48

    static let chipCorner: CGFloat = 10
    static let cardCorner: CGFloat = 14

    static let bubblePaddingVertical: CGFloat = 10
    static let bubblePaddingHorizontal: CGFloat = 14

    /// Chat bubbles never exceed this share of the pane width, at any screen size.
    static let bubbleMaxWidthFraction: CGFloat = 0.78

    /// Full-width buttons.
    static let buttonHeight: CGFloat = 52
}

/// Corner radii, ported from FirepitShapes / BubbleShape / SheetShape.
nonisolated enum FirepitRadius {
    static let extraSmall: CGFloat = 8
    static let small: CGFloat = 12
    static let medium: CGFloat = 12
    static let large: CGFloat = 16
    static let extraLarge: CGFloat = 24

    /// Chat bubble corner, per UX spec §9.3.
    static let bubble: CGFloat = 16
    /// The last bubble of a run: round on three corners, a small tail on the sender's side.
    static let bubbleRound: CGFloat = 18
    static let bubbleTail: CGFloat = 5
    /// Bottom sheets and the invite card.
    static let sheet: CGFloat = 24
    static let quote: CGFloat = 6
}
