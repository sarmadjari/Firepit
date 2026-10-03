import SwiftUI
import UIKit

/// Ember palette, ported from android/core/designsystem/…/theme/FirepitColors.kt (EmberLightColors, EmberDarkColors and
/// EmberPalette). Terracotta primary over warm neutrals — campfire and canvas, deliberately distinct from the greens,
/// blues and purples every other messenger uses. Status hues stay conventional so brand never competes with meaning.
///
/// Every token resolves per trait collection, so it follows light and dark instantly. Deliberately no wallpaper-derived
/// colour: Ember's hues carry meaning (live green, warn gold, danger crimson, infra slate). Never hard-code a colour
/// outside `DesignSystem/Theme`.
nonisolated enum FirepitColors {
    static let primary = Color(light: 0xC2410C, dark: 0xF0875A)
    /// Text and icons on anything filled with `primary`.
    static let onPrimary = Color(light: 0xFFFFFF, dark: 0x3B1400)
    /// The page.
    static let surface = Color(light: 0xFAF7F3, dark: 0x141210)
    /// Cards and raised rows, against the page `surface`.
    static let surface2 = Color(light: 0xFFFFFF, dark: 0x1E1B18)
    static let bubbleOut = Color(light: 0xFBE3D6, dark: 0x3A2418)
    static let bubbleIn = Color(light: 0xFFFFFF, dark: 0x24211E)
    static let textPrimary = Color(light: 0x1A1614, dark: 0xF1ECE7)
    static let textSecondary = Color(light: 0x6B625C, dark: 0xA39C95)
    /// Hairlines and unselected chip borders.
    static let outline = Color(light: 0xE8E0D9, dark: 0x2E2926)
    /// Live position markers and the connected node dot.
    static let live = Color(light: 0x2BB673, dark: 0x4ED69A)
    /// Stale positions and unknown state. Never the only signal — always paired with an age label.
    static let stale = Color(light: 0xA39E98, dark: 0x6F6963)
    /// Alerts, mesh-busy, node-restarting. Deep gold, distinct from the terracotta primary.
    static let warn = Color(light: 0x9A6B00, dark: 0xF2C94C)
    /// Failed sends, leave and remove. Crimson, distinct from the primary.
    static let danger = Color(light: 0xC62B4A, dark: 0xF27D8E)
    /// Base and Router node markers.
    static let infra = Color(light: 0x4A5B8C, dark: 0x93A6DF)
    static let mapGround = Color(light: 0xEEEAE4, dark: 0x1C1A17)
    static let mapRoad = Color(light: 0xFFFFFF, dark: 0x2C2925)
    static let mapWater = Color(light: 0xD5E5F0, dark: 0x1E2C38)
    static let mapPark = Color(light: 0xD6E3CF, dark: 0x22301F)
    /// Your own marker's ring: blue, the colour every map uses for you. Map only, as Android's SELF_RING.
    static let mapSelf = Color(hex: 0x1B73E8)
    /// Dropped pins, distinct from the round node discs. Map only, as Android's PIN_COLOR.
    static let mapPin = Color(hex: 0xF59E0B)
    /// Names under pins. The basemap stays light in both themes, so the text does too, on a white halo.
    static let mapLabel = Color(hex: 0x1A1614)

    /// Soft elevation under floating map controls on systems without Liquid Glass.
    static let shadow = Color(light: 0x1A1614, dark: 0x000000, opacity: 0.16)
}

/// An sRGB colour with components in 0…1.
nonisolated struct RGB: Hashable, Sendable {
    var red: Double
    var green: Double
    var blue: Double

    init(red: Double, green: Double, blue: Double) {
        self.red = red
        self.green = green
        self.blue = blue
    }

    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255)
    }

    /// HSL → sRGB with hue in degrees and saturation/lightness in 0…1, the same conversion as Compose's `Color.hsl`.
    init(hue: Double, saturation: Double, lightness: Double) {
        let chroma = (1 - abs(2 * lightness - 1)) * saturation
        let sector = hue.truncatingRemainder(dividingBy: 360) / 60
        let x = chroma * (1 - abs(sector.truncatingRemainder(dividingBy: 2) - 1))
        let (r, g, b): (Double, Double, Double) =
            switch sector {
            case ..<1: (chroma, x, 0)
            case ..<2: (x, chroma, 0)
            case ..<3: (0, chroma, x)
            case ..<4: (0, x, chroma)
            case ..<5: (x, 0, chroma)
            default: (chroma, 0, x)
            }
        let m = lightness - chroma / 2
        self.init(red: r + m, green: g + m, blue: b + m)
    }

    /// `0xRRGGBB`, rounded to 8 bits per channel.
    var hex: UInt32 {
        func byte(_ component: Double) -> UInt32 { UInt32((min(max(component, 0), 1) * 255).rounded()) }
        return byte(red) << 16 | byte(green) << 8 | byte(blue)
    }
}

extension Color {
    /// A colour that resolves per trait collection, so it follows light/dark (and every rendering context) instantly.
    nonisolated init(light: RGB, dark: RGB, opacity: Double = 1) {
        self.init(
            uiColor: UIColor { traits in
                let rgb = traits.userInterfaceStyle == .dark ? dark : light
                return UIColor(red: rgb.red, green: rgb.green, blue: rgb.blue, alpha: opacity)
            })
    }

    nonisolated init(light: UInt32, dark: UInt32, opacity: Double = 1) {
        self.init(light: RGB(hex: light), dark: RGB(hex: dark), opacity: opacity)
    }

    /// A fixed colour that ignores the theme — only for artwork that must look the same in both themes.
    nonisolated init(hex: UInt32) {
        let rgb = RGB(hex: hex)
        self.init(.sRGB, red: rgb.red, green: rgb.green, blue: rgb.blue)
    }
}
