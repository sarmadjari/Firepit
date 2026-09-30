import FirepitData
import FirepitModel
import FirepitProtocol
import FirepitProtos
import FirepitTransport
import Foundation
import Observation
import os

private nonisolated let sessionLog = Logger(subsystem: "com.getfirepit.app", category: "FirepitSession")

/// The one way into a radio session. Ported from android/app/…/radio/RadioSessionController.kt.
///
/// Android ties a foreground service's lifetime to the session here. iOS keeps the link alive in the background
/// through the `bluetooth-central` mode and state restoration instead, so there is no service to start — but
/// connecting still goes only through this class, which registers the radio and watches who answers.
@Observable
final class RadioSessionController {
    /// The saved radio whose address answered with a different identity, or nil. Anything can answer at a Bluetooth
    /// address; a radio that suddenly has another node number or key is either reset, or not this one.
    private(set) var identityDoubt: String?

    @ObservationIgnored private let link: RadioLink
    @ObservationIgnored private let sessionStore: SessionStore
    @ObservationIgnored private let savedRadios: SavedRadioStore
    @ObservationIgnored private let primaryBackup: PrimaryBackup
    @ObservationIgnored private var watcher: Task<Void, Never>?

    init(link: RadioLink, sessionStore: SessionStore, savedRadios: SavedRadioStore, primaryBackup: PrimaryBackup) {
        self.link = link
        self.sessionStore = sessionStore
        self.savedRadios = savedRadios
        self.primaryBackup = primaryBackup
    }

    /// A device is only tied to its mesh identity once it says who it is, which is what lets the map know which
    /// markers are your own hardware.
    func start() {
        guard watcher == nil else { return }
        let states = link.state.subscribe()
        watcher = Task { [weak self] in
            for await state in states {
                self?.noteIdentity(of: state)
            }
        }
    }

    private func noteIdentity(of state: LinkState) {
        guard case .ready(let snapshot) = state, let nodeNum = snapshot.myNodeNum else {
            identityDoubt = nil
            return
        }
        guard let id = sessionStore.lastRadioId else { return }
        let consistent = savedRadios.rememberNode(
            identifier: id, nodeNum: nodeNum,
            publicKey: Self.identityKeyOf(snapshot, nodeNum: nodeNum))
        identityDoubt = consistent ? nil : id
        if !consistent {
            sessionLog.warning("a saved radio answered as a different node or key than the one saved")
        }
    }

    /// The person says the radio answering is theirs, reset or reflashed.
    func trustConnectedRadio() {
        guard case .ready(let snapshot) = link.state.value,
            let nodeNum = snapshot.myNodeNum,
            let id = sessionStore.lastRadioId
        else { return }
        savedRadios.trust(identifier: id, nodeNum: nodeNum, publicKey: Self.identityKeyOf(snapshot, nodeNum: nodeNum))
        identityDoubt = nil
    }

    /// Stops administering `radio` for good: it is no longer reconnected on launch, and the copy of its original
    /// primary channel goes with it.
    func forget(_ radio: SavedRadio) {
        if sessionStore.lastRadioId == radio.identifier {
            sessionStore.lastRadioId = nil
        }
        if let nodeNum = radio.nodeNum {
            try? primaryBackup.forget(nodeNum: nodeNum)
        }
        savedRadios.forget(identifier: radio.identifier)
    }

    func connect(_ radio: DiscoveredRadio) {
        sessionStore.lastRadioId = radio.identifier
        // Registered here rather than at the button, so a radio reconnected on launch is administered too. A first
        // radio is Personal, because that is what a first radio is; later ones default to Base until told otherwise.
        if !savedRadios.radios.contains(where: { $0.identifier == radio.identifier }) {
            savedRadios.assign(
                identifier: radio.identifier,
                name: radio.name ?? radio.identifier,
                role: savedRadios.personal == nil ? .personal : .base
            )
        }
        let link = link
        Task {
            await link.connect(radio)
        }
    }

    func disconnect() async {
        // Forgotten deliberately, so an explicit disconnect is not undone by the next launch reconnecting on its own.
        sessionStore.lastRadioId = nil
        await link.disconnect()
    }

    /// Reconnects to the radio that carries you.
    ///
    /// Prefers the Personal radio over whichever was used last: administering a Base station shouldn't stop your own
    /// messages arriving next time you open the app.
    ///
    /// Android scans for half a minute and gives up if the radio is not seen. On iOS a connection request to a known
    /// radio never times out and costs nothing while the radio is away, so the link simply waits for it: a radio that
    /// is off or out of range connects the moment it is back.
    func reconnectLastRadio() {
        guard let wanted = savedRadios.personal?.identifier ?? sessionStore.lastRadioId else { return }
        let saved = savedRadios.radios.first { $0.identifier == wanted }
        sessionLog.info("reconnecting to the last radio")
        connect(DiscoveredRadio(identifier: wanted, name: saved?.name, rssi: 0))
    }

    /// The radio's own key: from its security config when it sent one, otherwise from its node entry — exactly
    /// Kotlin's `security?.public_key ?: nodes[nodeNum]?.user?.public_key`, so an empty key in a present security
    /// config does not fall through to the node entry.
    private static func identityKeyOf(_ snapshot: RadioSnapshot, nodeNum: Int32) -> String? {
        let key =
            snapshot.security.map(\.publicKey)
            ?? snapshot.nodes[nodeNum].flatMap { $0.hasUser ? $0.user.publicKey : nil }
        guard let key, !key.isEmpty else { return nil }
        return key.base64EncodedString()
    }
}
