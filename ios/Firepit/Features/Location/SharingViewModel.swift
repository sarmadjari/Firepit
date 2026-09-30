import CoreLocation
import FirepitData
import FirepitModel
import FirepitProtocol
import Foundation
import Observation
import os

private nonisolated let sharingLog = Logger(subsystem: "com.getfirepit.app", category: "FirepitSharing")

/// Where your position is going. Ported from android/app/…/location/SharingViewModel.kt (SharingUiState).
struct SharingUiState: Hashable {
    var connected = false
    /// Private rooms only: a Meshtastic channel reaches people nobody chose.
    var rooms: [RoomChannel] = []
    var roomId: Int32?
    var roomName: String?
    var choice: ShareDuration = .default
    var endsAt: Int64?
    /// Chosen, but not going anywhere now: the phone is away from its radio, or the radio connected does not carry
    /// the room. It resumes on its own.
    var paused = false

    var isSharing: Bool { roomId != nil }
    var hasRooms: Bool { !rooms.isEmpty }
}

/// Everything about where your position is going, in one place. Ported from
/// android/app/…/location/SharingViewModel.kt.
///
/// The map, the settings screen and a room's own page all ask the same question, and a person who turned sharing on
/// in one of them expects to find it in the others. Three copies of this would eventually disagree, and the one that
/// disagreed quietly would be the one still transmitting.
@Observable
final class SharingViewModel {
    private(set) var state = SharingUiState()

    @ObservationIgnored private let location: LocationRepository
    @ObservationIgnored private let mesh: MeshRepository

    init(location: LocationRepository, mesh: MeshRepository) {
        self.location = location
        self.mesh = mesh
        recompute()
    }

    /// Follows the radio, its rooms and the recorded choice while the screen is up. Call from the view's `.task`.
    func observe() async {
        let channels = mesh.channels.subscribe()
        let myNodeNum = mesh.myNodeNum.subscribe()
        let deadline = location.sharingDeadline.subscribe()
        let connected = mesh.isConnected.subscribe()
        await withTaskGroup(of: Void.self) { group in
            group.addTask { for await _ in channels { await self.recompute() } }
            group.addTask { for await _ in myNodeNum { await self.recompute() } }
            group.addTask { for await _ in deadline { await self.recompute() } }
            group.addTask { for await _ in connected { await self.recompute() } }
        }
    }

    func share(roomId: Int32, choice: ShareDuration) {
        let location = location
        Task {
            LocationPermission.requestIfNeeded()
            do {
                try await location.shareWith(roomId: roomId, choice: choice)
            } catch {
                sharingLog.warning("could not start sharing")
            }
        }
    }

    /// Takes effect at once, whether or not a radio is connected: the phone is what sends.
    func stop() {
        location.stopSharing()
    }

    private func recompute() {
        let rooms = ChannelSlotManager.rooms(channels: mesh.channels.value).filter {
            PositionSharing.canShare(channel: $0)
        }
        let deadline = location.sharingDeadline.value
        // The phone does the sharing, so the choice it recorded is the truth; the radio only tells us whether that
        // room can be reached right now.
        let room = deadline.flatMap { chosen in rooms.first { $0.id == chosen.roomId } }
        state = SharingUiState(
            connected: mesh.myNodeNum.value != nil,
            rooms: rooms,
            roomId: deadline?.roomId,
            roomName: room?.displayName,
            choice: deadline?.choice ?? .default,
            endsAt: deadline?.endsAt,
            paused: deadline != nil && (!mesh.isConnected.value || room == nil)
        )
    }
}

/// Asks for When In Use location once, when something is about to need the phone's position. The system shows the
/// prompt only the first time; after a refusal, screens explain and offer Settings instead.
enum LocationPermission {
    private static let manager = CLLocationManager()

    static var isDenied: Bool {
        let status = manager.authorizationStatus
        return status == .denied || status == .restricted
    }

    static func requestIfNeeded() {
        if manager.authorizationStatus == .notDetermined {
            manager.requestWhenInUseAuthorization()
        }
    }
}
