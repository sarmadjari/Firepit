import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation
import os

/// Where the link to the radio stands.
public enum LinkState: Sendable, Equatable {
    case disconnected
    case connecting(attempt: Int)
    /// Connected; the radio is streaming its config.
    case downloading
    case ready(RadioSnapshot)
    /// Link dropped; a retry is scheduled.
    case reconnecting(attempt: Int, cause: String)
    /// The radio is older than Firepit supports, and was hung up on.
    ///
    /// Terminal: retrying would only reach the same firmware again, and half of what this app promises would quietly
    /// not hold on it.
    case unsupported(FirmwareVersion?)
}

public enum RadioLinkError: Error, Equatable, Sendable {
    case notConnected
}

/// Raised once the config download reveals firmware below `RadioCapabilities.minimumFirmware`.
private struct FirmwareTooOld: Error {
    let version: FirmwareVersion?
}

/// What the repositories need from a radio link: where it stands, what arrives, and a way to send.
///
/// `RadioLink` is the only production implementation; the protocol exists so repositories can be exercised against a
/// scripted link in tests, which Android's concrete class does not allow.
public protocol RadioLinking: Sendable {
    /// Where the link stands, followable by every screen.
    var state: CurrentValue<LinkState> { get }
    /// Steady-state traffic from whichever session is currently live.
    var inbound: Broadcast<FromRadio> { get }
    /// Queues one message for the radio; throws `RadioLinkError.notConnected` when no session is live.
    func send(_ message: ToRadio) async throws
}

/// Keeps one radio connected: connect, run the PhoneAPI session, and reconnect with backoff when the link drops.
///
/// One instance per physical radio. A radio accepts only one PhoneAPI client at a time, so two links to the same node
/// will fight. Port of Android's `RadioLink`; iOS keeps the connection alive in the background through the
/// `bluetooth-central` mode and state restoration instead of a foreground service.
public actor RadioLink: RadioLinking {
    /// Where the link stands, followable by every screen.
    public nonisolated let state = CurrentValue(LinkState.disconnected)

    /// Steady-state traffic from whichever session is currently live. Config-download messages pass through too.
    public nonisolated let inbound = Broadcast<FromRadio>()

    private let central: BluetoothCentral
    private let log = Logger(subsystem: "com.getfirepit.app", category: "FirepitLink")
    private var session: PhoneApiSession?
    private var job: Task<Void, Never>?

    /// The radio this link is keeping, if any.
    public private(set) var radio: String?

    public init(central: BluetoothCentral) {
        self.central = central
    }

    /// Two sessions must never read the same transport: each `read` consumes one queued FromRadio, so concurrent
    /// readers split the config download between them and both end up with a partial snapshot. The previous attempt is
    /// therefore fully joined before a new one starts.
    public func connect(_ discovered: DiscoveredRadio) {
        let radio = discovered.identifier
        let previous = job
        let previousRadio = self.radio
        self.radio = radio
        job = Task {
            if let previous {
                if let previousRadio { await central.disconnect(previousRadio) }
                previous.cancel()
                await previous.value
            }
            await maintainConnection(radio)
        }
    }

    /// Announces the disconnect so the radio frees its PhoneAPI slot at once instead of waiting for a timeout — the
    /// next connection would otherwise be refused.
    public func disconnect() async {
        try? await session?.sendDisconnect()
        let radio = self.radio
        if let radio { await central.disconnect(radio) }
        job?.cancel()
        await job?.value
        job = nil
        session = nil
        self.radio = nil
        setState(.disconnected)
    }

    public func send(_ message: ToRadio) async throws {
        guard let session else { throw RadioLinkError.notConnected }
        try await session.send(message)
    }

    private func setState(_ next: LinkState) {
        log.info("link: \(Self.describe(next), privacy: .public)")
        state.set(next)
    }

    private func maintainConnection(_ radio: String) async {
        var attempt = 0
        while !Task.isCancelled {
            setState(.connecting(attempt: attempt))
            do {
                let transport = try await central.connect(to: radio)
                attempt = 0
                let session = PhoneApiSession(transport: transport)
                self.session = session
                try await run(session)
            } catch is CancellationError {
                break
            } catch let refused as FirmwareTooOld {
                // Hang up politely so the radio frees its PhoneAPI slot for whatever app the owner uses instead.
                try? await session?.sendDisconnect()
                session = nil
                await central.disconnect(radio)
                log.warning("refusing radio: firmware \(refused.version?.description ?? "unknown", privacy: .public)")
                setState(.unsupported(refused.version))
                return
            } catch {
                if Task.isCancelled { break }
                attempt += 1
                setState(.reconnecting(attempt: attempt, cause: String(describing: error)))
            }
            session = nil
            await central.disconnect(radio)
            if Task.isCancelled { break }
            try? await Task.sleep(for: Self.backoff(for: attempt))
        }
    }

    /// Runs one session until it ends, forwarding its traffic and state. The firmware version only arrives with the
    /// config, so the refusal cannot happen any earlier than the first `ready`.
    private func run(_ session: PhoneApiSession) async throws {
        let inbound = self.inbound
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                for await message in session.inbound {
                    inbound.send(message)
                }
            }
            group.addTask {
                for await sessionState in session.state {
                    try await self.relay(sessionState)
                }
            }
            group.addTask {
                try await session.run()
            }
            defer { group.cancelAll() }
            try await group.next()
            // A stream ending or `run` returning means the link is gone either way.
            throw TransportClosed()
        }
    }

    /// Held back until the firmware is known to be good, so the app never briefly reports a radio it is about to hang
    /// up on.
    private func relay(_ sessionState: SessionState) throws {
        switch sessionState {
        case .idle:
            return
        case .downloading:
            setState(.downloading)
        case .ready(let snapshot):
            let capabilities = snapshot.capabilities
            guard capabilities.isSupported else { throw FirmwareTooOld(version: capabilities.firmwareVersion) }
            setState(.ready(snapshot))
        }
    }

    static func backoff(for attempt: Int) -> Duration {
        guard attempt > 0 else { return .zero }
        let doubled = Duration.seconds(1) * (1 << min(attempt - 1, 5))
        return min(doubled, .seconds(30))
    }

    private static func describe(_ state: LinkState) -> String {
        switch state {
        case .disconnected: return "disconnected"
        case .connecting(let attempt): return "connecting attempt=\(attempt)"
        case .downloading: return "downloading"
        case .ready(let snapshot):
            let capabilities = snapshot.capabilities
            return "ready node=\(snapshot.myNodeNum.map(String.init) ?? "?") channels=\(snapshot.channels.count) "
                + "firmware=\(capabilities.firmwareVersion?.description ?? "unknown") "
                + "pki=\(capabilities.supportsPki) signing=\(capabilities.supportsSigning)"
        case .reconnecting(let attempt, let cause): return "reconnecting attempt=\(attempt) cause=\(cause)"
        case .unsupported(let version): return "unsupported firmware=\(version?.description ?? "unknown")"
        }
    }
}
