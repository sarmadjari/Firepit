import FirepitData
import FirepitModel
import FirepitProtocol
import Foundation
import Observation
import os

private nonisolated let mapLog = Logger(subsystem: "com.getfirepit.app", category: "FirepitMap")

/// What asking somebody's radio for its position is doing, one person at a time.
enum LocationAsk: Equatable {
    case idle
    case asking(nodeNum: Int32)
    case answered(nodeNum: Int32, said: String)
    /// Working through everyone whose pin has gone old.
    case sweeping(done: Int, total: Int)
    case swept(said: String)

    var isBusy: Bool {
        switch self {
        case .asking, .sweeping: true
        default: false
        }
    }

    var notice: String? {
        switch self {
        case .sweeping(let done, let total): String(localized: "Asking \(done) of \(total)…")
        case .swept(let said): said
        default: nil
        }
    }
}

/// A node drawn on the map.
struct MapMarker: Identifiable, Hashable, Sendable {
    var node: MeshNode
    var isLive: Bool
    var isSelf: Bool
    /// Who is carrying the radio, when this phone knows. Otherwise what the radio calls itself.
    var name: String
    /// The 2-character disc label, from the person before the radio.
    var tag: String
    /// Set only for our own infrastructure, which is drawn as its role rather than a tag.
    var role: NodeRole?
    /// A colour picked by hand, or nil to derive one from the node number.
    var colourSlot: Int?
    /// How long ago they were at this spot, in minutes. Nil when no honest age can be shown.
    var fixAgeMinutes: Int64?

    var id: Int32 { node.nodeNum }
    var isApproximate: Bool { (node.positionPrecision ?? 32) < 32 }

    /// Degrees clockwise from north, or nil when they are not going anywhere.
    var course: Double? {
        guard let track = node.groundTrack, (node.groundSpeed ?? 0) >= Self.movingKmh else { return nil }
        let course = Double(track) / 100
        return (0...360).contains(course) ? course : nil
    }

    var coordinate: MapCoordinate? {
        guard let latitude = node.latitude, let longitude = node.longitude else { return nil }
        return MapCoordinate(latitude: latitude, longitude: longitude)
    }

    private static let movingKmh = 3
}

/// How much of the mesh the map draws.
enum MapFilter: String, CaseIterable, Identifiable, Sendable {
    case all
    case ours

    var id: String { rawValue }

    var label: String {
        switch self {
        case .all: String(localized: "All nodes")
        case .ours: String(localized: "Our nodes")
        }
    }
}

struct MapCoordinate: Hashable, Sendable {
    var latitude: Double
    var longitude: Double
}

/// The conversation open beside the map, which the map follows (UX §6.11.6). Only set while both sides are on
/// screen. Ported from android/app/…/map/MapViewModel.kt.
nonisolated enum Following: Equatable, Sendable {
    case room(roomId: Int32, name: String)
    case direct(nodeNum: Int32, name: String)

    var name: String {
        switch self {
        case .room(_, let name), .direct(_, let name): name
        }
    }
}

/// What the map draws and where a pin goes, kept pure so it can be tested. Ported from `MapSelection` in
/// android/app/…/map/MapViewModel.kt.
nonisolated enum MapSelection {
    /// The open conversation, unless following was stopped for that very one.
    static func followed(open: Following?, stopped: Following?) -> Following? {
        guard let open, open != stopped else { return nil }
        return open
    }

    /// The followed conversation's people and always you; otherwise the chosen filter.
    static func nodes(
        _ onMap: [MeshNode],
        following: Following?,
        followedNodes: Set<Int32>,
        filter: MapFilter,
        ours: Set<Int32>,
        myNodeNum: Int32?
    ) -> [MeshNode] {
        if following != nil {
            return onMap.filter { followedNodes.contains($0.nodeNum) || $0.nodeNum == myNodeNum }
        }
        return filter == .all ? onMap : onMap.filter { ours.contains($0.nodeNum) }
    }

    /// A followed room's own pins, none for a direct chat, and every pin otherwise.
    static func pins(_ pins: [MapPin], following: Following?) -> [MapPin] {
        switch following {
        case nil: pins
        case .room(let roomId, _)?: pins.filter { $0.roomId == roomId }
        case .direct?: []
        }
    }

    /// The room a dropped pin goes to: the one open beside the map, then the one your location is shared with, then
    /// the only one there is. Nil rather than a guess.
    static func pinRoom(_ shareable: [RoomChannel], open: Following?, sharingRoomId: Int32?) -> RoomChannel? {
        if case .room(let roomId, _)? = open, let room = shareable.first(where: { $0.id == roomId }) { return room }
        if let room = shareable.first(where: { $0.id == sharingRoomId }) { return room }
        return shareable.count == 1 ? shareable[0] : nil
    }
}

struct MapUiState: Equatable {
    var connected = false
    var markers: [MapMarker] = []
    var pins: [MapPin] = []
    var rooms: [RoomChannel] = []
    var sharingRoomId: Int32?
    var ownPosition: OwnPosition.Fix?
    var myNodeNum: Int32?
    /// Everyone in a room with us, which is everyone we have a private way to ask.
    var roomMembers: Set<Int32> = []
    var filter: MapFilter = .all
    /// The conversation beside the map, when there is one, followed or not.
    var followable: Following?
    /// What the map is following now: `followable`, unless the user stopped.
    var following: Following?
    /// How many were left out by `filter` or `following`, so a thinned map says so.
    var hiddenByFilter = 0
    var busy = false
    var error: String?

    var isSharing: Bool { sharingRoomId != nil }
}

/// Map state and actions. Ported from android/app/…/map/MapViewModel.kt.
@Observable
final class MapViewModel {
    private(set) var uiState = MapUiState()
    private(set) var offlineOnly: Bool
    private(set) var areas: [OfflineArea] = []
    private(set) var ask: LocationAsk = .idle

    @ObservationIgnored private let location: LocationRepository
    @ObservationIgnored private let waypoints: WaypointRepository
    @ObservationIgnored private let mesh: MeshRepository
    @ObservationIgnored private let offlineMaps: OfflineMapRepository
    @ObservationIgnored private let savedRadios: SavedRadioStore
    @ObservationIgnored private let people: PersonStore
    @ObservationIgnored private let rooms: RoomRepository
    @ObservationIgnored private let mapPreferences: MapPreferences
    @ObservationIgnored private var sweep: Task<Void, Never>?
    @ObservationIgnored private var busy = false
    @ObservationIgnored private var error: String?
    @ObservationIgnored private var filter: MapFilter = .all
    @ObservationIgnored private var nodes: [MeshNode] = []
    @ObservationIgnored private var groupNodes: Set<Int32> = []
    @ObservationIgnored private var cards: [Int32: PersonCard] = [:]
    @ObservationIgnored private var pins: [MapPin] = []
    @ObservationIgnored private var followable: Following?
    /// Following stopped for this conversation; opening another starts it again.
    @ObservationIgnored private var stoppedFollowing: Following?
    @ObservationIgnored private var followedNodes: Set<Int32> = []
    @ObservationIgnored private var followTask: Task<Void, Never>?
    @ObservationIgnored private var visibleScreens: Set<UUID> = []

    /// Where the map was last looking. Unfolding, folding or rotating rebuilds the map's view, and it picks up here
    /// rather than back at the world (UX §6.11.5). In memory only: never saved or sent. Not observed, so panning does
    /// not redraw the screen.
    @ObservationIgnored var camera: MapCamera?

    /// The camera has been to everyone already, so it is not pulled there again on its own.
    var framed = false
    @ObservationIgnored private var nowMillis: Int64

    init(
        location: LocationRepository,
        waypoints: WaypointRepository,
        mesh: MeshRepository,
        offlineMaps: OfflineMapRepository,
        savedRadios: SavedRadioStore,
        people: PersonStore,
        rooms: RoomRepository,
        mapPreferences: MapPreferences,
        nowMillis: Int64 = currentEpochMillis()
    ) {
        self.location = location
        self.waypoints = waypoints
        self.mesh = mesh
        self.offlineMaps = offlineMaps
        self.savedRadios = savedRadios
        self.people = people
        self.rooms = rooms
        self.mapPreferences = mapPreferences
        self.nowMillis = nowMillis
        offlineOnly = mapPreferences.offlineOnly
        recompute()
    }

    convenience init(app: AppContainer) {
        self.init(
            location: app.location,
            waypoints: app.waypoints,
            mesh: app.mesh,
            offlineMaps: app.offlineMaps,
            savedRadios: app.savedRadios,
            people: app.people,
            rooms: app.rooms,
            mapPreferences: app.mapPreferences
        )
    }

    /// Follows map repositories while the screen is up. Call from `.task`.
    ///
    /// Safe to call again from a screen built later: the model outlives its screens (`Workspace`), and a tab or a
    /// layout coming back starts its task again. Two can overlap for a moment while one screen replaces another.
    func observe() async {
        areas = await offlineMaps.areas()
        recompute()
        await withTaskGroup(of: Void.self) { group in
            group.addTask { await self.observePositions() }
            group.addTask { await self.observeGroupNodes() }
            group.addTask { await self.observePersonCards() }
            group.addTask { await self.observePins() }
            group.addTask { await self.observeChannels() }
            group.addTask { await self.observeNodeNum() }
            group.addTask { await self.observeConnectivity() }
            group.addTask { await self.observeOwnPosition() }
            group.addTask { await self.tick() }
        }
    }

    func askWhereTheyAre(nodeNum: Int32, name: String) {
        guard !ask.isBusy else { return }
        Task {
            ask = .asking(nodeNum: nodeNum)
            let answer = await location.requestPosition(nodeNum: nodeNum)
            ask = .answered(nodeNum: nodeNum, said: MapWords.saidOf(answer: answer, name: name))
        }
    }

    /// Asks stale room members for a fresh position.
    func askEveryone() {
        guard !ask.isBusy else { return }
        let state = uiState
        let theirs = state.markers.filter { !$0.isSelf && state.roomMembers.contains($0.node.nodeNum) }
        guard !theirs.isEmpty else {
            ask = .swept(said: String(localized: "Nobody from your rooms is on the map yet."))
            return
        }
        let stale = theirs.filter { ($0.fixAgeMinutes ?? .max) >= Self.freshMinutes }
        let current = theirs.count - stale.count
        guard !stale.isEmpty else {
            ask = .swept(
                said: String(
                    localized: "Everyone in your rooms already has a current pin. Tap somebody to ask them anyway."
                ))
            return
        }
        sweep?.cancel()
        sweep = Task {
            var asked = 0
            var outOfReach = 0
            for (index, marker) in stale.enumerated() {
                if Task.isCancelled { return }
                ask = .sweeping(done: index + 1, total: stale.count)
                let answer = await location.askForPosition(nodeNum: marker.node.nodeNum)
                switch answer {
                case .asked:
                    asked += 1
                case .notConnected:
                    ask = .swept(said: String(localized: "Connect your own radio first."))
                    return
                default:
                    outOfReach += 1
                }
            }
            ask = .swept(said: MapWords.sweptWords(asked: asked, outOfReach: outOfReach, current: current))
        }
    }

    func clearAsk() {
        sweep?.cancel()
        sweep = nil
        ask = .idle
    }

    func setFilter(_ choice: MapFilter) {
        filter = choice
        // Choosing what to show is choosing not to follow, until another conversation is opened.
        stoppedFollowing = followable
        refollow()
    }

    /// The conversation now beside the map, or nil when there is none or the map is alone.
    func follow(_ target: Following?) {
        guard target != followable else { return }
        followable = target
        stoppedFollowing = nil
        refollow()
    }

    func resumeFollowing() {
        stoppedFollowing = nil
        refollow()
    }

    private func refollow() {
        followTask?.cancel()
        followTask = nil
        followedNodes = []
        switch MapSelection.followed(open: followable, stopped: stoppedFollowing) {
        case .room(let roomId, _)?:
            let members = rooms.observeMembers(roomId: roomId)
            followTask = Task { [weak self] in
                for await rows in members {
                    guard let self, !Task.isCancelled else { return }
                    self.followedNodes = Set(rows.map(\.nodeNum))
                    self.recompute()
                }
            }
        case .direct(let nodeNum, _)?:
            followedNodes = [nodeNum]
        case nil:
            break
        }
        recompute()
    }

    /// Pins go to one private room, never to the public primary channel.
    func dropPin(latitudeI: Int32, longitudeI: Int32, name: String) {
        let shareable = uiState.rooms.filter(PositionSharing.canShare(channel:))
        let room = MapSelection.pinRoom(shareable, open: followable, sharingRoomId: uiState.sharingRoomId)
        guard let room else {
            error = shareable.isEmpty ? MapWords.noPinRoom : MapWords.choosePinRoom
            recompute()
            return
        }
        run(fallback: String(localized: "Could not drop the pin")) {
            try await self.waypoints.drop(
                channel: room.index,
                latitudeI: latitudeI,
                longitudeI: longitudeI,
                name: name
            )
        }
    }

    func removePin(_ pin: MapPin) {
        run(fallback: String(localized: "Could not remove the pin")) { try await self.waypoints.remove(pin: pin) }
    }

    /// Called while a map screen is on screen, so the phone's own fix can be shown. Counted per screen: a screen
    /// rebuilt for a new layout appears before the old one goes, and the old one leaving must not hide the new one.
    func setMapVisible(_ visible: Bool, screen: UUID) {
        if visible { visibleScreens.insert(screen) } else { visibleScreens.remove(screen) }
        location.setMapVisible(visible: !visibleScreens.isEmpty)
    }

    func setLocationAllowed(_ allowed: Bool) {
        location.setLocationAllowed(allowed: allowed)
    }

    func clearError() {
        error = nil
        recompute()
    }

    func reportPermissionDenied() {
        error = String(localized: "Firepit needs location permission to share where you are.")
        recompute()
    }

    private func run(fallback: String, block: @escaping () async throws -> Void) {
        Task {
            busy = true
            error = nil
            recompute()
            do {
                try await block()
            } catch {
                self.error = error.localizedDescription.isEmpty ? fallback : error.localizedDescription
            }
            busy = false
            recompute()
        }
    }

    private func observePositions() async {
        for await next in location.observePositions() {
            nodes = next
            recompute()
        }
    }

    private func observeGroupNodes() async {
        for await next in rooms.observeGroupNodes() {
            groupNodes = next
            recompute()
        }
    }

    private func observePersonCards() async {
        for await next in rooms.observePersonCards() {
            cards = next
            recompute()
        }
    }

    private func observePins() async {
        for await next in waypoints.observePins() {
            pins = next
            recompute()
        }
    }

    private func observeChannels() async {
        for await _ in mesh.channels.subscribe() { recompute() }
    }

    private func observeNodeNum() async {
        for await _ in mesh.myNodeNum.subscribe() { recompute() }
    }

    private func observeConnectivity() async {
        for await _ in mesh.isConnected.subscribe() { recompute() }
    }

    private func observeOwnPosition() async {
        for await _ in location.chosenOwnPosition.subscribe() { recompute() }
    }

    private func tick() async {
        while !Task.isCancelled {
            nowMillis = currentEpochMillis()
            recompute()
            try? await Task.sleep(for: Self.tickInterval)
        }
    }

    private func recompute() {
        offlineOnly = mapPreferences.offlineOnly
        let hidden = SavedRadios.hiddenNodes(radios: savedRadios.radios)
        var ours = Set<Int32>()
        if let myNodeNum = mesh.myNodeNum.value { ours.insert(myNodeNum) }
        ours.formUnion(groupNodes)
        savedRadios.radios.compactMap(\.nodeNum).forEach { ours.insert($0) }
        let onMap = nodes.filter { !hidden.contains($0.nodeNum) }
        let following = MapSelection.followed(open: followable, stopped: stoppedFollowing)
        let shown = MapSelection.nodes(
            onMap,
            following: following,
            followedNodes: followedNodes,
            filter: filter,
            ours: ours,
            myNodeNum: mesh.myNodeNum.value
        ).filter { $0.nodeNum != mesh.myNodeNum.value }
        uiState = MapUiState(
            connected: mesh.isConnected.value,
            markers: shown.map(marker(for:)),
            pins: MapSelection.pins(pins, following: following),
            rooms: ChannelSlotManager.rooms(channels: mesh.channels.value),
            sharingRoomId: location.sharingRoomId(),
            ownPosition: location.chosenOwnPosition.value,
            myNodeNum: mesh.myNodeNum.value,
            roomMembers: groupNodes,
            filter: filter,
            followable: followable,
            following: following,
            hiddenByFilter: onMap.count - shown.count,
            busy: busy,
            error: error
        )
    }

    private func marker(for node: MeshNode) -> MapMarker {
        let myNodeNum = mesh.myNodeNum.value
        let isSelf = node.nodeNum == myNodeNum
        let person = isSelf ? people.person : nil
        let card = cards[node.nodeNum]
        let fixAge = (node.positionTime ?? node.lastHeard).map { max(0, nowMillis - $0) / 60_000 }
        return MapMarker(
            node: node,
            isLive: fixAge.map { $0 < Self.liveMinutes } ?? false,
            isSelf: isSelf,
            name: MapWords.markerName(node: node, person: person, card: card, isSelf: isSelf),
            tag: MapWords.markerTag(node: node, person: person, card: card),
            role: savedRadios.radios.first { $0.nodeNum == node.nodeNum }?.role.roleForMap(card: card, isSelf: isSelf),
            colourSlot: person?.colourSlot,
            fixAgeMinutes: fixAge
        )
    }

    private static let tickInterval: Duration = .seconds(20)
    private static let liveMinutes: Int64 = 15
    private static let freshMinutes: Int64 = 2
}

extension NodeRole {
    fileprivate func roleForMap(card: PersonCard?, isSelf: Bool) -> NodeRole? {
        guard self != .personal, card == nil, !isSelf else { return nil }
        return self
    }
}

/// Pure words and reducers used by the map and by tests.
nonisolated enum MapWords {
    static let noPinRoom = String(
        localized: """
            Pins go to one private room. Create or join a private room first — a pin carries a place and a name, \
            so it is never put on a public channel.
            """
    )
    static let choosePinRoom = String(
        localized: "Choose which room to share your map with first, so the pin has somewhere to go."
    )

    static func markerName(node: MeshNode, person: Person?, card: PersonCard?, isSelf: Bool) -> String {
        firstNonBlank(person?.name, card?.name, isSelf ? String(localized: "You") : node.displayName)
    }

    static func markerTag(node: MeshNode, person: Person?, card: PersonCard?) -> String {
        firstNonBlank(person?.tag, card?.tag, node.shortName, "?")
    }

    static func markerLabel(_ marker: MapMarker) -> String {
        if marker.isSelf { return marker.name }
        let radio = marker.node.positionFromRadio ? " · radio" : ""
        guard let minutes = marker.fixAgeMinutes, minutes >= 1 else { return marker.name + radio }
        if minutes < 60 { return String(localized: "\(marker.name)\(radio) · \(minutes)m") }
        if minutes < 60 * 24 { return String(localized: "\(marker.name)\(radio) · \(minutes / 60)h") }
        return String(localized: "\(marker.name)\(radio) · \(minutes / (60 * 24))d")
    }

    static func agePhrase(minutes: Int64) -> String {
        switch minutes {
        case ..<1: String(localized: "just now")
        case 1: String(localized: "1 minute ago")
        case 2..<60: String(localized: "\(minutes) minutes ago")
        case 60..<120: String(localized: "1 hour ago")
        case 120..<60 * 24: String(localized: "\(minutes / 60) hours ago")
        case 60 * 24..<60 * 48: String(localized: "1 day ago")
        default: String(localized: "\(minutes / (60 * 24)) days ago")
        }
    }

    static func tally(_ state: MapUiState) -> String {
        let people = state.markers.filter { !$0.isSelf }.count
        let live = state.markers.filter { !$0.isSelf && $0.isLive }.count
        var parts = [people == 1 ? String(localized: "1 person") : String(localized: "\(people) people")]
        // Says why the map is thinner than usual while a conversation sits beside it.
        if let following = state.following { parts.insert(String(localized: "Following \(following.name)"), at: 0) }
        if live > 0 { parts.append(String(localized: "\(live) live")) }
        if state.pins.count == 1 { parts.append(String(localized: "1 pin")) }
        if state.pins.count > 1 { parts.append(String(localized: "\(state.pins.count) pins")) }
        return parts.joined(separator: " · ")
    }

    static func sweptWords(asked: Int, outOfReach: Int, current: Int) -> String {
        var text: String
        switch asked {
        case 0: text = String(localized: "Nobody could be asked")
        case 1: text = String(localized: "Asked 1 person; their pin moves when they answer")
        default: text = String(localized: "Asked \(asked) people; pins move as they answer")
        }
        if current > 0 { text += String(localized: ". \(current) already current") }
        if outOfReach > 0 { text += String(localized: ". \(outOfReach) out of reach") }
        return text + "."
    }

    static func saidOf(answer: PositionAnswer, name: String) -> String {
        switch answer {
        case .answered:
            String(localized: "\(name) answered. Their pin is where they are now.")
        case .asked:
            String(localized: "Asked \(name). Their pin moves when they answer.")
        case .silent:
            String(
                localized: """
                    No answer from \(name). They are out of range, away from their radio, or not sharing with a room \
                    you are both in — the pin still shows where they were last seen.
                    """
            )
        case .notConnected:
            String(localized: "Connect your own radio first.")
        case .noSharedRoom:
            String(localized: "\(name) is not in one of your Firepit rooms, so there is no private way to ask.")
        }
    }

    static func firstNonBlank(_ values: String?...) -> String {
        values.compactMap { value in
            value?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false ? value : nil
        }.first ?? ""
    }
}

private struct MapLens {
    let group: Set<Int32>
    let now: Int64
}

private struct MapAside {
    let radios: [SavedRadio]
}
