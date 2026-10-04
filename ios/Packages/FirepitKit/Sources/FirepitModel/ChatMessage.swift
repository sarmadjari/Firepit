import Foundation

/// Broadcast destination; the same value the firmware uses for "everyone" (0xFFFFFFFF as a signed 32-bit int).
public let broadcastNodeNum: Int32 = -1
/// No real node has zero, so it marks a local notice line.
public let noticeNodeNum: Int32 = 0

/// Milliseconds since the Unix epoch, the unit every Firepit timestamp uses (Android's `System.currentTimeMillis()`).
public func currentEpochMillis() -> Int64 {
    Int64((Date().timeIntervalSince1970 * 1000).rounded(.down))
}

/// One chat message, incoming or outgoing.
///
/// - `id`: the mesh packet id. Non-zero and used to correlate acknowledgements, so it doubles as the primary key.
/// - `channel`: slot index 0-7 for room traffic. Direct messages are PKI encrypted and always report channel 0, so
///   `isDirect` decides which it is.
/// - `rxTime`: the radio's clock, which may be absent on nodes without an RTC.
/// - `roomId`: which room a room message belongs to, or 0. A slot is only a place on one radio: another radio can carry
///   another room there, so history follows the room rather than the number.
public struct ChatMessage: Hashable, Sendable, Identifiable {
    public var id: Int32
    public var channel: Int
    public var fromNodeNum: Int32
    public var toNodeNum: Int32
    public var text: String
    public var sentAt: Int64
    public var rxTime: Int64?
    public var status: MessageStatus
    public var failureReason: String?
    public var isOutgoing: Bool
    public var rxSnr: Float?
    public var rxRssi: Int?
    public var hopsAway: Int?
    public var replyId: Int32?
    public var emoji: Int?
    /// True only when a 2.8 node signed the broadcast and it verified.
    public var signed: Bool
    public var roomId: Int32

    public init(
        id: Int32,
        channel: Int,
        fromNodeNum: Int32,
        toNodeNum: Int32,
        text: String,
        sentAt: Int64,
        rxTime: Int64? = nil,
        status: MessageStatus = .queued,
        failureReason: String? = nil,
        isOutgoing: Bool = false,
        rxSnr: Float? = nil,
        rxRssi: Int? = nil,
        hopsAway: Int? = nil,
        replyId: Int32? = nil,
        emoji: Int? = nil,
        signed: Bool = false,
        roomId: Int32 = 0
    ) {
        self.id = id
        self.channel = channel
        self.fromNodeNum = fromNodeNum
        self.toNodeNum = toNodeNum
        self.text = text
        self.sentAt = sentAt
        self.rxTime = rxTime
        self.status = status
        self.failureReason = failureReason
        self.isOutgoing = isOutgoing
        self.rxSnr = rxSnr
        self.rxRssi = rxRssi
        self.hopsAway = hopsAway
        self.replyId = replyId
        self.emoji = emoji
        self.signed = signed
        self.roomId = roomId
    }

    public var isDirect: Bool { toNodeNum != broadcastNodeNum }
    public var isNotice: Bool { fromNodeNum == noticeNodeNum }

    /// The other person in a direct conversation, matching the database's peerNodeNum.
    public func peerNode(myNodeNum: Int32?) -> Int32 {
        if isOutgoing { return toNodeNum }
        return fromNodeNum != myNodeNum && fromNodeNum != noticeNodeNum ? fromNodeNum : toNodeNum
    }
}
