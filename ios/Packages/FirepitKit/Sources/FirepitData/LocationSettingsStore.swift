import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation

public struct LocationSettings: Sendable, Equatable {
    public var rateSeconds: Int
    public var whenMoved: Bool
    public var smartDistanceMetres: Int
    public var smartIntervalSeconds: Int
}

public final class LocationSettingsStore: Sendable {
    private let defaults: Mutex<UserDefaults>
    public let settings: CurrentValue<LocationSettings>

    public init(defaults: UserDefaults = .standard) {
        self.defaults = Mutex(defaults)
        settings = CurrentValue(Self.read(from: defaults))
    }

    public func initializeFromRadio(_ position: Config.PositionConfig?) {
        if defaults.withLock({ $0.bool(forKey: Self.initializedKey) }) { return }
        guard let position, let rate = BeaconRate.of(seconds: Int(position.positionBroadcastSecs)) else { return }
        defaults.withLock { defaults in
            defaults.set(true, forKey: Self.initializedKey)
            defaults.set(rate.seconds, forKey: Self.rateKey)
            defaults.set(position.positionBroadcastSmartEnabled, forKey: Self.whenMovedKey)
            defaults.set(Int(position.broadcastSmartMinimumDistance) > 0 ? Int(position.broadcastSmartMinimumDistance) : 100, forKey: Self.distanceKey)
            defaults.set(Int(position.broadcastSmartMinimumIntervalSecs) > 0 ? Int(position.broadcastSmartMinimumIntervalSecs) : 30, forKey: Self.intervalKey)
        }
        settings.set(defaults.withLock { Self.read(from: $0) })
    }

    public func setRate(_ rate: BeaconRate) {
        defaults.withLock {
            $0.set(true, forKey: Self.initializedKey)
            $0.set(rate.seconds, forKey: Self.rateKey)
        }
        settings.set(defaults.withLock { Self.read(from: $0) })
    }

    public func setWhenMoved(_ enabled: Bool) {
        defaults.withLock {
            $0.set(true, forKey: Self.initializedKey)
            $0.set(enabled, forKey: Self.whenMovedKey)
        }
        settings.set(defaults.withLock { Self.read(from: $0) })
    }

    private static func read(from defaults: UserDefaults) -> LocationSettings {
        LocationSettings(
            rateSeconds: defaults.object(forKey: rateKey) == nil ? BeaconRate.firmwareDefaultSeconds : defaults.integer(forKey: rateKey),
            whenMoved: defaults.bool(forKey: whenMovedKey),
            smartDistanceMetres: defaults.object(forKey: distanceKey) == nil ? 100 : defaults.integer(forKey: distanceKey),
            smartIntervalSeconds: defaults.object(forKey: intervalKey) == nil ? 30 : defaults.integer(forKey: intervalKey)
        )
    }

    private static let initializedKey = "location.initialized"
    private static let rateKey = "location.rate_seconds"
    private static let whenMovedKey = "location.when_moved"
    private static let distanceKey = "location.smart_distance"
    private static let intervalKey = "location.smart_interval"
}
