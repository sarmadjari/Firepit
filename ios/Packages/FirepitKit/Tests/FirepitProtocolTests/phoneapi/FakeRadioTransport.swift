import FirepitProtos
import Foundation

@testable import FirepitProtocol

/// In-memory radio that behaves like the BLE PhoneAPI: `read` hands back one
/// queued frame at a time and returns nil once drained, and writing
/// `want_config_id` queues a config download.
///
/// Holds raw frames rather than messages so malformed input can be injected.
final class FakeRadioTransport: RadioTransport, @unchecked Sendable {
    private actor Storage {
        var queue: [Data] = []
        var counter = 0
        var written: [ToRadio] = []
        var notificationsEnabled = false

        func setNotificationsEnabled() {
            notificationsEnabled = true
        }

        func appendWritten(_ message: ToRadio, frames: [Data]) {
            written.append(message)
            queue.append(contentsOf: frames)
        }

        func read() -> Data? {
            if queue.isEmpty {
                return nil
            }
            return queue.removeFirst()
        }

        func deliver(_ frames: [Data]) -> Int {
            queue.append(contentsOf: frames)
            counter += 1
            return counter
        }

        func deliverSilently(_ frame: Data) {
            queue.append(frame)
        }
    }

    private let configDownload: @Sendable (Int32) -> [FromRadio]
    private let storage = Storage()
    private let dataContinuation: AsyncStream<Int>.Continuation

    let dataAvailable: AsyncStream<Int>

    init(configDownload: @escaping @Sendable (Int32) -> [FromRadio]) {
        self.configDownload = configDownload
        let pair = AsyncStream.makeStream(of: Int.self, bufferingPolicy: .bufferingNewest(64))
        self.dataAvailable = pair.stream
        self.dataContinuation = pair.continuation
    }

    deinit {
        dataContinuation.finish()
    }

    var written: [ToRadio] {
        get async {
            await storage.written
        }
    }

    var notificationsEnabled: Bool {
        get async {
            await storage.notificationsEnabled
        }
    }

    func enableNotifications() async throws {
        await storage.setNotificationsEnabled()
    }

    func write(_ frame: Data) async throws {
        let message = try ToRadio(serializedBytes: frame)
        var frames: [Data] = []
        if case .wantConfigID(let configId)? = message.payloadVariant {
            frames = configDownload(Int32(bitPattern: configId)).map { try! $0.serializedData() }
        }
        await storage.appendWritten(message, frames: frames)
    }

    func read() async throws -> Data? {
        await storage.read()
    }

    /// Pushes unsolicited frames and rings FromNum, as the radio does for mesh traffic.
    func deliver(_ messages: FromRadio...) async {
        let frames = messages.map { try! $0.serializedData() }
        let value = await storage.deliver(frames)
        dataContinuation.yield(value)
    }

    /// Queues a frame without ringing FromNum, so only a post-write drain can find it.
    func deliverSilently(_ message: FromRadio) async {
        await storage.deliverSilently(try! message.serializedData())
    }

    /// Undecodable bytes ahead of a valid frame: one bad frame must be skipped, not fatal.
    func deliverGarbageThen(_ message: FromRadio) async {
        let frames = [Data([0x0A, 0x7F, 0xFF]), try! message.serializedData()]
        let value = await storage.deliver(frames)
        dataContinuation.yield(value)
    }
}
