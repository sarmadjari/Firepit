import SwiftUI

/// Per-person colours, ported from android/core/designsystem/…/theme/IdentityColors.kt.
///
/// Stable per-person colour, derived from the node number so every device in a room paints the same person the same
/// way without exchanging anything. A slot chosen on this phone overrides that for one person on one phone; nothing
/// carries the choice over the air, so it changes only what its owner sees.
///
/// Colour never carries meaning on its own: the 2-character tag sits inside the avatar and the full name is always in
/// the row or marker label, so a hue collision between two members is cosmetic.
nonisolated enum IdentityColors {
    static let slots = 12
    static let saturation = 0.50
    static let lightnessLight = 0.42
    static let lightnessDark = 0.64
    static let labelLightnessDark = 0.16

    /// The slots offered as a choice. Ten of the twelve: the two mustard hues either side of 60° read as muddy at this
    /// lightness and are close enough to each other to be confusable, which is the one thing a colour picked to tell
    /// people apart must not be.
    static let choices: [Int] = [0, 1, 3, 4, 5, 6, 7, 8, 9, 10]

    /// `floorMod(nodeNum, 12)` on the signed node number, exactly as Android computes it, so both platforms agree.
    static func slot(for nodeNum: Int32) -> Int {
        let slots = Int32(Self.slots)
        return Int(((nodeNum % slots) + slots) % slots)
    }

    static func hue(ofSlot slot: Int) -> Double { Double(slot) * (360.0 / Double(slots)) }

    /// The colour of one slot, for drawing the picker.
    static func color(forSlot slot: Int) -> Color {
        let hue = hue(ofSlot: slot)
        return Color(
            light: RGB(hue: hue, saturation: saturation, lightness: lightnessLight),
            dark: RGB(hue: hue, saturation: saturation, lightness: lightnessDark))
    }

    /// A person's colour; `slot` is this phone's personal pick for them, if any.
    static func color(for nodeNum: Int32, slot: Int? = nil) -> Color {
        color(forSlot: slot ?? self.slot(for: nodeNum))
    }

    /// Label colour for the tag inside an identity avatar or map marker. The fill inverts between themes — dark hue in
    /// light mode, light hue in dark mode — so the label inverts with it, as a deep tint of the same hue rather than
    /// flat black.
    static func onColor(for nodeNum: Int32, slot: Int? = nil) -> Color {
        let hue = hue(ofSlot: slot ?? self.slot(for: nodeNum))
        return Color(
            light: RGB(hex: 0xFFFFFF),
            dark: RGB(hue: hue, saturation: saturation, lightness: labelLightnessDark))
    }
}

/// What to draw inside a node's avatar, when the app knows better than the radio.
///
/// The radio's short name is a fallback, not the truth: you are a person rather than the hardware you happen to be
/// carrying, and a base station is a thing whose initials say nothing worth reading.
nonisolated struct IdentityMark: Hashable, Sendable {
    var tag: String?
    /// Drawn in place of a tag.
    var icon: FirepitIcon?

    init(tag: String? = nil, icon: FirepitIcon? = nil) {
        self.tag = tag
        self.icon = icon
    }
}

extension EnvironmentValues {
    /// Colour slot chosen per node on this phone, provided by the app so avatars can honour a personal pick.
    @Entry var identitySlots: [Int32: Int] = [:]
    /// Per-node avatar overrides, provided by the app.
    @Entry var identityMarks: [Int32: IdentityMark] = [:]
}
