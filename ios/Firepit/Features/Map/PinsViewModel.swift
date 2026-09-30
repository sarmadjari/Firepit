import FirepitData
import FirepitModel
import Foundation
import Observation

struct PinsUiState: Equatable {
    var pins: [MapPin] = []
    var myNodeNum: Int32?
    var error: String?
}

/// List-screen state for dropped pins. Ported from android/app/…/map/PinsViewModel.kt.
@Observable
final class PinsViewModel {
    private(set) var uiState = PinsUiState()

    @ObservationIgnored private let waypoints: WaypointRepository
    @ObservationIgnored private let mesh: MeshRepository
    @ObservationIgnored private var error: String?
    @ObservationIgnored private var pins: [MapPin] = []
    @ObservationIgnored private var observeStarted = false

    init(waypoints: WaypointRepository, mesh: MeshRepository) {
        self.waypoints = waypoints
        self.mesh = mesh
        recompute()
    }

    convenience init(app: AppContainer) {
        self.init(waypoints: app.waypoints, mesh: app.mesh)
    }

    /// Follows pins while the screen is visible. Call from `.task`.
    func observe() async {
        guard !observeStarted else { return }
        observeStarted = true
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                for await next in self.waypoints.observePins() {
                    await MainActor.run {
                        self.pins = next
                        self.recompute()
                    }
                }
            }
            group.addTask {
                for await _ in self.mesh.myNodeNum.subscribe() {
                    await MainActor.run { self.recompute() }
                }
            }
        }
    }

    func rename(_ pin: MapPin, name: String) {
        run(fallback: String(localized: "Could not rename the pin")) {
            try await self.waypoints.rename(pin: pin, name: name)
        }
    }

    func remove(_ pin: MapPin) {
        run(fallback: String(localized: "Could not delete the pin")) {
            try await self.waypoints.remove(pin: pin)
        }
    }

    private func run(fallback: String, block: @escaping () async throws -> Void) {
        Task {
            error = nil
            recompute()
            do {
                try await block()
            } catch {
                self.error = error.localizedDescription.isEmpty ? fallback : error.localizedDescription
            }
            recompute()
        }
    }

    private func recompute() {
        uiState = PinsUiState(
            pins: PinsReducer.sortedPins(pins),
            myNodeNum: mesh.myNodeNum.value,
            error: error
        )
    }
}

nonisolated enum PinsReducer {
    static func sortedPins(_ pins: [MapPin]) -> [MapPin] {
        pins.sorted { lhs, rhs in
            let left = lhs.name.lowercased()
            let right = rhs.name.lowercased()
            return left == right ? lhs.id < rhs.id : left < right
        }
    }

    static func validPinName(_ name: String) -> String? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : String(trimmed.prefix(pinNameLimit))
    }
}

/// Proto limit on Waypoint.name.
nonisolated let pinNameLimit = 30
