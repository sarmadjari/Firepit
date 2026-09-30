import FirepitModel
import Foundation

/// Scans for radios advertising the Meshtastic service. Ported from android/core/transport/…/RadioScanner.kt.
///
/// Filtering by service UUID is natively supported on every platform, which lets the system optimise the scan and
/// keeps other people's peripherals out of the results entirely. Scanning runs while anyone is iterating a stream and
/// stops when the last one stops.
public final class RadioScanner: Sendable {
    private let central: BluetoothCentral

    public init(central: BluetoothCentral) {
        self.central = central
    }

    /// Raw stream; the same radio is re-emitted every time it advertises.
    public func scan() -> AsyncStream<DiscoveredRadio> {
        forward { await $0.scan() }
    }

    /// Deduplicated, strongest first — what a device picker should show.
    public func scanDistinct() -> AsyncStream<[DiscoveredRadio]> {
        forward { await $0.scanDistinct() }
    }

    /// Relays a stream the central hands out from inside its actor, ending the central's scan when the reader stops.
    private func forward<Element: Sendable>(
        _ open: @escaping @Sendable (BluetoothCentral) async -> AsyncStream<Element>
    ) -> AsyncStream<Element> {
        let central = central
        return AsyncStream { continuation in
            let task = Task {
                for await element in await open(central) {
                    continuation.yield(element)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
