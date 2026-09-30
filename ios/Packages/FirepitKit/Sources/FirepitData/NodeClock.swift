import FirepitModel
import FirepitTransport
import Foundation
import os

/// A radio whose clock disagrees with the phone, and by how much.
public struct ClockOffer: Sendable, Equatable {
    public var drift: Duration
    public var behind: Bool

    public init(drift: Duration, behind: Bool) {
        self.drift = drift
        self.behind = behind
    }
}

/// Offers to give the radio the phone's time.
///
/// A radio with no GPS and no battery-backed clock stamps every message it hands
/// over with whatever it believes the time is, which reaches the app as a
/// conversation filed under the wrong day. Writing someone's hardware is offered
/// rather than done quietly.
///
/// The firmware records this as Net quality, which the protos note is below GPS,
/// so a radio with a fix keeps its own better time. It costs no airtime: admin
/// packets are addressed to our own node and never reach the air.
public final class NodeClock: Sendable {
    public let link: any RadioLinking
    public let mesh: MeshRepository
    public let admin: NodeAdminClient
    public let offer = CurrentValue<ClockOffer?>(nil)
    private let declined = CurrentValue(false)
    private let tasks = Mutex<[Task<Void, Never>]>([])
    private let log = Logger(subsystem: "com.getfirepit.app", category: "NodeClock")

    public init(link: any RadioLinking, mesh: MeshRepository, admin: NodeAdminClient) {
        self.link = link
        self.mesh = mesh
        self.admin = admin
    }

    deinit {
        tasks.withLock { jobs in
            for job in jobs {
                job.cancel()
            }
            jobs.removeAll()
        }
    }

    public func start() {
        tasks.withLock { jobs in
            guard jobs.isEmpty else {
                return
            }
            let states = link.state.subscribe()
            let skews = mesh.clockSkewMillis.subscribe()
            let refusals = declined.subscribe()
            jobs.append(
                Task { [weak self] in
                    for await state in states {
                        // A reconnection, or a different radio, is worth asking about again.
                        if case .ready = state {
                        } else {
                            self?.declined.set(false)
                        }
                        self?.recompute()
                    }
                })
            jobs.append(
                Task { [weak self] in
                    for await _ in skews {
                        self?.recompute()
                    }
                })
            jobs.append(
                Task { [weak self] in
                    for await _ in refusals {
                        self?.recompute()
                    }
                })
        }
    }

    /// The offer follows the link, the radio's measured drift and whether it was declined, as Kotlin's `combine`
    /// of the three does: any of them changing can make it appear or go.
    private func recompute() {
        let connected: Bool
        if case .ready = link.state.value {
            connected = true
        } else {
            connected = false
        }
        guard connected, !declined.value, let skew = mesh.clockSkewMillis.value else {
            offer.set(nil)
            return
        }
        let absolute = abs(skew)
        offer.set(absolute >= Self.toleranceMillis ? ClockOffer(drift: .milliseconds(absolute), behind: skew < 0) : nil)
    }

    /// Accepted: write the phone's time to the radio.
    public func sync() async {
        let now = Int(Date().timeIntervalSince1970.rounded(.down))
        do {
            try await admin.setTime(epochSeconds: now)
            log.info("radio clock set")
        } catch {
            log.warning("could not set the radio clock")
        }
        // Packets already in flight still carry the old stamp, and asking again
        // about a clock we have just set would look like it had not worked.
        declined.set(true)
        offer.set(nil)
    }

    /// Declined: leave the radio alone until it connects again.
    public func dismiss() {
        declined.set(true)
    }

    /// Below this the difference cannot put a message under the wrong day.
    private static let toleranceMillis: Int64 = 2 * 60 * 1_000
}
