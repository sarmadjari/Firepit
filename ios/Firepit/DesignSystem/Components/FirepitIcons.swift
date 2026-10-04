import SwiftUI

/// The app's icon set, mapped from android/core/designsystem/…/component/FirepitIcons.kt.
///
/// Interface chrome (search, close, share…) uses the matching SF Symbols, which share one weight, follow Dynamic Type
/// and bold text, and take the theme colour. Glyphs that people compare between phones — a radio's role and a dropped
/// pin — are Android's own drawings instead (`glyph`, from `Assets.xcassets/Glyphs`, written by
/// scripts/sync-ios-glyphs.py), so an iPhone and an Android phone show the same thing. The delivery ticks keep their
/// own paths (see `StatusTick`) because SF Symbols has no double tick and both platforms must draw the same ticks.
nonisolated enum FirepitIcon: String, CaseIterable, Sendable {
    case chats, map, settings, search, more, add, mute, bell, locate, download, share, qr, back, send, pin, copy
    case clock, info, close, tick, tickDouble, pending, warning, chevron
    case signed
    case rolePersonal, roleBase, roleRouter

    var systemName: String {
        switch self {
        // One bubble, as Android draws it.
        case .chats: "bubble.left"
        case .map: "map"
        case .settings: "gearshape"
        case .search: "magnifyingglass"
        case .more: "ellipsis"
        case .add: "plus"
        case .mute: "bell.slash"
        case .bell: "bell"
        case .locate: "location"
        case .download: "arrow.down.circle"
        case .share: "square.and.arrow.up"
        case .qr: "qrcode"
        case .back: "chevron.backward"
        case .send: "arrow.up"
        case .pin: "mappin"
        case .copy: "doc.on.doc"
        case .clock: "clock"
        case .info: "info.circle"
        case .close: "xmark"
        case .tick, .tickDouble: "checkmark"
        case .pending: "clock"
        case .warning: "exclamationmark.triangle"
        case .chevron: "chevron.right"
        // A radio signature on the message, from Meshtastic 2.8 (UX §5.4).
        case .signed: "checkmark.shield"
        case .rolePersonal: "person"
        // Android draws the base station as a tent: a camp that stays put.
        case .roleBase: "tent"
        case .roleRouter: "antenna.radiowaves.left.and.right"
        }
    }

    /// Android's drawing of this icon, for the ones that must look the same on both platforms.
    var glyph: String? {
        switch self {
        case .rolePersonal: "role-personal"
        case .roleBase: "role-base"
        case .roleRouter: "role-router"
        case .pin: "pin"
        default: nil
        }
    }
}

extension Image {
    /// The icon as SwiftUI draws it. A shared glyph is a template image at its 24 pt drawing size, so where a symbol
    /// would be sized with `.font`, give it `.resizable()` and a frame instead.
    init(icon: FirepitIcon) {
        if let glyph = icon.glyph {
            self.init(glyph)
        } else {
            self.init(systemName: icon.systemName)
        }
    }
}

/// The eight fixed room icons, ported from android/…/component/RoomIcon.kt. Deliberately not emoji: those render
/// differently on every platform and would not match between Android and iOS.
///
/// The order is part of the cross-platform contract: `forRoomId` picks by index, so every member on either platform
/// sees the same icon for a room. Never reorder or insert.
nonisolated enum RoomIcon: Int, CaseIterable, Sendable {
    case tent, trail, car, music, flag, house, star, heart

    static let `default`: RoomIcon = .tent

    /// Stable pick from a room id, so every member sees the same icon: `floorMod(roomId, 8)` as on Android.
    static func forRoomId(_ roomId: Int32) -> RoomIcon {
        let count = Int32(allCases.count)
        return allCases[Int(((roomId % count) + count) % count)]
    }

    var label: String {
        switch self {
        case .tent: "Tent"
        case .trail: "Trail"
        case .car: "Car"
        case .music: "Music"
        case .flag: "Flag"
        case .house: "House"
        case .star: "Star"
        case .heart: "Heart"
        }
    }

    /// Android's own drawing of the icon (`Assets.xcassets/Glyphs`), so a room looks the same to every member.
    var glyph: String {
        switch self {
        case .tent: "room-tent"
        case .trail: "room-trail"
        case .car: "room-car"
        case .music: "room-music"
        case .flag: "room-flag"
        case .house: "room-house"
        case .star: "room-star"
        case .heart: "room-heart"
        }
    }
}
