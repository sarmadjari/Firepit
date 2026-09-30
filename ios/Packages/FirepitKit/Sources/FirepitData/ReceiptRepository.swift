import FirepitCrypto
import FirepitModel
import FirepitProtocol
import FirepitProtos
import FirepitTransport
import Foundation
import os

/// Who has a message, who has read it, and when.
///
/// Receipts are collected rather than sent one per message: a packet carries
/// forty ids, so telling a room about a morning's reading costs one transmission
/// instead of forty. A message the sender marked urgent skips the wait.
public final class ReceiptRepository: Sendable {
    public let link: any RadioLinking
    public let mesh: MeshRepository
    public let roomKeys: RoomKeyStore
    public let receiptDao: ReceiptDao
    public let messageDao: MessageDao
    public let memberDao: RoomMemberDao
    public let phoneKeys: PhoneKeyStore

    private struct Conversation: Hashable, Sendable {
        var channel: Int
        var peer: Int32?
    }

    private struct State: Sendable {
        var pending: [Conversation: PendingReceipts] = [:]
        var echo: [Conversation: PendingReceipts] = [:]
        var reported: [Conversation: Set<Int32>] = [:]
        var flushScheduled = false
        var flushJob: Task<Void, Never>?
    }

    private let pacer = OutboundPacer(nowMillis: currentEpochMillis)
    private let mutex = Mutex(State())
    private let log = Logger(subsystem: "com.getfirepit.app", category: "ReceiptRepository")

    public init(
        link: any RadioLinking,
        mesh: MeshRepository,
        roomKeys: RoomKeyStore,
        receiptDao: ReceiptDao,
        messageDao: MessageDao,
        memberDao: RoomMemberDao,
        phoneKeys: PhoneKeyStore
    ) {
        self.link = link
        self.mesh = mesh
        self.roomKeys = roomKeys
        self.receiptDao = receiptDao
        self.messageDao = messageDao
        self.memberDao = memberDao
        self.phoneKeys = phoneKeys
    }

    deinit {
        mutex.withLock { state in
            state.flushJob?.cancel()
            state.flushJob = nil
        }
    }

    /**
     * Whether this conversation has receipts at all.
     *
     * The Firepit room id is what decides it: a standard Meshtastic channel
     * carries none. `ReceiptRules.tracks` holds the rule.
     */
    private func tracked(conversation: Conversation) -> Bool {
        ReceiptRules.tracks(roomId: firepitRoomFor(channel: conversation.channel), peer: conversation.peer)
    }

    /// The room a channel carries, only when we hold the key that seals it.
    private func firepitRoomFor(channel: Int) -> Int32? {
        guard let roomId = mesh.roomIdForChannel(channel),
            roomKeys.keyFor(roomId: roomId) != nil
        else {
            return nil
        }
        return roomId
    }

    /// Who has this message, and when they got it.
    public func observe(messageId: Int32) -> AsyncStream<[Receipt]> {
        receiptDao.observe(messageId: messageId)
    }

    /// The same, for everything on screen at once.
    public func observeAll(messageIds: [Int32]) -> AsyncStream<[Int32: [Receipt]]> {
        let source = receiptDao.observeForAll(messageIds: messageIds)
        return AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let task = Task {
                for await rows in source {
                    let grouped = Dictionary(grouping: rows, by: \.messageId).mapValues { receipts in
                        receipts.map { Receipt(nodeNum: $0.nodeNum, state: $0.state, at: $0.at) }
                    }
                    continuation.yield(grouped)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// A message arrived.
    public func received(channel: Int, messageId: Int32, peer: Int32? = nil) async {
        let key = Conversation(channel: channel, peer: peer)
        if !tracked(conversation: key) {
            return
        }
        mutex.withLock { state in
            if (state.reported[key] ?? []).contains(messageId) {
                return
            }
            state.pending[key] = ReceiptRules.received(
                pending: state.pending[key] ?? PendingReceipts(),
                messageId: messageId
            )
        }
        schedule(windowMillis: Self.quietWindowMillis)
    }

    /**
     * Messages were on screen long enough to count as read.
     *
     * Sent sooner than a delivery receipt: it is the half anyone actually waits
     * for, and arriving ten minutes late makes it useless.
     *
     * The caller passes everything on screen each time the list changes, so ids
     * already reported are dropped here. Without that a room would re-send the
     * same receipts on every arrival, which is the flood batching exists to
     * avoid.
     */
    public func read(channel: Int, messageIds: Set<Int32>, peer: Int32? = nil) async {
        if messageIds.isEmpty {
            return
        }
        let key = Conversation(channel: channel, peer: peer)
        if !tracked(conversation: key) {
            return
        }
        mutex.withLock { state in
            let seen = state.reported[key] ?? []
            let fresh = messageIds.subtracting(seen)
            if fresh.isEmpty {
                return
            }
            state.pending[key] = ReceiptRules.opened(
                pending: state.pending[key] ?? PendingReceipts(),
                messageIds: fresh
            )
        }
        schedule(windowMillis: Self.readWindowMillis)
    }

    /**
     * Someone told us what they have.
     *
     * Only believed about our own messages, and only from someone who was
     * meant to see them: the person a direct message went to, or a member of
     * the room it was sent in. Packet ids are in every header, so without this
     * anybody could put themselves on a message's "read by" list.
     */
    public func handle(from: Int32, receipt: Meshchat_Receipt, at: Int64 = currentEpochMillis()) async {
        for messageId in receipt.delivered.map({ Int32(bitPattern: $0) }) {
            if await mayReport(messageId: messageId, from: from) {
                try? await receiptDao.recordReceived(messageId: messageId, nodeNum: from, at: at)
            }
        }
        for messageId in receipt.read.map({ Int32(bitPattern: $0) }) {
            if await mayReport(messageId: messageId, from: from) {
                try? await receiptDao.recordRead(messageId: messageId, nodeNum: from, at: at)
            }
        }
    }

    private func mayReport(messageId: Int32, from: Int32) async -> Bool {
        guard let message = try? await messageDao.find(id: messageId) else {
            return false
        }
        let roomId = firepitRoomFor(channel: message.channel)
        let isRoomMember: Bool
        if message.toNodeNum == broadcastNodeNum, let roomId {
            isRoomMember = (try? await memberDao.findEntity(roomId: roomId, nodeNum: from)) != nil
        } else {
            isRoomMember = false
        }
        return TrustRules.receiptAllowed(
            isOutgoing: message.isOutgoing,
            sentTo: message.toNodeNum,
            sender: from,
            senderIsRoomMember: isRoomMember
        )
    }

    /**
     * A window rather than an immediate send, so a burst of arrivals becomes one
     * packet. Restarting it on each event would starve a busy room, so an
     * already-scheduled flush is left to run.
     */
    private func schedule(windowMillis: Int64) {
        let shouldLaunch = mutex.withLock { state -> Bool in
            if state.flushScheduled {
                return false
            }
            state.flushScheduled = true
            return true
        }
        if !shouldLaunch {
            return
        }
        let jitter = Int64.random(in: 0..<Self.jitterMillis)
        let job = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(windowMillis + jitter))
            do {
                try await self?.flush()
            } catch {
                self?.log.warning("receipt flush failed")
            }
        }
        mutex.withLock { state in
            state.flushJob = job
        }
    }

    /**
     * Send what is owed, one packet per conversation.
     *
     * A batch is capped, so anything left over needs another window rather than
     * waiting for the next message to arrive and carry it.
     */
    public func flush() async throws {
        let owed = mutex.withLock { state -> [Conversation: PendingReceipts] in
            state.flushScheduled = false
            return state.pending.filter { !$0.value.isEmpty }
        }
        for (conversation, outstanding) in owed {
            let previousEcho = mutex.withLock { $0.echo[conversation] ?? PendingReceipts() }
            let sent = ReceiptRules.batch(pending: outstanding, echo: previousEcho)
            if sent.isEmpty {
                continue
            }
            if try await !send(conversation: conversation, batch: sent) {
                // Nothing about this conversation will change, so holding the
                // ids only grows a list nobody will ever read, and re-offering
                // them would repeat the attempt on every message that arrives.
                mutex.withLock { state in
                    state.pending.removeValue(forKey: conversation)
                    remember(conversation: conversation, sent: outstanding, state: &state)
                }
                continue
            }
            mutex.withLock { state in
                state.pending[conversation] = ReceiptRules.remaining(
                    pending: state.pending[conversation] ?? PendingReceipts(),
                    sent: sent
                )
                state.echo[conversation] = ReceiptRules.echoOf(sent: sent)
                remember(conversation: conversation, sent: sent, state: &state)
            }
        }
        let hasMore = mutex.withLock { $0.pending.contains { !$0.value.isEmpty } }
        if hasMore {
            schedule(windowMillis: Self.quietWindowMillis)
        }
    }

    /// Ids already announced, kept bounded: a receipt is worth saying once.
    private func remember(conversation: Conversation, sent: PendingReceipts, state: inout State) {
        var seen = state.reported[conversation] ?? []
        seen.formUnion(sent.delivered)
        seen.formUnion(sent.read)
        if seen.count > Self.reportedMemory {
            seen = Set(seen.sorted().suffix(Self.reportedMemory))
        }
        state.reported[conversation] = seen
    }

    /**
     * Sent only where it can be sent privately: sealed under a room key, or
     * sealed to the one person's phone and encrypted to their radio as well.
     *
     * On an ordinary channel a receipt would announce to everyone in earshot
     * what this phone has been reading, which is worse than having no receipt.
     * Somebody whose phone key we never learned gets none either: it would only
     * tell a stranger this phone is on and reading.
     */
    private func send(conversation: Conversation, batch: PendingReceipts) async throws -> Bool {
        guard let myNodeNum = mesh.myNodeNum.value else {
            return false
        }
        let roomId = firepitRoomFor(channel: conversation.channel)
        let publicKey = conversation.peer.flatMap { mesh.radioKeyOf(nodeNum: $0) }
        let peerPhoneKey = conversation.peer == nil ? nil : await mesh.phoneKeyOf(nodeNum: conversation.peer!)
        var receipt = Meshchat_Receipt()
        receipt.roomID = UInt32(bitPattern: roomId ?? 0)
        receipt.delivered = batch.delivered.map { UInt32(bitPattern: $0) }
        receipt.read = batch.read.map { UInt32(bitPattern: $0) }
        var control = Meshchat_MeshChatControl()
        control.receipt = receipt

        let carriage = ReceiptRules.carriageFor(
            channel: conversation.channel,
            roomId: roomId,
            peer: conversation.peer,
            hasPeerKey: publicKey != nil,
            hasPeerPhoneKey: peerPhoneKey != nil
        )

        let packet: MeshPacket
        switch carriage {
        case .sealedRoom(let roomId, let channel):
            guard let payload = seal(roomId: roomId, myNodeNum: myNodeNum, control: control) else {
                return false
            }
            packet = try MeshPacketBuilder.meshPacket(
                to: broadcastNodeNum,
                channel: channel,
                portNum: .privateApp,
                payload: payload,
                hopLimit: mesh.hopLimitForSending(),
                wantAck: true,
                priority: .background
            )
        case .sealedDirect(let nodeNum):
            let sealed = try phoneKeys.sealDirect(
                peerPublic: peerPhoneKey!,
                plaintext: try control.serializedData(),
                context: DirectSeal.contextOf(senderNodeNum: myNodeNum, recipientNodeNum: nodeNum)
            )
            var direct = Meshchat_SealedDirect()
            direct.ciphertext = sealed
            var outer = Meshchat_MeshChatControl()
            outer.sealedDirect = direct
            packet = try MeshPacketBuilder.meshPacket(
                to: nodeNum,
                channel: conversation.channel,
                portNum: .privateApp,
                payload: try outer.serializedData(),
                hopLimit: mesh.hopLimitForSending(),
                wantAck: true,
                priority: .background,
                pkiEncrypted: true,
                publicKey: publicKey!
            )
        case .none:
            log.info("no private way to send a receipt")
            return false
        }

        await pacer.awaitSlot(portNum: .privateApp)
        var toRadio = ToRadio()
        toRadio.packet = packet
        do {
            try await link.send(toRadio)
            return true
        } catch {
            log.warning("receipt send failed")
            return false
        }
    }

    /**
     * Under the room's own key, which the radio never holds.
     *
     * Null when there is no such key, which means the channel is an ordinary
     * Meshtastic one. Receipts are a Firepit concept: on a shared channel they
     * would be unreadable noise to every other client, and would announce to
     * everyone in earshot what this phone has been reading.
     */
    private func seal(roomId: Int32, myNodeNum: Int32, control: Meshchat_MeshChatControl) -> Data? {
        guard let key = roomKeys.sealingKey(roomId: roomId),
            let plaintext = try? control.serializedData()
        else {
            return nil
        }
        let sealed = SealedText.seal(
            key: key,
            plaintext: plaintext,
            context: SealedText.contextOf(roomId: roomId, senderNodeNum: myNodeNum)
        )
        var message = Meshchat_SealedMessage()
        message.roomID = UInt32(bitPattern: roomId)
        message.ciphertext = sealed
        // Without this the receiver reaches for generation 1 and every receipt
        // goes unreadable the moment a room rotates its key.
        message.generation = UInt32(roomKeys.generationOf(roomId: roomId))
        var outer = Meshchat_MeshChatControl()
        outer.sealedMessage = message
        return try? outer.serializedData()
    }

    private static let quietWindowMillis: Int64 = 30_000
    private static let readWindowMillis: Int64 = 3_000
    private static let reportedMemory = 500
    private static let jitterMillis: Int64 = 20_000
}
