import FirepitProtocol
import Foundation
import Testing

@testable import Firepit

/// App-level stores ported from the Android app module, each on a throwaway defaults suite.
@MainActor
@Suite("App stores")
struct AppStoreTests {
    private let defaults: UserDefaults

    init() {
        let suite = "firepit.tests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
    }

    @Test func notificationsHideTheWordsUntilAskedAndRemember() {
        let preferences = NotificationPreferences(defaults: defaults)
        #expect(preferences.showText == false)

        preferences.setShowText(true)

        #expect(NotificationPreferences(defaults: defaults).showText)
    }

    @Test func screenCaptureIsRefusedUntilAllowedAndRemembered() {
        let preferences = ScreenPrivacyPreferences(defaults: defaults)
        #expect(preferences.allowCapture == false)

        preferences.setAllowCapture(true)

        #expect(ScreenPrivacyPreferences(defaults: defaults).allowCapture)
    }

    @Test func themeFollowsTheSystemUntilChosen() {
        let preferences = ThemePreferences(defaults: defaults)
        #expect(preferences.choice == .system)

        preferences.set(.dark)

        #expect(ThemePreferences(defaults: defaults).choice == .dark)
    }

    @Test func anUnknownSavedThemeFallsBackToTheSystem() {
        defaults.set("SEPIA", forKey: "theme_choice")

        #expect(ThemePreferences(defaults: defaults).choice == .system)
    }

    @Test func mapsFetchTilesUntilOfflineOnlyIsChosen() {
        let preferences = MapPreferences(defaults: defaults)
        #expect(preferences.offlineOnly == false)

        preferences.setOfflineOnly(true)
        defer { preferences.setOfflineOnly(false) }

        #expect(MapPreferences(defaults: defaults).offlineOnly)
        #expect(OfflineOnlyURLProtocol.canInit(with: URLRequest(url: URL(string: "https://tiles.openfreemap.org")!)))
    }

    @Test func savedRadiosSurviveARestart() {
        let store = SavedRadioStore(defaults: defaults)
        store.assign(identifier: "A", name: "Pocket", role: .personal)
        store.assign(identifier: "B", name: "Camp", role: .base)
        _ = store.rememberNode(identifier: "A", nodeNum: -5, publicKey: "key-a")
        store.showOnMap(identifier: "B", onMap: false)

        let reloaded = SavedRadioStore(defaults: defaults)

        #expect(reloaded.radios == store.radios)
        #expect(reloaded.personal?.identifier == "A")
        #expect(reloaded.radios.first { $0.identifier == "A" }?.nodeNum == -5)
        #expect(reloaded.radios.first { $0.identifier == "B" }?.onMap == false)
    }

    @Test func onlyOneRadioIsPersonal() {
        let store = SavedRadioStore(defaults: defaults)
        store.assign(identifier: "A", name: "Old", role: .personal)
        store.assign(identifier: "B", name: "New", role: .personal)

        #expect(store.radios.filter { $0.role == .personal }.count == 1)
        #expect(store.personal?.identifier == "B")
    }

    @Test func aRadioLearnsWhoItIsOnce() {
        let store = SavedRadioStore(defaults: defaults)
        store.assign(identifier: "A", name: "Pocket", role: .personal)

        #expect(store.rememberNode(identifier: "A", nodeNum: 7, publicKey: "key"))
        #expect(store.radios.first?.nodeNum == 7)
        #expect(store.radios.first?.publicKey == "key")
    }

    @Test func aDifferentNodeAtTheSameAddressIsNotTheSavedRadio() {
        let store = SavedRadioStore(defaults: defaults)
        store.assign(identifier: "A", name: "Pocket", role: .personal)
        _ = store.rememberNode(identifier: "A", nodeNum: 7, publicKey: "key")

        #expect(store.rememberNode(identifier: "A", nodeNum: 8, publicKey: "key") == false)
        #expect(store.radios.first?.nodeNum == 7)
    }

    @Test func theSameNodeUnderAnotherKeyIsNotTheSavedRadio() {
        let store = SavedRadioStore(defaults: defaults)
        store.assign(identifier: "A", name: "Pocket", role: .personal)
        _ = store.rememberNode(identifier: "A", nodeNum: 7, publicKey: "key")

        #expect(store.rememberNode(identifier: "A", nodeNum: 7, publicKey: "other") == false)
        #expect(store.radios.first?.publicKey == "key")
    }

    @Test func aRadioThatStopsShowingItsKeyIsNotTrusted() {
        let store = SavedRadioStore(defaults: defaults)
        store.assign(identifier: "A", name: "Pocket", role: .personal)
        _ = store.rememberNode(identifier: "A", nodeNum: 7, publicKey: "key")

        #expect(store.rememberNode(identifier: "A", nodeNum: 7, publicKey: nil) == false)
    }

    @Test func trustingAReflashedRadioReplacesItsIdentity() {
        let store = SavedRadioStore(defaults: defaults)
        store.assign(identifier: "A", name: "Pocket", role: .personal)
        _ = store.rememberNode(identifier: "A", nodeNum: 7, publicKey: "key")

        store.trust(identifier: "A", nodeNum: 9, publicKey: "new")

        #expect(store.rememberNode(identifier: "A", nodeNum: 9, publicKey: "new"))
        #expect(store.radios.first?.publicKey == "new")
    }

    @Test func reassigningKeepsWhatTheRadioSaidAboutItself() {
        let store = SavedRadioStore(defaults: defaults)
        store.assign(identifier: "A", name: "Pocket", role: .personal)
        _ = store.rememberNode(identifier: "A", nodeNum: 7, publicKey: "key")

        store.assign(identifier: "A", name: "Renamed", role: .base)

        #expect(store.radios.first?.nodeNum == 7)
        #expect(store.radios.first?.publicKey == "key")
        #expect(store.radios.first?.name == "Renamed")
    }

    @Test func forgettingARadioRemovesIt() {
        let store = SavedRadioStore(defaults: defaults)
        store.assign(identifier: "A", name: "Pocket", role: .personal)

        store.forget(identifier: "A")

        #expect(SavedRadioStore(defaults: defaults).radios.isEmpty)
    }

    @Test func corruptSavedRadiosReadAsNone() {
        defaults.set("not json", forKey: "radios")

        #expect(SavedRadioStore(defaults: defaults).radios.isEmpty)
    }
}
