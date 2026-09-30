import FirepitData
import FirepitModel
import FirepitProtocol
import FirepitProtos
import FirepitTransport
import Foundation
import Observation

struct ChannelRow: Equatable, Identifiable {
    let index: Int32
    let role: String
    let name: String
    let precision: Int
    let key: ChannelKey
    /// What the key is, in words, naming the app-wide key for what it is.
    let keyLabel: String

    var id: Int32 { index }

    init(index: Int32, role: String, name: String, precision: Int, key: ChannelKey, keyLabel: String? = nil) {
        self.index = index
        self.role = role
        self.name = name
        self.precision = precision
        self.key = key
        self.keyLabel = keyLabel ?? key.label
    }
}

struct RadioDetails: Equatable {
    let nodeId: String
    let nodeNum: Int32
    let firmware: String
    let hardware: String
    let region: String
    let rebootCount: Int
    let capabilities: RadioCapabilities
    let channels: [ChannelRow]
    let knownNodes: Int
}

struct RadioUiState: Equatable {
    var scanning = false
    var found: [DiscoveredRadio] = []
    var link: LinkState = .disconnected
    var details: RadioDetails?
    var error: String?
    /// Something that happened but did not fail, and is not worth a dialog.
    var notice: String?
    var nodes: [MeshNode] = []
    var myNodeNum: Int32?
    var tracing: Int32?
    var traceResult: String?
    var saved: [SavedRadio] = []
    var owner: Owner?
    /// Which saved radio this session is talking to, if any.
    var connectedTo: String?
    /// The radio this session is bound to, connected or not yet.
    var activeRadioId: String?
    /// What the phone's Bluetooth stack holds, which is wider than our own link.
    var bluetooth = BluetoothState()
    /// Whether iOS lets Firepit use Bluetooth at all.
    var bluetoothAvailability: BluetoothAvailability = .unknown
    /// Only known for the radio on the other end of the link.
    var relayReach: RelayReach?
    var beaconRate: BeaconRate?
    var beaconWhenMoved = false
    /// Settings on the connected radio that let people read it or run it.
    var risks: [RadioRisk] = []
    /// A Bluetooth PIN just set, shown until dismissed: the phone will ask for it.
    var newPin: UInt32?
    /// The saved radio whose address answered as a different node or key.
    var identityDoubt: String?
}

/// The Meshtastic devices this phone can talk to, and which one it is using.
@MainActor
@Observable
final class RadioViewModel {
    private let scanner: RadioScanner
    private let link: any RadioLinking
    private let central: BluetoothCentral?
    private let presence: BluetoothPresence
    private let session: RadioSessionController
    private let savedRadios: SavedRadioStore
    private let sessionStore: SessionStore
    private let owners: OwnerRepository
    private let admin: NodeAdminClient
    private let mesh: MeshRepository
    private let alerts: AlertClient
    private let traceroute: TracerouteClient
    private let rooms: RoomRepository
    private let notifier: MessageNotifier?

    private(set) var uiState = RadioUiState()

    @ObservationIgnored private var scanning = false
    @ObservationIgnored private var found: [DiscoveredRadio] = []
    @ObservationIgnored private var error: String?
    @ObservationIgnored private var notice: String?
    @ObservationIgnored private var tracing: Int32?
    @ObservationIgnored private var traceResult: String?
    @ObservationIgnored private var owner: Owner?
    @ObservationIgnored private var nodes: [MeshNode] = []
    @ObservationIgnored private var myNodeNum: Int32?
    @ObservationIgnored private var bluetooth = BluetoothState()
    @ObservationIgnored private var bluetoothAvailability: BluetoothAvailability = .unknown
    @ObservationIgnored private var newPin: UInt32?
    @ObservationIgnored private var scanJob: Task<Void, Never>?
    @ObservationIgnored private var noticeJob: Task<Void, Never>?

    init(
        scanner: RadioScanner,
        link: any RadioLinking,
        central: BluetoothCentral? = nil,
        presence: BluetoothPresence,
        session: RadioSessionController,
        savedRadios: SavedRadioStore,
        sessionStore: SessionStore,
        owners: OwnerRepository,
        admin: NodeAdminClient,
        mesh: MeshRepository,
        alerts: AlertClient,
        traceroute: TracerouteClient,
        rooms: RoomRepository,
        notifier: MessageNotifier? = nil
    ) {
        self.scanner = scanner
        self.link = link
        self.central = central
        self.presence = presence
        self.session = session
        self.savedRadios = savedRadios
        self.sessionStore = sessionStore
        self.owners = owners
        self.admin = admin
        self.mesh = mesh
        self.alerts = alerts
        self.traceroute = traceroute
        self.rooms = rooms
        self.notifier = notifier
        rebuildState(linkState: link.state.value)
    }

    convenience init(app: AppContainer) {
        self.init(
            scanner: app.scanner,
            // The radio the repositories talk to: the Bluetooth link, or the pretend one in a demo.
            link: app.radio,
            central: app.central,
            presence: app.presence,
            session: app.session,
            savedRadios: app.savedRadios,
            sessionStore: app.sessionStore,
            owners: app.owners,
            admin: app.admin,
            mesh: app.mesh,
            alerts: app.alerts,
            traceroute: app.traceroute,
            rooms: app.rooms,
            notifier: app.notifier
        )
    }

    func observe() async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask { await self.observeLink() }
            group.addTask { await self.observeNodes() }
            group.addTask { await self.observeMyNode() }
            group.addTask { await self.observeOwner() }
            group.addTask { await self.observeBluetooth() }
            if let central {
                group.addTask { await self.observeAvailability(central) }
            }
            await group.waitForAll()
        }
    }

    func checkPath(_ node: MeshNode) {
        guard tracing == nil else { return }
        Task {
            tracing = node.nodeNum
            rebuildState()
            let name = node.displayName
            let result = await Result { await traceroute.trace(nodeNum: node.nodeNum) }
            tracing = nil
            switch result {
            case .success(let trace):
                traceResult = Self.describeTrace(trace, name: name)
            case .failure(let cause):
                traceResult =
                    cause.localizedDescription.isEmpty ? "Could not trace the route" : cause.localizedDescription
            }
            rebuildState()
        }
    }

    func clearTrace() {
        traceResult = nil
        rebuildState()
    }

    func startScan() {
        guard scanJob?.isCancelled != false else { return }
        error = nil
        scanning = true
        rebuildState()
        scanJob = Task {
            defer {
                scanning = false
                rebuildState()
            }
            await withTimeout(seconds: Self.scanWindowSeconds) { [scanner] in
                for await radios in scanner.scanDistinct() {
                    self.found = radios
                    self.rebuildState()
                }
            }
        }
    }

    func stopScan() {
        scanJob?.cancel()
        scanJob = nil
        scanning = false
        rebuildState()
    }

    func connect(_ radio: DiscoveredRadio) {
        stopScan()
        error = nil
        Task { await notifier?.requestAuthorization() }
        session.connect(radio)
        rebuildState()
    }

    /// Connects to a radio already known, without waiting for a scan to find it.
    func connectSaved(_ saved: SavedRadio) {
        error = nil
        rebuildState()
        Task {
            await notifier?.requestAuthorization()
            await session.disconnect()
            let found = await withTimeoutValue(seconds: Self.savedScanWindowSeconds) { [scanner] in
                for await radios in scanner.scanDistinct() {
                    if let match = radios.first(where: { $0.identifier == saved.identifier }) {
                        return match
                    }
                }
                return nil
            }
            if let found {
                session.connect(found)
            } else {
                error = "\(saved.name) did not answer. It may be off or out of range."
                rebuildState()
            }
        }
    }

    func setRole(_ saved: SavedRadio, role: NodeRole) {
        savedRadios.assign(identifier: saved.identifier, name: saved.name, role: role)
        rebuildState()
    }

    /**
     * Rings one radio so it can be told from the others on the desk.
     *
     * Nothing comes back to say it sounded, so the notice says what silence means rather than claiming success.
     */
    func buzz(_ saved: SavedRadio) {
        error = nil
        guard let nodeNum = saved.nodeNum else {
            say("Connect \(saved.name) once so Firepit learns which node it is.")
            return
        }
        guard case .ready = link.state.value else {
            say("Connect a radio first — a buzz travels over the mesh.")
            return
        }
        Task {
            say("Buzzing \(saved.name)…", transient: false)
            let result = await alerts.buzz(nodeNum: nodeNum)
            say(Self.describeBuzz(result, name: saved.name))
        }
    }

    /** Said once and then forgotten, so a stale line never describes the current state. */
    func say(_ message: String, transient: Bool = true) {
        notice = message
        noticeJob?.cancel()
        rebuildState()
        guard transient else { return }
        noticeJob = Task {
            try? await Task.sleep(for: .seconds(Self.noticeLifetimeSeconds))
            if !Task.isCancelled {
                notice = nil
                rebuildState()
            }
        }
    }

    /**
     * Stops administering `saved`. When it is the radio connected now and `takeRoomsOff` is set, Firepit's rooms come
     * off it first and its own primary channel goes back, so whoever has it next holds none of the rooms' keys.
     */
    func forget(_ saved: SavedRadio, takeRoomsOff: Bool) {
        error = nil
        rebuildState()
        Task {
            let connectedHere = uiState.connectedTo == saved.identifier
            if connectedHere && takeRoomsOff {
                do {
                    try await rooms.removeFirepitFromRadio()
                } catch {
                    self.error =
                        error.localizedDescription.isEmpty
                        ? "Could not take the rooms off this radio" : error.localizedDescription
                    rebuildState()
                    return
                }
            }
            if connectedHere { await session.disconnect() }
            session.forget(saved)
            rebuildState()
        }
    }

    /**
     * Moves every room `saved` is in to new keys without it, for a radio that is lost or in somebody else's hands.
     */
    func removeFromRooms(_ saved: SavedRadio) {
        guard let nodeNum = saved.nodeNum else { return }
        error = nil
        rebuildState()
        Task {
            do {
                let count = try await rooms.removeFromAllRooms(nodeNum: nodeNum)
                if count == 0 {
                    say("\(saved.name) isn't in any room this radio carries.", transient: false)
                } else {
                    let plural = count == 1 ? "" : "s"
                    say(
                        "\(saved.name) was removed from \(count) room\(plural). It gets none of their new keys.",
                        transient: false
                    )
                }
            } catch {
                self.error =
                    error.localizedDescription.isEmpty
                    ? "Could not remove it from your rooms" : error.localizedDescription
                rebuildState()
            }
        }
    }

    /// Fixes one of `RadioUiState.risks`, writing back the radio's own section with only that changed.
    func fixRisk(_ risk: RadioRisk) {
        guard case .ready(let snapshot) = link.state.value else { return }
        error = nil
        rebuildState()
        Task {
            do {
                switch risk {
                case .bluetoothOpen, .bluetoothDefaultPin:
                    guard let current = snapshot.bluetooth else {
                        throw RadioChangeError.message("The radio did not report its Bluetooth settings")
                    }
                    let pin = RadioSecurityCheck.newPin()
                    newPin = pin
                    rebuildState()
                    try await admin.setBluetoothConfig(
                        bluetooth: RadioSecurityCheck.withPin(current: current, pin: pin))
                case .remoteAdminKey, .legacyAdminChannel, .debugLog:
                    guard let current = snapshot.security else {
                        throw RadioChangeError.message("The radio did not report its security settings")
                    }
                    guard current.privateKey.count == TrustRules.radioKeySize else {
                        throw RadioChangeError.message(
                            "The radio did not report its own key, so its security settings can't be rewritten safely"
                        )
                    }
                    try await admin.setSecurityConfig(
                        security: RadioSecurityCheck.withoutRemoteAccess(current: current))
                case .mqttUplink, .mqttMapReport:
                    guard let current = snapshot.mqtt else {
                        throw RadioChangeError.message("The radio did not report its MQTT settings")
                    }
                    try await admin.setMqttConfig(mqtt: RadioSecurityCheck.withoutMqtt(current: current))
                case .managed:
                    return
                }
                say("Sent to the radio. It restarts to apply the change.")
            } catch {
                newPin = nil
                self.error =
                    error.localizedDescription.isEmpty ? "Could not change the radio" : error.localizedDescription
                rebuildState()
            }
        }
    }

    func dismissNewPin() {
        newPin = nil
        rebuildState()
    }

    /// The radio answering is the person's own, reset or reflashed.
    func trustConnectedRadio() {
        session.trustConnectedRadio()
        rebuildState()
    }

    func showOnMap(_ saved: SavedRadio, onMap: Bool) {
        savedRadios.showOnMap(identifier: saved.identifier, onMap: onMap)
        rebuildState()
    }

    func setBeaconRate(_ rate: BeaconRate) {
        writePosition { position in
            var copy = position
            copy.positionBroadcastSecs = UInt32(rate.seconds)
            return copy
        }
    }

    func setBeaconWhenMoved(_ enabled: Bool) {
        writePosition { position in
            var copy = position
            copy.positionBroadcastSmartEnabled = enabled
            return copy
        }
    }

    /** Built from what the radio reported, since the firmware replaces the section. */
    func writePosition(_ change: @escaping @Sendable (Config.PositionConfig) -> Config.PositionConfig) {
        guard case .ready(let snapshot) = link.state.value, let position = snapshot.position else { return }
        error = nil
        rebuildState()
        Task {
            do {
                try await admin.setPositionConfig(position: change(position))
            } catch {
                self.error = error.localizedDescription
                rebuildState()
            }
        }
    }

    /**
     * Changes who the connected radio relays for.
     *
     * Built from the config the radio reported, because the firmware replaces the section rather than merging it.
     */
    func setRelayReach(_ reach: RelayReach) {
        guard case .ready(let snapshot) = link.state.value, var device = snapshot.device else { return }
        error = nil
        device.rebroadcastMode = reach.mode
        rebuildState()
        Task {
            do {
                try await admin.setDeviceConfig(device: device)
            } catch {
                self.error = error.localizedDescription
                rebuildState()
            }
        }
    }

    /// Renames the radio itself, which is what non-Firepit apps display.
    func renameNode(longName: String, shortName: String) {
        error = nil
        rebuildState()
        Task {
            do {
                try await owners.rename(longName: longName, shortName: shortName)
            } catch {
                self.error = error.localizedDescription
                rebuildState()
            }
        }
    }

    func disconnect() {
        Task {
            await session.disconnect()
            rebuildState()
        }
    }

    private func observeLink() async {
        for await state in link.state.subscribe() {
            rebuildState(linkState: state)
        }
    }

    private func observeNodes() async {
        for await nodes in mesh.observeNodes() {
            self.nodes = nodes
            rebuildState()
        }
    }

    private func observeMyNode() async {
        for await node in mesh.myNodeNum.subscribe() {
            myNodeNum = node
            rebuildState()
        }
    }

    private func observeOwner() async {
        for await owner in owners.owner {
            self.owner = owner
            rebuildState()
        }
    }

    private func observeBluetooth() async {
        for await state in presence.state() {
            bluetooth = state
            rebuildState()
        }
    }

    private func observeAvailability(_ central: BluetoothCentral) async {
        for await state in central.availability.subscribe() {
            bluetoothAvailability = state
            rebuildState()
        }
    }

    private func rebuildState(linkState: LinkState? = nil) {
        let linkState = linkState ?? link.state.value
        let saved = Self.savedWithMeshNames(savedRadios.radios, nodes: nodes)
        uiState = RadioUiState(
            scanning: scanning,
            found: found,
            link: linkState,
            details: linkState.radioDetails,
            error: error,
            notice: notice,
            nodes: Self.sortedNodes(nodes, myNodeNum: myNodeNum),
            myNodeNum: myNodeNum,
            tracing: tracing,
            traceResult: traceResult,
            saved: saved,
            owner: owner,
            connectedTo: linkState.isReady ? sessionStore.lastRadioId : nil,
            activeRadioId: sessionStore.lastRadioId,
            bluetooth: bluetooth,
            bluetoothAvailability: bluetoothAvailability,
            relayReach: linkState.relayReach,
            beaconRate: linkState.beaconRate,
            beaconWhenMoved: linkState.beaconWhenMoved,
            risks: linkState.risks,
            newPin: newPin,
            identityDoubt: session.identityDoubt
        )
    }

    private static func savedWithMeshNames(_ radios: [SavedRadio], nodes: [MeshNode]) -> [SavedRadio] {
        radios.map { radio in
            guard let nodeNum = radio.nodeNum,
                let meshName = nodes.first(where: { $0.nodeNum == nodeNum })?.longName,
                !meshName.trimmingCharacters(in: .whitespaces).isEmpty
            else { return radio }
            var copy = radio
            copy.name = meshName
            return copy
        }
    }

    static func sortedNodes(_ nodes: [MeshNode], myNodeNum: Int32?) -> [MeshNode] {
        nodes.sorted { lhs, rhs in
            let lSelf = lhs.nodeNum == myNodeNum
            let rSelf = rhs.nodeNum == myNodeNum
            if lSelf != rSelf { return lSelf }
            let lHops = lhs.hopsAway ?? Int.max
            let rHops = rhs.hopsAway ?? Int.max
            if lHops != rHops { return lHops < rHops }
            return (lhs.lastHeard ?? 0) > (rhs.lastHeard ?? 0)
        }
    }

    static func describeTrace(_ trace: TraceRouteResult?, name: String) -> String {
        guard let trace else { return "No reply from \(name) within a minute. It may be out of range." }
        if trace.isDirect { return "\(name) answered directly, no relay in between." }
        let hops = trace.towards.map { hop in
            MeshConstants.formatNodeId(hop.nodeNum) + (hop.snr.map { String(format: " (%.1f dB)", $0) } ?? "")
        }
        return "\(name) is \(trace.hopsOut) hops away, via " + hops.joined(separator: ", ")
    }

    static func describeBuzz(_ result: BuzzResult, name: String) -> String {
        switch result {
        case .delivered:
            return "\(name) took the buzz. Silence means its buzzer alert is off, not that it is missing."
        case .reachedMesh:
            return "The mesh carried the buzz, but \(name) never confirmed it. It may be out of range."
        case .noAnswer:
            return "\(name) did not answer. It may be off or out of range."
        case .noKey:
            return "\(name) has not introduced itself yet, so there is no private way to reach it. "
                + "Wait for it to appear on the mesh, then try again."
        case .refused(let reason):
            if reason == .noChannel {
                return "\(name) is not on this phone's primary channel, so it cannot be reached. "
                    + "Connect it once and Firepit will set the channel."
            }
            return "\(name) refused the buzz: \(prettyName(String(describing: reason)))."
        }
    }

    private static let savedScanWindowSeconds: UInt64 = 20
    private static let scanWindowSeconds: UInt64 = 60
    private static let noticeLifetimeSeconds: UInt64 = 8
}

private enum RadioChangeError: LocalizedError {
    case message(String)

    var errorDescription: String? {
        switch self {
        case .message(let text): text
        }
    }
}

extension LinkState {
    var isReady: Bool {
        if case .ready = self { return true }
        return false
    }

    var radioDetails: RadioDetails? {
        guard case .ready(let snapshot) = self else { return nil }
        return snapshot.toDetails()
    }

    var relayReach: RelayReach? {
        guard case .ready(let snapshot) = self else { return nil }
        return RelayReach.of(mode: snapshot.device?.rebroadcastMode)
    }

    var beaconRate: BeaconRate? {
        guard case .ready(let snapshot) = self else { return nil }
        return BeaconRate.of(seconds: snapshot.position.map { Int($0.positionBroadcastSecs) })
    }

    var beaconWhenMoved: Bool {
        guard case .ready(let snapshot) = self else { return false }
        return snapshot.position?.positionBroadcastSmartEnabled == true
    }

    var risks: [RadioRisk] {
        guard case .ready(let snapshot) = self else { return [] }
        return RadioSecurityCheck.risksOf(snapshot: snapshot)
    }
}

extension RadioSnapshot {
    func toDetails() -> RadioDetails {
        RadioDetails(
            nodeId: myNodeNum.map(MeshConstants.formatNodeId) ?? "",
            nodeNum: myNodeNum ?? 0,
            firmware: metadata?.firmwareVersion ?? "",
            hardware: metadata.map { String(describing: $0.hwModel) } ?? "",
            region: lora.map { String(describing: $0.region) } ?? "UNSET",
            rebootCount: Int(myInfo?.rebootCount ?? 0),
            capabilities: capabilities,
            channels: channels.values.sorted { $0.index < $1.index }.map { channel in
                let psk = channel.hasSettings ? channel.settings.psk : Data()
                return ChannelRow(
                    index: Int32(channel.index),
                    role: String(describing: channel.role),
                    name: channel.hasSettings && !channel.settings.name.isEmpty
                        ? channel.settings.name : "Default preset",
                    precision: channel.hasSettings ? Int(channel.settings.moduleSettings.positionPrecision) : 0,
                    key: ChannelKey.of(psk),
                    keyLabel: PrimaryChannel.keyLabel(psk)
                )
            },
            knownNodes: nodes.count
        )
    }
}

func prettyName(_ raw: String) -> String {
    raw.split(separator: "_")
        .filter { !$0.isEmpty }
        .map { part in
            let lower = part.lowercased()
            return lower.prefix(1).uppercased() + lower.dropFirst()
        }
        .joined(separator: " ")
}

/// Nodes report their own last_heard and some have no clock, so an unheard node says so plainly.
func nodeDetail(_ node: MeshNode, now: Int64 = currentEpochMillis()) -> String {
    var parts = [MeshConstants.formatNodeId(node.nodeNum)]
    if let hops = node.hopsAway { parts.append(hops == 0 ? "direct" : "\(hops) hops") }
    if let snr = node.snr { parts.append(String(format: "%.1f dB", snr)) }
    if let battery = node.batteryLevel { parts.append(battery > 100 ? "powered" : "\(battery)%") }
    parts.append(node.lastHeard.map { "heard \(shortAge($0, now: now))" } ?? "not heard yet")
    return parts.joined(separator: " · ")
}

/// Compact enough to sit on one line beside everything else.
func shortAge(_ epochMillis: Int64, now: Int64 = currentEpochMillis()) -> String {
    let minutes = max(0, (now - epochMillis) / 60_000)
    switch minutes {
    case ..<1: return "just now"
    case ..<60: return "\(minutes)m ago"
    case ..<(60 * 24): return "\(minutes / 60)h ago"
    case ..<(60 * 24 * 30): return "\(minutes / (60 * 24))d ago"
    default: return "long ago"
    }
}

func pinText(_ pin: UInt32) -> String {
    String(format: "%06d", pin)
}

func linkStateWords(_ state: LinkState, minimumFirmware: String = RadioCapabilities.minimumFirmware.raw) -> String {
    switch state {
    case .disconnected:
        return "Not connected"
    case .connecting:
        return "Connecting…"
    case .downloading:
        return "Reading your node…"
    case .ready:
        return "Active · you are administering this one"
    case .reconnecting(let attempt, let cause):
        return "Reconnecting (attempt \(attempt)) · \(cause)"
    case .unsupported(let version):
        return "Firmware \(version?.description ?? "unknown") · needs \(minimumFirmware) or newer"
    }
}

private func withTimeout(seconds: UInt64, operation: @escaping @MainActor () async -> Void) async {
    await withTaskGroup(of: Void.self) { group in
        group.addTask { await operation() }
        group.addTask { try? await Task.sleep(for: .seconds(seconds)) }
        await group.next()
        group.cancelAll()
    }
}

private func withTimeoutValue<T: Sendable>(
    seconds: UInt64,
    operation: @escaping @MainActor () async -> T?
) async -> T? {
    await withTaskGroup(of: T?.self) { group in
        group.addTask { await operation() }
        group.addTask {
            try? await Task.sleep(for: .seconds(seconds))
            return nil
        }
        let first = await group.next() ?? nil
        group.cancelAll()
        return first
    }
}
