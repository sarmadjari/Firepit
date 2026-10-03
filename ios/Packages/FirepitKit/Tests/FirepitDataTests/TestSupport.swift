import FirepitCrypto
import FirepitData
import FirepitModel
import FirepitProtos
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

/// A key for this hour, as a grant or a rotation would hand one over.
func hourKey(_ key: Data = RoomCipher.generateKey()) -> HourKey {
    HourKey(hour: RoomRatchet.hourOf(unixMillis: Int64(Date().timeIntervalSince1970 * 1_000)), key: key)
}

/// Opens what a phone sealed for a room, the way another member holding `key` would: moved on to the hour the
/// message names, then the sender's own key for that hour.
func openSealed(_ sealed: Meshchat_SealedMessage, key: HourKey, roomId: Int32, sender: Int32) -> Data? {
    guard let tag = SealedText.hourTagOf(sealed.ciphertext) else {
        return nil
    }
    let generation = sealed.generation == 0 ? RoomKeyStore.first : Int(sealed.generation)
    let hour = RoomRatchet.hourNear(tag: tag, near: key.hour)
    guard
        let hourKey = RoomRatchet.forward(
            key: key.key, roomId: roomId, generation: generation, from: key.hour, to: hour)
    else {
        return nil
    }
    let senderKey = RoomRatchet.senderKey(
        hourKey: hourKey, roomId: roomId, generation: generation, hour: hour, sender: sender)
    return SealedText.open(
        key: senderKey, payload: sealed.ciphertext,
        context: SealedText.contextOf(roomId: roomId, senderNodeNum: sender))
}
