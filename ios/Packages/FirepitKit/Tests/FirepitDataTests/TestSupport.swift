import FirepitData
import FirepitModel
import Foundation
import GRDB

func makeDatabase() throws -> DatabaseQueue {
    try FirepitDatabase.inMemory()
}

func firstValue<T: Sendable>(_ stream: AsyncStream<T>) async throws -> T {
    var iterator = stream.makeAsyncIterator()
    guard let value = await iterator.next() else {
        throw TestSupportError.noValue
    }
    return value
}

enum TestSupportError: Error {
    case noValue
}

func sampleMessage(
    id: Int32,
    channel: Int = 1,
    from: Int32 = 10,
    to: Int32 = broadcastNodeNum,
    sentAt: Int64 = 1,
    status: MessageStatus = .received,
    outgoing: Bool = false,
    roomId: Int32 = 0
) -> ChatMessage {
    ChatMessage(
        id: id,
        channel: channel,
        fromNodeNum: from,
        toNodeNum: to,
        text: "message \(id)",
        sentAt: sentAt,
        status: status,
        isOutgoing: outgoing,
        roomId: roomId
    )
}

/// Polls `condition` until it holds or `timeout` passes, for effects that land on another task. A fixed sleep before
/// an assertion is a guess about scheduling, and loses whenever the machine is busy.
func eventually(
    timeout: Duration = .seconds(3),
    _ condition: () async throws -> Bool
) async rethrows -> Bool {
    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: timeout)
    while clock.now < deadline {
        if try await condition() {
            return true
        }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return try await condition()
}
