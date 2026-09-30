import FirepitProtos
import Foundation

/// Spaces packets sent from the phone to satisfy the firmware's PhoneAPI rate
/// limits.
///
/// This has to live in the app, not be discovered from responses: when the
/// radio drops a rate-limited position, waypoint, alert or telemetry packet it
/// **still returns a normal `QueueStatus`**, so a violation is invisible. Text
/// is the exception — it NAKs with `RATE_LIMIT_EXCEEDED`.
///
/// Limits are per portnum, matching `PhoneAPI::handleToRadioPacket`.
public actor OutboundPacer {
    private let nowMillis: @Sendable () -> Int64
    private let sleepMillis: @Sendable (Int64) async -> Void
    private var lastSentAt: [Int32: Int64] = [:]

    public init(
        nowMillis: @escaping @Sendable () -> Int64,
        sleepMillis: @escaping @Sendable (Int64) async -> Void = { millis in
            if millis > 0 {
                try? await Task.sleep(for: .milliseconds(millis))
            }
        }
    ) {
        self.nowMillis = nowMillis
        self.sleepMillis = sleepMillis
    }

    /// Suspends until the next packet on `portNum` may be written.
    public func awaitSlot(portNum: PortNum) async {
        guard let minimumGap = minimumGapFor(portNum: portNum) else {
            return
        }

        let now = nowMillis()
        let previous = lastSentAt[portNumValue(portNum)]
        let readyAt = previous.map { $0 + minimumGap } ?? now
        // Reserve the slot while holding the lock so concurrent senders
        // queue up behind each other instead of all waiting for the same
        // instant and then firing together.
        let sendAt = max(now, readyAt)
        lastSentAt[portNumValue(portNum)] = sendAt
        let waitFor = sendAt - now

        if waitFor > 0 {
            await sleepMillis(waitFor)
        }
    }

    private func minimumGapFor(portNum: PortNum) -> Int64? {
        switch portNum {
        case .textMessageApp:
            return Self.textGap
        case .positionApp:
            return Self.broadcastableGap
        case .waypointApp:
            return Self.broadcastableGap
        case .alertApp:
            return Self.broadcastableGap
        case .telemetryApp:
            return Self.broadcastableGap
        case .tracerouteApp:
            return Self.tracerouteGap
        default:
            return nil
        }
    }

    private func portNumValue(_ portNum: PortNum) -> Int32 {
        Int32(portNum.rawValue)
    }

    private static let textGap: Int64 = 2_000
    private static let broadcastableGap: Int64 = 10_000
    private static let tracerouteGap: Int64 = 30_000
}
