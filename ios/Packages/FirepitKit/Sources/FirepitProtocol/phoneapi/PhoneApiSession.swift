import FirepitProtos
import Foundation
import SwiftProtobuf

public enum SessionState: Equatable, Sendable {
    case idle

    /// Handshake in flight: the radio is streaming its config.
    case downloading

    case ready(RadioSnapshot)
}

/// Radio reported `rebooted`, so the config we hold is stale and must be re-read.
private struct RadioRebooted: Error {}

/// The transport stopped delivering notifications. Treated as a lost link rather
/// than a clean end, so the caller reconnects with backoff instead of spinning.
public struct TransportClosed: Error, Equatable, Sendable {
    public init() {}
}

private struct DownloadTimedOut: Error, Equatable, Sendable {}

/// Drives the PhoneAPI conversation over a connected `RadioTransport`:
/// handshake, config download, then a steady-state drain loop.
///
/// Does not own the physical connection. If the transport throws, the exception
/// propagates so a connection manager can reconnect and start a fresh session.
public actor PhoneApiSession {
    private let transport: RadioTransport
    private let configIds: @Sendable () -> Int32
    private let downloadTimeout: Duration
    private let pollInterval: Duration
    private let sleep: @Sendable (Duration) async throws -> Void
    private let stateContinuation: AsyncStream<SessionState>.Continuation
    private let inboundContinuation: AsyncStream<FromRadio>.Continuation
    private let drainContinuation: AsyncStream<Void>.Continuation
    private let drainStream: AsyncStream<Void>

    public nonisolated let state: AsyncStream<SessionState>
    /// Steady-state traffic. Config-download messages also pass through here.
    public nonisolated let inbound: AsyncStream<FromRadio>

    public init(
        transport: RadioTransport,
        configIds: @escaping @Sendable () -> Int32 = {
            let value = Int32.random(in: Int32.min...Int32.max)
            if value == 0 {
                return 1
            }
            return value
        },
        downloadTimeout: Duration = .seconds(60),
        pollInterval: Duration = .milliseconds(50),
        sleep: @escaping @Sendable (Duration) async throws -> Void = { duration in try await Task.sleep(for: duration) }
    ) {
        self.transport = transport
        self.configIds = configIds
        self.downloadTimeout = downloadTimeout
        self.pollInterval = pollInterval
        self.sleep = sleep

        let statePair = AsyncStream.makeStream(of: SessionState.self, bufferingPolicy: .bufferingNewest(1))
        self.state = statePair.stream
        self.stateContinuation = statePair.continuation
        self.stateContinuation.yield(.idle)

        let inboundPair = AsyncStream.makeStream(of: FromRadio.self, bufferingPolicy: .bufferingNewest(256))
        self.inbound = inboundPair.stream
        self.inboundContinuation = inboundPair.continuation

        let drainPair = AsyncStream.makeStream(of: Void.self, bufferingPolicy: .bufferingNewest(1))
        self.drainStream = drainPair.stream
        self.drainContinuation = drainPair.continuation
    }

    deinit {
        stateContinuation.finish()
        inboundContinuation.finish()
        drainContinuation.finish()
    }

    /// Runs until cancelled or the transport fails. Re-runs the handshake
    /// whenever the radio reports it rebooted, which happens after most
    /// `set_config` writes.
    public func run() async throws {
        try await transport.enableNotifications()
        do {
            while !Task.isCancelled {
                let snapshot = try await handshake()
                setState(.ready(snapshot))
                if try await !pump() {
                    throw TransportClosed()
                }
            }
        } catch is CancellationError {
            setState(.idle)
            throw CancellationError()
        } catch {
            setState(.idle)
            throw error
        }
        setState(.idle)
    }

    public func send(_ message: ToRadio) async throws {
        try await transport.write(try message.serializedData())
        // Responses to our own writes would otherwise sit in the radio's queue
        // until some unrelated notification arrives.
        drainContinuation.yield(())
    }

    /// Lets the radio reset its PhoneAPI state immediately instead of timing out.
    public func sendDisconnect() async throws {
        var message = ToRadio()
        message.disconnect = true
        try await transport.write(try message.serializedData())
    }

    private func handshake() async throws -> RadioSnapshot {
        setState(.downloading)
        let configId = configIds()
        let accumulator = RadioSnapshotAccumulator()
        var request = ToRadio()
        request.wantConfigID = UInt32(bitPattern: configId)
        try await transport.write(try request.serializedData())

        try await withTimeout(downloadTimeout) {
            while true {
                let message = try await self.readNext()
                if message == nil {
                    // FromNum notifications are gated behind the radio's
                    // packet-sending state, so the download must be polled.
                    try await self.sleep(self.pollInterval)
                    continue
                }
                if await accumulator.accept(message: message!, expectedConfigId: configId) {
                    return
                }
            }
        }
        return await accumulator.build()
    }

    /// - Returns: true if the radio rebooted, false if the transport stopped emitting.
    private func pump() async throws -> Bool {
        do {
            return try await withThrowingTaskGroup(of: Bool.self) { group in
                group.addTask {
                    for await _ in self.transport.dataAvailable {
                        try await self.drain()
                    }
                    return false
                }
                group.addTask {
                    for await _ in self.drainStream {
                        try await self.drain()
                    }
                    return false
                }
                let result = try await group.next() ?? false
                group.cancelAll()
                return result
            }
        } catch is RadioRebooted {
            return true
        }
    }

    private func drain() async throws {
        while true {
            guard let message = try await readNext() else {
                return
            }
            if message.rebooted == true {
                throw RadioRebooted()
            }
        }
    }

    /// One decoded message, or nil only when the radio's queue is genuinely empty.
    private func readNext() async throws -> FromRadio? {
        while true {
            let frame = try await transport.read()
            if frame == nil || frame!.isEmpty {
                return nil
            }
            let message: FromRadio
            do {
                message = try FromRadio(serializedBytes: frame!)
            } catch {
                // Everything off the mesh is untrusted; skip the bad frame and
                // keep draining rather than mistaking it for an empty queue.
                continue
            }
            inboundContinuation.yield(message)
            return message
        }
    }

    private func setState(_ value: SessionState) {
        stateContinuation.yield(value)
    }

    private func withTimeout(_ timeout: Duration, operation: @escaping @Sendable () async throws -> Void) async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                try await operation()
            }
            group.addTask {
                try await self.sleep(timeout)
                throw DownloadTimedOut()
            }
            try await group.next()
            group.cancelAll()
        }
    }
}

/// Must be non-zero: the radio uses 0 to mean "no config download in progress".
internal func randomConfigId() -> Int32 {
    let value = Int32.random(in: Int32.min...Int32.max)
    if value == 0 {
        return 1
    }
    return value
}
