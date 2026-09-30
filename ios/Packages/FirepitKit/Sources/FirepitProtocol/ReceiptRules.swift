/// What this phone owes the room.
///
/// Arrays keep insertion order, so the newest ids are at the end. That matters
/// only for `echoOf`, which repeats the most recent ones.
public struct PendingReceipts: Hashable, Sendable {
    public var delivered: [Int32]
    public var read: [Int32]

    public init(delivered: [Int32] = [], read: [Int32] = []) {
        self.delivered = delivered.uniqueInOrder()
        self.read = read.uniqueInOrder()
    }

    public init(delivered: Set<Int32>, read: Set<Int32> = []) {
        self.delivered = delivered.sorted()
        self.read = read.sorted()
    }

    public var isEmpty: Bool {
        delivered.isEmpty && read.isEmpty
    }

    public var size: Int {
        delivered.count + read.count
    }
}

/// How a receipt may travel, or why it may not.
///
/// A receipt says what this phone has been reading. On a shared Meshtastic
/// channel there is nobody who could read one, so it is sealed or not sent.
public enum ReceiptCarriage: Hashable, Sendable {
    /// Sealed under the room's own key, on the room's channel.
    case sealedRoom(roomId: Int32, channel: Int)

    /// Sealed to the phone of the one person the message came from.
    case sealedDirect(nodeNum: Int32)

    /// No private way to say it, so nothing is sent and nothing is tracked.
    case none
}

/// When a receipt goes out and which ones fit.
///
/// Receipts are held back rather than sent per message: one packet carries many,
/// and a member who replies carries theirs along for nothing.
public enum ReceiptRules {
    /// How a receipt for this conversation would travel.
    public static func carriageFor(
        channel: Int,
        roomId: Int32?,
        peer: Int32?,
        hasPeerKey: Bool,
        hasPeerPhoneKey: Bool = false
    ) -> ReceiptCarriage {
        if let roomId {
            return .sealedRoom(roomId: roomId, channel: channel)
        }
        if let peer, hasPeerKey, hasPeerPhoneKey {
            return .sealedDirect(nodeNum: peer)
        }
        return .none
    }

    /// True when this conversation is worth collecting receipts for at all.
    public static func tracks(roomId: Int32?, peer: Int32?) -> Bool {
        roomId != nil || peer != nil
    }

    /// Ids per packet.
    public static let maxIdsPerPacket = 40

    /// Recently sent ids repeated in the next packet.
    public static let echo = 8

    /// A message arrived and has not been opened.
    public static func received(pending: PendingReceipts, messageId: Int32) -> PendingReceipts {
        if pending.read.contains(messageId) {
            return pending
        }
        return PendingReceipts(delivered: pending.delivered.addingUnique(messageId), read: pending.read)
    }

    /// Opening a message replaces its delivery receipt rather than adding one.
    public static func opened(pending: PendingReceipts, messageIds: [Int32]) -> PendingReceipts {
        PendingReceipts(
            delivered: pending.delivered.filter { id in
                !messageIds.contains(id)
            },
            read: pending.read.addingUnique(contentsOf: messageIds)
        )
    }

    public static func opened(pending: PendingReceipts, messageIds: Set<Int32>) -> PendingReceipts {
        opened(pending: pending, messageIds: messageIds.sorted())
    }

    /// What goes in the next packet.
    public static func batch(pending: PendingReceipts, echo: PendingReceipts = PendingReceipts()) -> PendingReceipts {
        let read = pending.read.addingUnique(contentsOf: echo.read).prefix(maxIdsPerPacket)
        let room = maxIdsPerPacket - read.count
        let delivered = pending.delivered
            .addingUnique(contentsOf: echo.delivered)
            .filter { id in
                !read.contains(id)
            }
            .prefix(room)
        return PendingReceipts(delivered: Array(delivered), read: Array(read))
    }

    /// What is still owed once `batch` has gone out.
    public static func remaining(pending: PendingReceipts, sent: PendingReceipts) -> PendingReceipts {
        PendingReceipts(
            delivered: pending.delivered.filter { id in
                !sent.delivered.contains(id)
            },
            read: pending.read.filter { id in
                !sent.read.contains(id)
            }
        )
    }

    /// The tail of a sent batch, to be repeated once in the next one.
    public static func echoOf(sent: PendingReceipts) -> PendingReceipts {
        PendingReceipts(
            delivered: Array(sent.delivered.suffix(echo)),
            read: Array(sent.read.suffix(echo))
        )
    }
}

extension Array where Element: Equatable {
    fileprivate func uniqueInOrder() -> [Element] {
        var result: [Element] = []
        for item in self {
            if !result.contains(item) {
                result.append(item)
            }
        }
        return result
    }

    fileprivate func addingUnique(_ item: Element) -> [Element] {
        if contains(item) {
            return self
        }
        return self + [item]
    }

    fileprivate func addingUnique(contentsOf items: [Element]) -> [Element] {
        var result = self
        for item in items {
            if !result.contains(item) {
                result.append(item)
            }
        }
        return result
    }
}
