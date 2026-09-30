import FirepitData
import FirepitModel
import FirepitProtocol
import FirepitProtos
import FirepitTransport
import Foundation
import Observation

struct SettingsUiState: Equatable {
    var theme: ThemeChoice = .system
    var person: Person?
    var connected = false
    var retention: MessageRetention = .default
    var roomLifetime: RoomLifetime = .default
    var showMessageText = false
    var allowScreenCapture = false
    var messageAlerts: MessageAlerts = .phoneOnly
    var rangeMode: RangeMode = .default
    var radioPrivacy: RadioPrivacy = .default
    var canRestoreRadio = false
    var owner: Owner?
    var myNodeNum: Int32?
    var rangeError: String?
    var alertsError: String?
    var renameError: String?
}

/// Settings state and actions. Ported from android/app/…/settings/SettingsViewModel.kt.
///
/// The state is read live: the stores that Settings changes are observable, and the radio's values are followed while
/// the screen is up, so nothing here is a copy that can fall behind.
@MainActor
@Observable
final class SettingsViewModel {
    @ObservationIgnored private let themePreferences: ThemePreferences
    @ObservationIgnored private let people: PersonStore
    @ObservationIgnored private let owners: OwnerRepository
    @ObservationIgnored private let retention: RetentionStore
    @ObservationIgnored private let notifications: NotificationPreferences
    @ObservationIgnored private let screenPrivacy: ScreenPrivacyPreferences
    @ObservationIgnored private let range: RangeRepository
    @ObservationIgnored private let admin: NodeAdminClient
    @ObservationIgnored private let mesh: MeshRepository

    /// The radio's side, followed by `observe()`.
    private var radio = RadioSide()
    private var rangeError: String?
    private var alertsError: String?
    private var renameError: String?

    var state: SettingsUiState {
        SettingsUiState(
            theme: themePreferences.choice,
            person: people.person,
            connected: radio.connected,
            retention: retention.choice,
            roomLifetime: retention.roomLifetime,
            showMessageText: notifications.showText,
            allowScreenCapture: screenPrivacy.allowCapture,
            messageAlerts: Self.messageAlerts(snapshot: radio.snapshot),
            rangeMode: radio.rangeMode,
            radioPrivacy: radio.privacy,
            canRestoreRadio: radio.canRestore,
            owner: radio.owner,
            myNodeNum: radio.myNodeNum,
            rangeError: rangeError,
            alertsError: alertsError,
            renameError: renameError
        )
    }

    var retentionChoice: MessageRetention { retention.choice }

    init(
        themePreferences: ThemePreferences,
        people: PersonStore,
        owners: OwnerRepository,
        retention: RetentionStore,
        notifications: NotificationPreferences,
        screenPrivacy: ScreenPrivacyPreferences,
        range: RangeRepository,
        admin: NodeAdminClient,
        mesh: MeshRepository
    ) {
        self.themePreferences = themePreferences
        self.people = people
        self.owners = owners
        self.retention = retention
        self.notifications = notifications
        self.screenPrivacy = screenPrivacy
        self.range = range
        self.admin = admin
        self.mesh = mesh
        radio = RadioSide(
            connected: mesh.isConnected.value,
            snapshot: mesh.snapshot.value,
            rangeMode: range.mode.value,
            privacy: range.privacy.value,
            canRestore: range.canRestore.value,
            myNodeNum: mesh.myNodeNum.value
        )
    }

    convenience init(app: AppContainer) {
        self.init(
            themePreferences: app.themePreferences,
            people: app.people,
            owners: app.owners,
            retention: app.retention,
            notifications: app.notificationPreferences,
            screenPrivacy: app.screenPrivacy,
            range: app.range,
            admin: app.admin,
            mesh: app.mesh
        )
    }

    /// Follows the radio while Settings is on screen. Call from `.task`; returns when cancelled.
    func observe() async {
        let mesh = mesh
        let range = range
        let owners = owners
        await awaitObservers([
            Task { for await value in mesh.isConnected.subscribe() { self.radio.connected = value } },
            Task { for await value in mesh.snapshot.subscribe() { self.radio.snapshot = value } },
            Task { for await value in mesh.myNodeNum.subscribe() { self.radio.myNodeNum = value } },
            Task { for await value in range.mode.subscribe() { self.radio.rangeMode = value } },
            Task { for await value in range.privacy.subscribe() { self.radio.privacy = value } },
            Task { for await value in range.canRestore.subscribe() { self.radio.canRestore = value } },
            Task { for await value in owners.owner { self.radio.owner = value } },
        ])
    }

    func chooseRange(_ choice: RangeMode) {
        Task {
            do {
                try await range.choose(choice: choice)
                rangeError = nil
            } catch {
                rangeError = failureMessage(error, fallback: String(localized: "Could not change the range setting"))
            }
        }
    }

    /// Takes the radio's primary channel over, hiding its name and battery.
    func makeRadioPrivate() {
        Task {
            do {
                try await range.makePrivate()
                rangeError = nil
            } catch {
                rangeError = failureMessage(error, fallback: String(localized: "Could not change the radio"))
            }
        }
    }

    /// Leaves the radio on whatever primary channel it already had.
    func keepRadioPublic() {
        Task {
            do {
                try await range.keepPublic()
                rangeError = nil
            } catch {
                rangeError = failureMessage(
                    error, fallback: String(localized: "Could not put the radio's own channel back"))
            }
        }
    }

    func setShowMessageText(_ show: Bool) {
        notifications.setShowText(show)
    }

    /// Whether Firepit's screens may be captured; off unless somebody asks.
    func setAllowScreenCapture(_ allow: Bool) {
        screenPrivacy.setAllowCapture(allow)
    }

    /// Deletes everything this phone kept about what was said and where anyone was.
    func eraseHistory() {
        retention.eraseHistory()
    }

    /// Taken from the radio, so it stays right if another app changed it.
    func chooseMessageAlerts(_ choice: MessageAlerts) {
        Task {
            let current = mesh.snapshot.value?.externalNotification
            do {
                try await admin.setExternalNotificationConfig(
                    config: MessageAlerts.applyTo(config: current, choice: choice))
                alertsError = nil
            } catch {
                alertsError = failureMessage(error, fallback: String(localized: "Could not change the radio's alert"))
            }
        }
    }

    func chooseRetention(_ choice: MessageRetention) {
        retention.choose(choice)
    }

    func chooseRoomLifetime(_ choice: RoomLifetime) {
        retention.chooseRoomLifetime(choice)
    }

    /// You. Needs no radio, and changing it changes no radio.
    func chooseIdentitySlot(_ slot: Int?) {
        people.chooseColour(slot)
    }

    func chooseTheme(_ choice: ThemeChoice) {
        themePreferences.set(choice)
    }

    func savePerson(name: String, tag: String) {
        do {
            try people.save(name: name, tag: tag)
            renameError = nil
        } catch {
            renameError = failureMessage(error, fallback: String(localized: "Could not save your name"))
        }
    }

    /// Copies your name onto the connected radio.
    ///
    /// The only name the protocol has is the node's, so this is the one way to be seen as yourself by people who are
    /// not running Firepit.
    func useAsNodeName() {
        guard let person = people.person else { return }
        renameNode(longName: person.name, shortName: person.tag)
    }

    func renameNode(longName: String, shortName: String) {
        Task {
            do {
                try await owners.rename(longName: longName, shortName: shortName)
            } catch {
                renameError = failureMessage(error, fallback: String(localized: "The radio did not take the name."))
            }
        }
    }

    func clearRenameError() {
        renameError = nil
    }

    static func messageAlerts(snapshot: RadioSnapshot?) -> MessageAlerts {
        MessageAlerts.of(config: snapshot?.externalNotification)
    }

    static func deviceSummary(_ link: LinkState) -> String {
        settingsLinkStateWords(link)
    }

    static func nodeSummary(count: Int) -> String {
        count == 0 ? String(localized: "Nobody heard yet") : String(localized: "\(count) heard on the mesh")
    }
}

/// What Settings shows about the radio, as last reported.
private struct RadioSide {
    var connected = false
    var snapshot: RadioSnapshot?
    var rangeMode: RangeMode = .default
    var privacy: RadioPrivacy = .default
    var canRestore = false
    var owner: Owner?
    var myNodeNum: Int32?
}

func settingsLinkStateWords(_ state: LinkState) -> String {
    switch state {
    case .ready:
        "Connected"
    case .downloading:
        "Reading settings…"
    case .connecting:
        "Connecting…"
    case .reconnecting:
        "Reconnecting…"
    case .unsupported(let version):
        "Not connected · firmware \(version?.raw ?? "unknown") is older than \(RadioCapabilities.minimumFirmware.raw)"
    case .disconnected:
        "Not connected"
    }
}
