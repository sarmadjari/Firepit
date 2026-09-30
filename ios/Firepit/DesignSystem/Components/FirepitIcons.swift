import SwiftUI

/// The app's icon set, mapped from android/core/designsystem/…/component/FirepitIcons.kt onto SF Symbols.
///
/// Android draws its own 24 dp stroke glyphs; iOS uses the matching SF Symbols, which share one weight, follow Dynamic
/// Type and bold text, and take the theme colour. The delivery ticks keep their own paths (see `StatusTick`) because
/// SF Symbols has no double tick and both platforms must draw the same ticks.
nonisolated enum FirepitIcon: String, CaseIterable, Sendable {
    case chats, map, settings, search, more, add, mute, bell, locate, download, share, qr, back, send, pin, copy
    case clock, info, close, tick, tickDouble, pending, warning, chevron
    case rolePersonal, roleBase, roleRouter

    var systemName: String {
        switch self {
        case .chats: "bubble.left.and.bubble.right"
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
        case .rolePersonal: "person"
        // Android draws the base station as a tent: a camp that stays put.
        case .roleBase: "tent"
        case .roleRouter: "antenna.radiowaves.left.and.right"
        }
    }
}

extension Image {
    init(icon: FirepitIcon) {
        self.init(systemName: icon.systemName)
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

    var systemName: String {
        switch self {
        case .tent: "tent"
        // Android's trail glyph is a map marker with a hollow centre.
        case .trail: "mappin.and.ellipse"
        case .car: "car"
        case .music: "music.note"
        case .flag: "flag"
        case .house: "house"
        case .star: "star"
        case .heart: "heart"
        }
    }
}
