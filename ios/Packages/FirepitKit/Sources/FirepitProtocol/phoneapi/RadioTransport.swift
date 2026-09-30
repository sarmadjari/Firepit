import Foundation

/// A connected link carrying the ToRadio/FromRadio protobuf stream.
///
/// Shaped after the BLE PhoneAPI, which is the primary transport: writes go to
/// the ToRadio characteristic, and reads pull one queued message at a time from
/// FromRadio until it comes back empty. TCP and serial can satisfy the same
/// contract by framing their stream.
///
/// Implementations are handed to `PhoneApiSession` already connected; they do
/// not own reconnection.
public protocol RadioTransport: Sendable {
    /// Emits the radio's FromNum counter whenever new data is waiting.
    ///
    /// Only fires once the radio reaches its packet-sending state, which is why
    /// the config download in `PhoneApiSession` polls instead of waiting here.
    var dataAvailable: AsyncStream<Int> { get }

    /// Subscribe to FromNum. Must happen before the handshake — the firmware
    /// gates these notifications behind STATE_SEND_PACKETS, and subscribing
    /// afterwards can miss the transition.
    func enableNotifications() async throws

    /// Write one encoded `ToRadio`.
    func write(_ frame: Data) async throws

    /// One encoded `FromRadio`, or nil once the radio's queue is drained.
    func read() async throws -> Data?
}
