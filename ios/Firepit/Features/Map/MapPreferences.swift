import Foundation
import MapLibre
import Observation
import os

/// Map behaviour the user controls. Ported from android/app/…/map/MapPreferences.kt, with TileClient.kt folded in.
///
/// Offline-only is applied to MapLibre itself rather than filtered in our code: making the SDK's own network session
/// refuse every request is the only way to be sure nothing fetches a tile, which is the whole point when data costs
/// money. Android does it with `MapLibre.setConnected(false)`; MapLibre iOS has no such switch, so `apply()` installs a
/// URL protocol on the SDK's session that fails every request while offline-only is on.
@Observable
final class MapPreferences {
    private(set) var offlineOnly: Bool

    @ObservationIgnored private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        offlineOnly = defaults.bool(forKey: Self.keyOfflineOnly)
    }

    /// Applied at startup, before any map exists, so the choice survives a restart, not just a toggle.
    func apply() {
        TileClient.install()
        let offline = offlineOnly
        OfflineOnlyURLProtocol.isOffline.withLock { $0 = offline }
    }

    func setOfflineOnly(_ enabled: Bool) {
        defaults.set(enabled, forKey: Self.keyOfflineOnly)
        offlineOnly = enabled
        OfflineOnlyURLProtocol.isOffline.withLock { $0 = enabled }
    }

    private static let keyOfflineOnly = "offline_only"
}

/// The HTTP client map tiles are fetched with. Ported from android/app/…/map/TileClient.kt.
///
/// MapLibre's own User-Agent names the app, its version and the phone's system version in every request, which lets
/// the tile server pick Firepit users out of its logs. This one says only that it is a map.
nonisolated enum TileClient {
    static let userAgent = "MapLibre iOS"

    private static let delegate = TileRequestDelegate()

    @MainActor static func install() {
        let configuration = URLSessionConfiguration.default
        configuration.protocolClasses = [OfflineOnlyURLProtocol.self] + (configuration.protocolClasses ?? [])
        // Tiles load in parallel, so a host gets more connections than the default.
        configuration.httpMaximumConnectionsPerHost = 20
        MLNNetworkConfiguration.sharedManager.sessionConfiguration = configuration
        MLNNetworkConfiguration.sharedManager.delegate = delegate
    }
}

/// Called by MapLibre on background threads for every request it makes.
private nonisolated final class TileRequestDelegate: NSObject, MLNNetworkConfigurationDelegate, Sendable {
    func willSend(_ request: NSMutableURLRequest) -> NSMutableURLRequest {
        request.setValue(TileClient.userAgent, forHTTPHeaderField: "User-Agent")
        return request
    }
}

/// Fails every request the map makes while offline-only is on, so MapLibre falls back to downloaded regions and its
/// cache. Registered only on MapLibre's own session.
nonisolated final class OfflineOnlyURLProtocol: URLProtocol {
    static let isOffline = OSAllocatedUnfairLock(initialState: false)

    override class func canInit(with request: URLRequest) -> Bool {
        isOffline.withLock { $0 }
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
    }

    override func stopLoading() {}
}
