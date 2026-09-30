import CoreLocation
import FirepitData
import FirepitModel
import Foundation
import Observation

/// What the offline-areas screen shows. Ported from android/app/…/map/OfflineMapsViewModel.kt.
struct OfflineMapsUiState: Equatable {
    var areas: [OfflineArea] = []
    /// 0…1 while a download runs, nil when idle.
    var progress: Double?
    var error: String?
    var offlineOnly = false
    /// Set when an area has just finished downloading while the map still goes online: the moment to offer never
    /// fetching tiles again, which is what keeps the tile server from learning where the group looks.
    var suggestOfflineOnly = false
}

@Observable
final class OfflineMapsViewModel {
    private(set) var uiState = OfflineMapsUiState()

    /// The last fix this phone recorded for itself, or nil before there is one.
    ///
    /// Read from the node table rather than the GPS: this screen is about choosing ground, and the last known
    /// position is close enough to point the camera at without waking the receiver.
    private(set) var myPosition: CLLocationCoordinate2D?

    @ObservationIgnored private let repository: OfflineMapRepository
    @ObservationIgnored private let mapPreferences: MapPreferences
    @ObservationIgnored private let mesh: MeshRepository
    @ObservationIgnored private var job: Task<Void, Never>?

    init(repository: OfflineMapRepository, mapPreferences: MapPreferences, mesh: MeshRepository) {
        self.repository = repository
        self.mapPreferences = mapPreferences
        self.mesh = mesh
        uiState.offlineOnly = mapPreferences.offlineOnly
        refresh()
    }

    /// Follows our own node's position while the screen is up. Call from the view's `.task`.
    func observe() async {
        for await nodes in mesh.observeNodes() {
            let me = mesh.myNodeNum.value
            myPosition = nodes.first { $0.nodeNum == me && $0.hasPosition }.flatMap { node in
                guard let latitude = node.latitude, let longitude = node.longitude else { return nil }
                return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
            }
        }
    }

    func setOfflineOnly(_ enabled: Bool) {
        mapPreferences.setOfflineOnly(enabled)
        uiState.offlineOnly = enabled
    }

    func download(name: String, bounds: GeoBox, styleUrl: String) {
        job = Task {
            uiState.progress = 0
            uiState.error = nil
            var failure: String?
            do {
                try await repository.download(name: name, bounds: bounds, styleUrl: styleUrl) { progress in
                    self.uiState.progress = progress
                }
            } catch {
                failure = error.localizedDescription
            }
            uiState.progress = nil
            uiState.error = failure
            uiState.suggestOfflineOnly = failure == nil && !uiState.offlineOnly
            refresh()
        }
    }

    func dismissOfflineSuggestion() {
        uiState.suggestOfflineOnly = false
    }

    func delete(_ area: OfflineArea) {
        Task {
            do {
                try await repository.delete(area)
            } catch {
                uiState.error = error.localizedDescription
            }
            refresh()
        }
    }

    func update(_ area: OfflineArea) {
        job = Task {
            uiState.progress = 0
            uiState.error = nil
            var failure: String?
            do {
                try await repository.update(area) { progress in
                    self.uiState.progress = progress
                }
            } catch {
                failure = error.localizedDescription
            }
            uiState.progress = nil
            uiState.error = failure
            refresh()
        }
    }

    private func refresh() {
        Task {
            uiState.areas = await repository.areas()
        }
    }
}
