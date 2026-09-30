import FirepitCrypto
import FirepitData
import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation
import Observation

struct InviteState: Equatable {
    var payload: String
    /// Whole seconds left before the code is redrawn, for the countdown ring.
    var secondsRemaining: Int
}

struct RoomsUiState: Equatable {
    var connected = false
    var rooms: [RoomChannel] = []
    var isFull = false
    var busy = false
    var invite: InviteState?
    var joinedRoomName: String?
    var error: String?
}

/// What a path check is doing, for one member at a time.
enum TraceState: Equatable {
    case idle
    case running(nodeNum: Int32)
    case done(nodeNum: Int32, summary: String)
}

/// A Firepit code that has been scanned but not acted on.
struct ScannedInvite: Equatable {
    var roomName: String
    var inviterId: String
    /// The inviter's key as the code states it. Their own screen shows theirs.
    var fingerprint: String?
    var invite: Meshchat_Invite
}

/// A roster row: who they are, plus who vouched for them if anyone did.
struct MemberRow: Identifiable, Equatable {
    var member: RoomMember
    var node: MeshNode?
    /// What they call themselves. Claimed, not proven — see `nodeId`.
    var card: PersonCard?
    var invitedByName: String?
    var isSelf: Bool

    var id: Int32 { member.nodeNum }

    var displayName: String {
        if let name = card?.name, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return name
        }
        return node?.displayName ?? nodeId
    }

    var shortName: String? {
        guard let tag = card?.tag, !tag.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return node?.shortName
        }
        return tag
    }

    /// The only identity the mesh attests. Anyone can claim any name.
    var nodeId: String { MeshConstants.formatNodeId(member.nodeNum) }

    var isNameClaimed: Bool {
        card?.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
    }
}

/// The room screens' state and actions. Ported from android/app/…/rooms/RoomsViewModel.kt.
@Observable
final class RoomsViewModel {
    private(set) var uiState = RoomsUiState()
    private(set) var trace: TraceState = .idle
    private(set) var rotation: String?
    private(set) var scanned: ScannedInvite?
    private(set) var pendingJoins: [PendingJoin] = []
    private(set) var awaiting: AwaitedRoom?
    private(set) var askToMakeRadioPrivate = false

    @ObservationIgnored private let rooms: RoomRepository
    @ObservationIgnored private let mesh: MeshRepository
    @ObservationIgnored private let range: RangeRepository
    @ObservationIgnored private let traceroute: TracerouteClient
    @ObservationIgnored private var rotationTask: Task<Void, Never>?

    @ObservationIgnored private var busy = false
    @ObservationIgnored private var invite: InviteState?
    @ObservationIgnored private var notice: String?
    @ObservationIgnored private var failure: String?

    init(rooms: RoomRepository, mesh: MeshRepository, range: RangeRepository, traceroute: TracerouteClient) {
        self.rooms = rooms
        self.mesh = mesh
        self.range = range
        self.traceroute = traceroute
        recompute()
    }

    convenience init(app: AppContainer) {
        self.init(rooms: app.rooms, mesh: app.mesh, range: app.range, traceroute: app.traceroute)
    }

    deinit {
        rotationTask?.cancel()
    }

    /// Follows repository state while a room screen is visible. Call from `.task`.
    func observe() async {
        let connected = mesh.isConnected.subscribe()
        let channels = mesh.channels.subscribe()
        let needsChoice = range.needsChoice.subscribe()
        let pending = rooms.pendingJoins.subscribe()
        let waiting = rooms.awaiting.subscribe()
        await withTaskGroup(of: Void.self) { group in
            group.addTask { for await _ in connected { await self.recompute() } }
            group.addTask { for await _ in channels { await self.recompute() } }
            group.addTask { for await _ in needsChoice { await self.recompute() } }
            group.addTask {
                for await value in pending {
                    await MainActor.run { self.pendingJoins = value }
                }
            }
            group.addTask {
                for await value in waiting {
                    await MainActor.run { self.awaiting = value }
                }
            }
        }
    }

    /// Checks the path to a member.
    func checkPath(nodeNum: Int32, name: String) {
        Task {
            trace = .running(nodeNum: nodeNum)
            let result = await traceroute.trace(nodeNum: nodeNum)
            trace = .done(nodeNum: nodeNum, summary: Self.traceSummary(result: result, name: name))
        }
    }

    func clearTrace() {
        trace = .idle
    }

    func makeRadioPrivate() {
        run("Could not change the radio") { try await self.range.makePrivate() }
    }

    func approveJoin(nodeNum: Int32) {
        run("Could not let them in") { try await self.rooms.approveJoin(nodeNum: nodeNum) }
    }

    func declineJoin(nodeNum: Int32) {
        run("Could not turn them away") { await self.rooms.declineJoin(nodeNum: nodeNum) }
    }

    func stopWaiting() {
        rooms.stopWaiting()
    }

    func keepRadioPublic() {
        run("Could not put the radio's own channel back") { try await self.range.keepPublic() }
    }

    func clearMessages() {
        failure = nil
        notice = nil
        recompute()
    }

    /// The room's roster, newest sighting first, with our own entry pinned to the top.
    ///
    /// Kotlin's `combine` of the roster, the node list, our own number and the person cards: nothing is emitted until
    /// each has spoken once, and a change to any of them re-emits, so a name that arrives after the roster still lands.
    /// The four subscriptions are children of one task, so ending the stream ends all of them.
    func members(roomId: Int32) -> AsyncStream<[MemberRow]> {
        let mesh = mesh
        let rooms = rooms
        return AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let task = Task { @MainActor in
                let latest = LatestRoster()
                @MainActor func emit() {
                    guard let members = latest.members, let nodes = latest.nodes, let cards = latest.cards,
                        let me = latest.me
                    else { return }
                    continuation.yield(Self.memberRows(members: members, nodes: nodes, myNodeNum: me, cards: cards))
                }
                await awaitObservers([
                    Task {
                        for await value in rooms.observeMembers(roomId: roomId) {
                            latest.members = value
                            emit()
                        }
                    },
                    Task {
                        for await value in mesh.observeNodes() {
                            latest.nodes = value
                            emit()
                        }
                    },
                    Task {
                        for await value in mesh.myNodeNum.subscribe() {
                            latest.me = .some(value)
                            emit()
                        }
                    },
                    Task {
                        for await value in rooms.observePersonCards() {
                            latest.cards = value
                            emit()
                        }
                    },
                ])
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func createRoom(name: String) {
        run("Could not create the room") {
            let room = try await self.rooms.createRoom(name: name)
            await MainActor.run { self.notice = "Created \(room.name)" }
        }
    }

    /// `slot` says which channel is meant when it has no room id of its own.
    func leaveRoom(roomId: Int32, slot: Int? = nil) {
        run("Could not leave the room") { try await self.rooms.leaveRoom(roomId: roomId, slot: slot) }
    }

    func clearRotation() {
        rotation = nil
    }

    func removeMember(roomId: Int32, nodeNum: Int32) {
        run("Could not remove them") {
            let result = try await self.rooms.rotateRoom(roomId: roomId, remove: [nodeNum])
            await MainActor.run { self.rotation = Self.rotationMessage(result) }
        }
    }

    func joinFromScan(scanned raw: String) {
        run("Could not join") {
            guard await MainActor.run(body: { self.scanned == nil }) else { return }
            let result = Self.handleScan(raw)
            switch result {
            case .ask(let invite):
                await MainActor.run {
                    self.scanned = invite
                    self.notice = nil
                }
            case .joinMeshtastic(let shared):
                let added = try await self.rooms.joinMeshtasticChannels(shared: shared)
                await MainActor.run {
                    self.notice = Self.meshtasticAddedMessage(count: added.count, first: added.first)
                }
            case .error(let message):
                await MainActor.run { self.failure = message }
            }
        }
    }

    func confirmScan() {
        guard let pending = scanned else { return }
        scanned = nil
        run("Could not join") { try await self.rooms.joinRoom(invite: pending.invite) }
    }

    func cancelScan() {
        scanned = nil
    }

    /// This radio's own key, shown beside an invite so the joiner can check the code is ours.
    func ownFingerprint() -> String? {
        rooms.ownFingerprint()
    }

    func addSharedChannel(name: String) {
        run("Could not add the channel") {
            let room = try await self.rooms.addMeshtasticChannel(name: name, psk: RoomCrypto.generatePsk())
            await MainActor.run {
                self.notice =
                    "Added \(room.name). Share it from the Meshtastic app — "
                    + "Firepit only issues its own invites."
            }
        }
    }

    /// Re-issues the code every rotation window so a photographed invite stops working within seconds.
    func startInviteRotation(roomId: Int32) {
        rotationTask?.cancel()
        rotationTask = Task {
            while !Task.isCancelled {
                let now = currentEpochMillis()
                let remaining = Self.remainingSeconds(nowMillis: now)
                do {
                    let built = try await rooms.buildInvite(roomId: roomId, nowMillis: now)
                    invite = InviteState(payload: InviteCodec.encode(built), secondsRemaining: remaining)
                    recompute()
                } catch {
                    failure = Self.errorMessage(error, fallback: nil)
                    recompute()
                }
                for _ in 0..<remaining where !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(1))
                    if let current = invite {
                        invite = InviteState(
                            payload: current.payload,
                            secondsRemaining: max(current.secondsRemaining - 1, 0)
                        )
                        recompute()
                    }
                }
            }
        }
    }

    func stopInvite() {
        rotationTask?.cancel()
        rotationTask = nil
        invite = nil
        recompute()
    }

    private func run(_ fallback: String, block: @escaping @Sendable () async throws -> Void) {
        Task {
            busy = true
            failure = nil
            recompute()
            do {
                try await block()
            } catch {
                failure = Self.errorMessage(error, fallback: fallback)
            }
            busy = false
            recompute()
        }
    }

    private func recompute() {
        let roomChannels = ChannelSlotManager.rooms(channels: mesh.channels.value)
        uiState = RoomsUiState(
            connected: mesh.isConnected.value,
            rooms: roomChannels,
            isFull: ChannelSlotManager.isFull(channels: mesh.channels.value),
            busy: busy,
            invite: invite,
            joinedRoomName: notice,
            error: failure
        )
        askToMakeRadioPrivate = range.needsChoice.value && roomChannels.contains { $0.kind == .firepit }
        pendingJoins = rooms.pendingJoins.value
        awaiting = rooms.awaiting.value
    }
}

enum ScanAction: Equatable {
    case ask(ScannedInvite)
    case joinMeshtastic(ChannelUrl.Shared)
    case error(String)
}

extension RoomsViewModel {
    nonisolated static func remainingSeconds(nowMillis: Int64) -> Int {
        let period = RoomCrypto.rotationSeconds * 1000
        let remaining = (period - nowMillis % period) / 1000
        return min(max(Int(remaining), 1), Int(RoomCrypto.rotationSeconds))
    }

    nonisolated static func errorMessage(_ error: Error, fallback: String?) -> String {
        if let roomError = error as? RoomError {
            return roomError.message
        }
        let description = error.localizedDescription
        return (error as? LocalizedError)?.errorDescription
            ?? (description.isEmpty ? nil : description)
            ?? fallback
            ?? "Something went wrong"
    }

    nonisolated static func handleScan(_ raw: String) -> ScanAction {
        switch CodeScanner.classify(raw) {
        case .firepit(let invite):
            let inviter = invite.inviter
            let fingerprint = KeyFingerprint.of(publicKeyBase64: inviter.user.publicKey.base64EncodedString())
            return .ask(
                ScannedInvite(
                    roomName: invite.roomName,
                    inviterId: MeshConstants.formatNodeId(Int32(bitPattern: inviter.nodeNum)),
                    fingerprint: fingerprint,
                    invite: invite
                )
            )
        case .meshtastic(let shared):
            return .joinMeshtastic(shared)
        case .firepitUnreadable(let reason):
            switch reason {
            case .newerVersion:
                return .error("This Firepit invite was made by a newer version of the app. Update to join.")
            case .malformed:
                return .error("This Firepit invite is damaged or has expired — ask for a fresh one.")
            }
        case .unrecognised:
            return .error("That isn't a Firepit invite or a Meshtastic channel link")
        }
    }

    nonisolated static func memberRows(
        members: [RoomMember],
        nodes: [MeshNode],
        myNodeNum: Int32?,
        cards: [Int32: PersonCard]
    ) -> [MemberRow] {
        let byNum = Dictionary(nodes.map { ($0.nodeNum, $0) }, uniquingKeysWith: { _, last in last })
        return members.map { member in
            MemberRow(
                member: member,
                node: byNum[member.nodeNum],
                card: cards[member.nodeNum],
                invitedByName: member.invitedBy.flatMap { inviter in
                    byNum[inviter]?.displayName ?? MeshConstants.formatNodeId(inviter)
                },
                isSelf: member.nodeNum == myNodeNum
            )
        }
        .sorted { left, right in
            if left.isSelf != right.isSelf { return left.isSelf }
            return (left.member.lastHeard ?? left.member.firstSeen) > (right.member.lastHeard ?? right.member.firstSeen)
        }
    }

    nonisolated static func traceSummary(result: TraceRouteResult?, name: String) -> String {
        guard let result else { return "No reply from \(name). They may be out of range." }
        if result.isDirect { return "\(name) answered directly, no relay." }
        let path = result.towards.map { hop in
            MeshConstants.formatNodeId(hop.nodeNum) + (hop.snr.map { String(format: " (%.1f dB)", $0) } ?? "")
        }
        .joined(separator: ", ")
        return "\(name) is \(result.hopsOut) hops away, via \(path)"
    }

    nonisolated static func rotationMessage(_ result: RotationResult) -> String {
        if result.missed.isEmpty {
            return "Removed. Everyone still in the room confirmed they have the new key."
        }
        if result.reached.isEmpty {
            return "Removed, and the room has a new key. Nobody else confirmed getting it yet. "
                + "Firepit will hand it over as each of them is heard again; anyone who can't be reached "
                + "for long may need a new invite."
        }
        let count = result.missed.count
        return "Removed, and the room has a new key. \(count) member\(count == 1 ? " hasn't" : "s haven't") "
            + "confirmed getting it yet. Firepit will hand it over when they're heard again."
    }

    nonisolated static func meshtasticAddedMessage(count: Int, first: RoomChannel?) -> String {
        if count == 1, let first {
            return "Added \(first.name). Standard Meshtastic — other Meshtastic apps can read it, "
                + "and Firepit's own features are off."
        }
        return "Added \(count) Meshtastic channels. Other Meshtastic apps can read them, "
            + "and Firepit's own features are off."
    }
}

func relativeTime(epochMillis: Int64, nowMillis: Int64 = currentEpochMillis()) -> String {
    let elapsed = max(0, nowMillis - epochMillis)
    let minutes = elapsed / 60_000
    let hours = elapsed / 3_600_000
    let days = elapsed / 86_400_000
    if minutes < 1 { return "just now" }
    if minutes < 60 { return "\(minutes)m ago" }
    if hours < 24 { return "\(hours)h ago" }
    return "\(days)d ago"
}

func prettyHardware(_ model: String) -> String {
    model.split(separator: "_").map { part in
        let lower = part.lowercased()
        return lower.prefix(1).uppercased() + lower.dropFirst()
    }
    .joined(separator: " ")
}

/// The node id first, then hardware, charge and when we last heard them.
func memberDetail(_ row: MemberRow, nowMillis: Int64 = currentEpochMillis()) -> String {
    var parts = [row.nodeId]
    if let model = row.node?.hwModel, !model.isEmpty {
        parts.append(prettyHardware(model))
    }
    if let battery = row.node?.batteryLevel {
        parts.append(battery > 100 ? "powered" : "\(battery)%")
    }
    parts.append(
        row.member.lastHeard.map { "heard \(relativeTime(epochMillis: $0, nowMillis: nowMillis))" } ?? "not heard yet")
    return parts.joined(separator: " · ")
}

/// The newest value from each of the roster's sources. `me` is doubly optional: "not heard yet" differs from "no node".
@MainActor
private final class LatestRoster {
    var members: [RoomMember]?
    var nodes: [MeshNode]?
    var me: Int32??
    var cards: [Int32: PersonCard]?
}
