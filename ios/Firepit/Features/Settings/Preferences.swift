import FirepitProtocol
import Foundation
import Observation

/// Whether a message's words appear in its notification. Ported from android/app/…/settings/NotificationPreferences.kt.
///
/// Off by default: a notification is read by whoever is looking at the phone, which is not always the person it was
/// sent to.
@Observable
final class NotificationPreferences {
    private(set) var showText: Bool

    @ObservationIgnored private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        showText = defaults.bool(forKey: Self.key)
    }

    func setShowText(_ show: Bool) {
        defaults.set(show, forKey: Self.key)
        showText = show
    }

    private static let key = "show_message_text"
}

/// Whether Firepit's screens may be captured. Ported from android/app/…/settings/ScreenPrivacyPreferences.kt.
///
/// Off by default. A screen recording of a conversation or of the map outlives every deletion Firepit makes and ends
/// up in photo backups; the app-switcher snapshot of an open room is kept by the system, outside the app. Somebody who
/// wants to record the map can say so.
///
/// Android blocks screenshots outright with FLAG_SECURE. iOS offers no such switch, so here the setting hides the app
/// behind a cover in the app switcher and while the screen is being recorded or mirrored (see `SecureWindow`).
@Observable
final class ScreenPrivacyPreferences {
    private(set) var allowCapture: Bool

    @ObservationIgnored private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        allowCapture = defaults.bool(forKey: Self.key)
    }

    func setAllowCapture(_ allow: Bool) {
        defaults.set(allow, forKey: Self.key)
        allowCapture = allow
    }

    private static let key = "allow_screen_capture"
}

/// Which palette to draw, independently of what the phone is doing.
enum ThemeChoice: String, CaseIterable, Sendable {
    case system = "SYSTEM"
    case light = "LIGHT"
    case dark = "DARK"

    var label: String {
        switch self {
        case .system: String(localized: "Follow the system")
        case .light: String(localized: "Light")
        case .dark: String(localized: "Dark")
        }
    }
}

/// The chosen theme. Ported from android/app/…/settings/ThemePreferences.kt.
///
/// Worth overriding the system for: a phone set to follow daylight will flip to a white screen after dark, which ruins
/// night vision at exactly the moment the app is most likely to be out in the dark.
@Observable
final class ThemePreferences {
    private(set) var choice: ThemeChoice

    @ObservationIgnored private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        choice = defaults.string(forKey: Self.keyTheme).flatMap(ThemeChoice.init(rawValue:)) ?? .system
    }

    func set(_ choice: ThemeChoice) {
        defaults.set(choice.rawValue, forKey: Self.keyTheme)
        self.choice = choice
    }

    private static let keyTheme = "theme_choice"
}

/// The quick replies the composer offers (UX §5.4), on this phone only. Ported from
/// android/app/…/settings/QuickReplyStore.kt.
@Observable
final class QuickReplyStore {
    private(set) var replies: [String]

    @ObservationIgnored private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        replies = defaults.stringArray(forKey: Self.key).map(QuickReplies.normalise) ?? QuickReplies.defaults
    }

    func set(_ next: [String]) {
        let kept = QuickReplies.normalise(next)
        defaults.set(kept, forKey: Self.key)
        replies = kept
    }

    func resetToDefaults() {
        defaults.removeObject(forKey: Self.key)
        replies = QuickReplies.defaults
    }

    private static let key = "quick_replies"
}
