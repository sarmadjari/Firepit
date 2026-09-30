import FirepitModel
import Foundation

/// What the phone's Bluetooth stack knows about our radios. Ported from android/core/transport/…/BluetoothPresence.kt.
///
/// Identifiers, matching `DiscoveredRadio.identifier`.
public struct BluetoothState: Sendable, Equatable {
    /// Holding a live connection, to this app or to another.
    public var connected: Set<String>
    /// Paired with the phone. Always empty on iOS, which offers no way to list paired Bluetooth LE devices; a radio
    /// paired but idle simply shows as not connected.
    public var paired: Set<String>

    public init(connected: Set<String> = [], paired: Set<String> = []) {
        self.connected = connected
        self.paired = paired
    }
}

/// Which radios the phone is connected to.
///
/// Deliberately wider than `RadioLink`: more than one radio can hold a connection, but a radio accepts a single
/// PhoneAPI
/// client. Reporting only our own link would tell someone carrying three live radios that two of them were off.
///
/// Polled, because iOS raises no event when another app connects to a Bluetooth LE device.
public final class BluetoothPresence: Sendable {
    /// Fast enough to feel immediate, slow enough to be free.
    public static let poll: Duration = .seconds(2)

    private let central: BluetoothCentral

    public init(central: BluetoothCentral) {
        self.central = central
    }

    /// The current state, then every change.
    public func state() -> AsyncStream<BluetoothState> {
        let central = central
        return AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let task = Task {
                var last: BluetoothState?
                while !Task.isCancelled {
                    let next = BluetoothState(connected: await central.connectedRadios())
                    if next != last {
                        continuation.yield(next)
                        last = next
                    }
                    try? await Task.sleep(for: Self.poll)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
