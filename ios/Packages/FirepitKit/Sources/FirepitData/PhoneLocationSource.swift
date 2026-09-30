import CoreLocation
import Foundation
import os

private let gpsLog = Logger(subsystem: "com.getfirepit.app", category: "FirepitGps")

/// The phone's own GPS. Ported from android/core/data/…/PhoneLocationSource.kt.
///
/// Android asks the platform LocationManager rather than Play Services, so the app works on de-Googled phones. iOS
/// has one location service; this uses its live updates, which need no delegate and hold no shared state.
///
/// Nothing here starts until something iterates `updates`, so no fix is ever requested while location sharing is off.
/// Permission is requested by the screens that need it, not here: without it the stream simply produces nothing.
public final class PhoneLocationSource: PhoneLocationProviding {
    public static let defaultInterval: Duration = .seconds(30)

    public init() {}

    /// Fixes at most once per `interval`, the first as soon as there is one — so the map has something to show
    /// immediately instead of an empty screen while the next update arrives.
    public func updates(interval: Duration = defaultInterval) -> AsyncStream<CLLocation> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let task = Task {
                gpsLog.info("requesting phone location updates")
                let clock = ContinuousClock()
                var lastSent: ContinuousClock.Instant?
                do {
                    for try await update in CLLocationUpdate.liveUpdates() {
                        guard let location = update.location else {
                            continue
                        }
                        let now = clock.now
                        if let lastSent, now - lastSent < interval {
                            continue
                        }
                        lastSent = now
                        continuation.yield(location)
                    }
                } catch {
                    gpsLog.warning("phone location updates ended")
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in
                task.cancel()
            }
        }
    }

    public func hasAnyProvider() -> Bool {
        CLLocationManager.locationServicesEnabled()
    }
}
