import CoreLocation
import Foundation
import MapLibre
import os

private nonisolated let offlineLog = Logger(subsystem: "com.getfirepit.app", category: "FirepitOffline")

/// A downloaded map region. Ported from android/app/…/map/OfflineMapRepository.kt.
nonisolated struct OfflineArea: Identifiable, Hashable, Sendable {
    let id: Int64
    let name: String
    /// Epoch millis of the last completed download, or 0 when unknown.
    let downloadedAt: Int64
    let tiles: Int64
    /// Nil only if MapLibre reports a region shape we do not draw.
    let bounds: GeoBox?

    var sizeLabel: String { TileEstimate.describe(tiles) }
}

/// Why a download or deletion did not happen, in words the screen can show.
nonisolated enum OfflineMapError: LocalizedError, Equatable {
    case failed(String)
    case tooLarge
    case noLongerSaved

    var errorDescription: String? {
        switch self {
        case .failed(let message): message
        case .tooLarge: String(localized: "That area is too large. Zoom in and try again.")
        case .noLongerSaved: String(localized: "That area is no longer saved")
        }
    }
}

/// Downloaded map areas, so the map still works with no network.
///
/// This is the point of choosing MapLibre: the platform map SDKs cannot pre-cache an arbitrary region, which makes
/// them useless exactly when Firepit matters. Downloads are capped because OpenFreeMap is donation-funded and one
/// careless region can pull gigabytes. MapLibre wants its offline storage driven from the main thread, which is where
/// this lives.
@MainActor
final class OfflineMapRepository {
    /// Street level only. Going deeper multiplies tiles by four per level.
    static let minZoom = 8.0
    static let maxZoom = 15.0

    /// Enough for a day of browsing a trip's area, not a record of every trip.
    static let browsedTileCap = 50 * 1024 * 1024

    private static let keyName = "name"
    private static let keyDownloadedAt = "downloadedAt"

    private var storage: MLNOfflineStorage { MLNOfflineStorage.shared }

    func areas() async -> [OfflineArea] {
        await loadedPacks().map(toArea)
    }

    /// Starts a download and reports progress (0…1) until it finishes. Cancelling the calling task pauses it.
    func download(
        name: String,
        bounds: GeoBox,
        styleUrl: String,
        minZoom: Double = OfflineMapRepository.minZoom,
        maxZoom: Double = OfflineMapRepository.maxZoom,
        onProgress: @escaping (Double) -> Void
    ) async throws {
        let region = MLNTilePyramidOfflineRegion(
            styleURL: URL(string: styleUrl),
            bounds: MLNCoordinateBounds(
                sw: CLLocationCoordinate2D(latitude: bounds.south, longitude: bounds.west),
                ne: CLLocationCoordinate2D(latitude: bounds.north, longitude: bounds.east)
            ),
            fromZoomLevel: minZoom,
            toZoomLevel: maxZoom
        )
        let pack: MLNOfflinePack
        do {
            pack = try await storage.addPack(for: region, withContext: metadataFor(name: name))
        } catch {
            throw OfflineMapError.failed(error.localizedDescription)
        }
        try await run(PackHandle(pack), onProgress: onProgress)
    }

    func delete(_ area: OfflineArea) async throws {
        guard let pack = await loadedPacks().first(where: { id(of: $0) == area.id }) else { return }
        do {
            try await storage.removePack(pack)
        } catch {
            throw OfflineMapError.failed(error.localizedDescription)
        }
    }

    /// Re-runs an existing area's download so its tiles pick up map changes.
    ///
    /// MapLibre only fetches what is missing or stale, so this is far cheaper than deleting and downloading again.
    func update(_ area: OfflineArea, onProgress: @escaping (Double) -> Void) async throws {
        guard let pack = await loadedPacks().first(where: { id(of: $0) == area.id }) else {
            throw OfflineMapError.noLongerSaved
        }
        try await run(PackHandle(pack), onProgress: onProgress)
        do {
            try await pack.setContext(metadataFor(name: area.name))
        } catch {
            offlineLog.warning("could not stamp update time")
        }
    }

    /// Empties the tiles cached while browsing, and caps how many build up again.
    ///
    /// Downloaded areas are kept: somebody chose those. Browsed tiles are a record of where the map was looked at —
    /// usually where the group was — so they are not kept past the retention window.
    func forgetBrowsedTiles() async -> Bool {
        let capped = await fileSource { completion in
            MLNOfflineStorage.shared.setMaximumAmbientCacheSize(
                UInt(Self.browsedTileCap), withCompletionHandler: completion)
        }
        let cleared = await fileSource { completion in
            MLNOfflineStorage.shared.clearAmbientCache(completionHandler: completion)
        }
        return capped && cleared
    }

    // MARK: Private

    /// Resumes a pack and waits for it to complete, fail or hit the tile limit. Cancelling the task pauses the pack.
    private func run(_ handle: PackHandle, onProgress: @escaping (Double) -> Void) async throws {
        let watcher = PackWatcher(handle: handle, onProgress: onProgress)
        defer { watcher.stopObserving() }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                watcher.start(continuation)
            }
        } onCancel: {
            Task { @MainActor in
                watcher.cancel()
            }
        }
    }

    /// The packs, once MapLibre has read them from disk: the list is empty until then, and the offline screen can
    /// open before any map has.
    private func loadedPacks() async -> [MLNOfflinePack] {
        if let packs = storage.packs {
            return packs
        }
        for _ in 0..<50 {
            try? await Task.sleep(for: .milliseconds(100))
            if let packs = storage.packs {
                return packs
            }
        }
        offlineLog.warning("offline packs did not load")
        return []
    }

    private func toArea(_ pack: MLNOfflinePack) -> OfflineArea {
        let metadata = (try? JSONSerialization.jsonObject(with: pack.context)) as? [String: Any]
        let region = pack.region as? MLNTilePyramidOfflineRegion
        let bounds = region.map { region in
            GeoBox(
                north: region.bounds.ne.latitude, south: region.bounds.sw.latitude,
                east: region.bounds.ne.longitude, west: region.bounds.sw.longitude)
        }
        let name = (metadata?[Self.keyName] as? String)?.trimmingCharacters(in: .whitespaces)
        return OfflineArea(
            id: id(of: pack),
            name: name?.isEmpty == false ? name! : String(localized: "Saved area"),
            downloadedAt: (metadata?[Self.keyDownloadedAt] as? NSNumber)?.int64Value ?? 0,
            tiles: bounds.map { box in
                TileEstimate.tileCount(
                    north: box.north, south: box.south, east: box.east, west: box.west,
                    minZoom: Int(Self.minZoom), maxZoom: Int(Self.maxZoom))
            } ?? 0,
            bounds: bounds
        )
    }

    private func id(of pack: MLNOfflinePack) -> Int64 {
        pack.regionId?.int64Value ?? Int64(pack.hash)
    }

    private func metadataFor(name: String) -> Data {
        let metadata: [String: Any] = [Self.keyName: name, Self.keyDownloadedAt: currentMillis()]
        return (try? JSONSerialization.data(withJSONObject: metadata)) ?? Data()
    }

    private func currentMillis() -> Int64 {
        Int64(Date.now.timeIntervalSince1970 * 1000)
    }

    private func fileSource(_ call: (@escaping @Sendable (Error?) -> Void) -> Void) async -> Bool {
        await withCheckedContinuation { continuation in
            call { error in
                if error != nil {
                    offlineLog.warning("map cache operation failed")
                }
                continuation.resume(returning: error == nil)
            }
        }
    }
}

/// Carries a pack across the continuations MapLibre's completion handlers resume. Main-actor bound, like the pack.
@MainActor
private final class PackHandle {
    let pack: MLNOfflinePack

    init(_ pack: MLNOfflinePack) {
        self.pack = pack
    }
}

/// Follows one pack's download through MapLibre's notifications, which it posts on the main queue.
@MainActor
private final class PackWatcher {
    private let handle: PackHandle
    private let onProgress: (Double) -> Void
    private var continuation: CheckedContinuation<Void, any Error>?
    private var observers: [any NSObjectProtocol] = []

    init(handle: PackHandle, onProgress: @escaping (Double) -> Void) {
        self.handle = handle
        self.onProgress = onProgress
    }

    func start(_ continuation: CheckedContinuation<Void, any Error>) {
        self.continuation = continuation
        let center = NotificationCenter.default
        let pack = handle.pack
        observers = [
            center.addObserver(forName: .MLNOfflinePackProgressChanged, object: pack, queue: .main) { [self] _ in
                MainActor.assumeIsolated { progressChanged() }
            },
            center.addObserver(forName: .MLNOfflinePackError, object: pack, queue: .main) { [self] note in
                let message = (note.userInfo?[MLNOfflinePackUserInfoKey.error] as? NSError)?.localizedDescription
                MainActor.assumeIsolated { finish(.failure(OfflineMapError.failed(message ?? ""))) }
            },
            center.addObserver(forName: .MLNOfflinePackMaximumMapboxTilesReached, object: pack, queue: .main) {
                [self] _ in
                MainActor.assumeIsolated {
                    handle.pack.suspend()
                    finish(.failure(OfflineMapError.tooLarge))
                }
            },
        ]
        pack.resume()
    }

    func cancel() {
        handle.pack.suspend()
        finish(.failure(CancellationError()))
    }

    func stopObserving() {
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
    }

    private func progressChanged() {
        let pack = handle.pack
        let status = pack.progress
        let required = max(status.countOfResourcesExpected, 1)
        onProgress(min(max(Double(status.countOfResourcesCompleted) / Double(required), 0), 1))
        if pack.state == .complete {
            pack.suspend()
            finish(.success(()))
        }
    }

    private func finish(_ result: Result<Void, any Error>) {
        continuation?.resume(with: result)
        continuation = nil
    }
}
