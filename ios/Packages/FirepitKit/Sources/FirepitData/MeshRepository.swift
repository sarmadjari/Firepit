import FirepitCrypto
import FirepitModel
import FirepitProtocol
import FirepitProtos
import FirepitTransport
import Foundation
import os

/// Why a message was not put on the air, in terms the composer can show.
public enum SendError: Error, Sendable, Equatable {
    case notConnected

    /**
     * The firmware cannot encrypt to a node whose public key it has never seen,
     * and sending anyway would mean falling back to the channel key — which on
     * the primary is a key every Meshtastic radio holds.
     */
    case noPeerKey

    /// Nothing to seal with, so there is no private way to say it.
    case notARoom

    /// A channel with no key at all, Firepit has no reason to put words in the clear.
    case notEncrypted

    /// A Firepit room still on the radio whose key this phone does not hold.
    case roomKeyMissing

    /// The room moved to a key that never reached us, the old one only reaches whoever was removed.
    case roomMovedOn

    case tooLong(bytes: Int, limit: Int)

    public var message: String {
        switch self {
        case .notConnected:
            return "Connect your node first"
        case .noPeerKey:
            return "No secure channel to this person yet. Wait for their node to introduce itself."
        case .notARoom:
            return "This channel isn't a conversation. Create a room first."
        case .notEncrypted:
            return "This channel has no encryption at all."
        case .roomKeyMissing:
            return "This room's key isn't on this phone."
        case .roomMovedOn:
            return "This room moved to a new key that didn't reach this phone."
        case .tooLong(let bytes, let limit):
            return "Message is \(bytes) bytes, over the \(limit)-byte limit"
        }
    }
}

/// What a radio said about one of our packets: the id it answers, who said it, and how it went.
public struct RoutingEvent: Sendable, Equatable {
    public var requestId: Int32
    public var from: Int32
    public var error: Routing.Error

    public init(requestId: Int32, from: Int32, error: Routing.Error) {
        self.requestId = requestId
        self.from = from
        self.error = error
    }
}

/// Single source of truth for chat and node state.
///
/// Owns the one-way flow from the radio into storage, and the outbound path from
/// the UI back out. Nothing above this layer touches the transport.
public final class MeshRepository: Sendable {
    public let link: any RadioLinking
    public let messageDao: MessageDao
    public let nodeDao: NodeDao
    public let sessionStore: SessionStore
    public let roomKeys: RoomKeyStore
    public let peerKeyDao: PeerKeyDao
    public let memberDao: RoomMemberDao
    public let phoneKeys: PhoneKeyStore
    public let roomActivity: RoomActivityDao

    private let pacer = OutboundPacer(nowMillis: currentEpochMillis)
    private let tasks = Mutex<[Task<Void, Never>]>([])
    private let routing = Broadcast<RoutingEvent>()
    private let log = Logger(subsystem: "com.getfirepit.app", category: "FirepitMesh")
    private let hopLimitState = Mutex(MeshConstants.defaultHopLimit)

    // Seeded from the last session so the map can identify us before, or
    // without, a radio connection.
    public let myNodeNum: CurrentValue<Int32?>

    /// How far the radio's clock sits from the phone's, once a packet has shown us.
    public let clockSkewMillis = CurrentValue<Int64?>(nil)

    public let channels = CurrentValue<[RoomChannel]>([])
    public let snapshot = CurrentValue<RadioSnapshot?>(nil)

    /// Newly stored incoming messages. Replays nothing, so a late collector cannot re-notify.
    public let incomingMessages = Broadcast<ChatMessage>()

    // Derived from the link, not from myNodeNum: that is remembered across
    // sessions so the map can identify us offline, and would otherwise report a
    // connection that does not exist.
    public let isConnected = CurrentValue(false)

    /**
     * How busy our own radio finds the channel, or null before it says.
     *
     * Read from our own node rather than the mesh average: congestion is local,
     * and it is our antenna that has to find a gap to speak in.
     */
    public let channelLoad = CurrentValue<ChannelLoad?>(nil)

    public init(
        link: any RadioLinking,
        messageDao: MessageDao,
        nodeDao: NodeDao,
        sessionStore: SessionStore,
        roomKeys: RoomKeyStore,
        peerKeyDao: PeerKeyDao,
        memberDao: RoomMemberDao,
        phoneKeys: PhoneKeyStore,
        roomActivity: RoomActivityDao
    ) {
        self.link = link
        self.messageDao = messageDao
        self.nodeDao = nodeDao
        self.sessionStore = sessionStore
        self.roomKeys = roomKeys
        self.peerKeyDao = peerKeyDao
        self.memberDao = memberDao
        self.phoneKeys = phoneKeys
        self.roomActivity = roomActivity
        self.myNodeNum = CurrentValue(sessionStore.myNodeNum)
    }

    deinit {
        tasks.withLock { jobs in
            for job in jobs {
                job.cancel()
            }
            jobs.removeAll()
        }
    }

    /**
     * Starts consuming the radio. Idempotent, because Android's repository is a
     * singleton and any screen may ask for it to be ready.
     */
    public func start() {
        tasks.withLock { jobs in
            guard jobs.isEmpty else {
                return
            }
            let link = self.link
            let nodeDao = self.nodeDao
            jobs.append(
                Task { [weak self, link] in
                    for await state in link.state.subscribe() {
                        guard let self else { break }
                        switch state {
                        case .ready(let snapshot):
                            self.isConnected.set(true)
                            self.applySnapshot(snapshot)
                        default:
                            // A different radio has a different clock; the old one's skew would otherwise describe a
                            // device no longer attached.
                            self.clockSkewMillis.set(nil)
                            self.isConnected.set(false)
                        }
                    }
                })
            jobs.append(
                Task { [weak self, link] in
                    for await message in link.inbound.subscribe() {
                        guard let self else { break }
                        await self.handle(message: message)
                    }
                })
            jobs.append(
                Task { [weak self, nodeDao] in
                    for await nodes in nodeDao.observeAll() {
                        guard let self else { break }
                        guard let own = self.myNodeNum.value else {
                            self.channelLoad.set(nil)
                            continue
                        }
                        let node = nodes.first { $0.nodeNum == own }
                        self.channelLoad.set(ChannelLoad.of(utilizationPercent: node?.channelUtilization))
                    }
                })
        }
    }

    /**
     * Sends packet and waits for from itself to acknowledge it.
     *
     * Our radio accepting a packet says nothing about where it went, and an
     * implicit ack only says a neighbour repeated it. Only the recipient's own
     * answer shows it arrived. Our radio refusing it — no key for them, nobody
     * left to repeat, rate-limit exceeded — is allowed to stop the wait early.
     */
    public func sendAwaitingAck(packet: MeshPacket, from: Int32, timeout: Duration) async -> Bool {
        let own = myNodeNum.value
        let requestId = Int32(bitPattern: packet.id)
        // Listening starts before sending: an answer can arrive first. Subscribing here, synchronously, rather than
        // inside the task below, which may only start after the send has returned.
        let events = routing.subscribe()
        let answerTask = Task<RoutingEvent?, Never> {
            try? await withTimeoutOrNil(timeout) {
                await events.first { event in
                    event.requestId == requestId && (event.from == from || (event.from == own && event.error != .none))
                }
            }
        }
        do {
            var toRadio = ToRadio()
            toRadio.packet = packet
            try await link.send(toRadio)
        } catch {
            answerTask.cancel()
            log.warning("could not send packet")
            return false
        }
        let event = await answerTask.value
        return event?.from == from && event?.error == Routing.Error.none
    }

    /// Whether words to peer can be sealed to their phone, as that changes.
    public func observeDirectSealed(peer: Int32) -> AsyncStream<Bool> {
        peerKeyDao.observeKnown(nodeNum: peer)
    }

    /// Our own User, needed to introduce ourselves when joining a room.
    public var myUser: User? {
        guard let snap = snapshot.value,
            let myNodeNum = snap.myNodeNum,
            let node = snap.nodes[myNodeNum]
        else {
            return nil
        }
        return node.user
    }

    /// Records our own fix from the phone's GPS. Local only, nothing is transmitted.
    public func setOwnPosition(
        nodeNum: Int32,
        latitudeI: Int32,
        longitudeI: Int32,
        altitude: Int?,
        timeMillis: Int64
    ) async throws {
        try await nodeDao.updatePosition(
            nodeNum: nodeNum,
            latitudeI: latitudeI,
            longitudeI: longitudeI,
            altitude: altitude,
            positionTime: timeMillis,
            positionPrecision: PositionPrecision.full,
            groundSpeed: nil,
            groundTrack: nil
        )
    }

    /**
     * Mirrors a rename onto our own node.
     *
     * The firmware acknowledges `set_owner` but does not send our own NodeInfo
     * back, so without this the app keeps showing the old name until the next
     * config download.
     */
    public func setOwnName(nodeNum: Int32, longName: String, shortName: String) async throws {
        guard var node = try await nodeDao.find(nodeNum: nodeNum) else {
            return
        }
        node.longName = longName
        node.shortName = shortName
        try await nodeDao.save(node: node, now: currentEpochMillis())
    }

    /**
     * A person's phone key, which is what makes a direct message private from
     * the radios as well as the mesh. Null for anyone who has never shared a
     * room with us, which includes everybody not running Firepit.
     */
    internal func phoneKeyOf(nodeNum: Int32) async -> Data? {
        guard let entity = try? await peerKeyDao.find(nodeNum: nodeNum),
            let key = Data(base64Encoded: entity.phoneKey),
            KeyEnvelope.isValidPublicKey(key)
        else {
            return nil
        }
        return key
    }

    /**
     * Which conversation a slot holds, for filing its history: the room id when
     * it has one, so history follows the room between radios, else 0.
     */
    private func conversationIdOf(channel: Int) -> Int32 {
        roomIdForChannel(channel) ?? 0
    }

    /// A node's public key, required before anything can be sent to it over PKI.
    public func publicKeyOf(nodeNum: Int32) async -> Data? {
        if let key = snapshot.value?.nodes[nodeNum]?.user.publicKey,
            key.count == Self.publicKeySize
        {
            return key
        }
        guard let node = try? await nodeDao.find(nodeNum: nodeNum),
            let text = node.publicKey,
            let key = Data(base64Encoded: text),
            key.count == Self.publicKeySize
        else {
            return nil
        }
        return key
    }

    /// The key our own radio holds for nodeNum, as it reported at connection, or null.
    internal func radioKeyOf(nodeNum: Int32) -> Data? {
        let key = snapshot.value?.nodes[nodeNum]?.user.publicKey
        return key?.count == Self.publicKeySize ? key : nil
    }

    /**
     * nodeNum as a contact the radio can take back: its own NodeInfo while it
     * still holds one, else what this phone kept. The radio's node list is
     * bounded and evicts, this phone's is not, so a member the radio forgot can
     * be put back instead of silently becoming unreachable.
     */
    internal func contactFor(nodeNum: Int32) async -> User? {
        if let user = snapshot.value?.nodes[nodeNum]?.user,
            user.publicKey.count == Self.publicKeySize
        {
            return user
        }
        guard let node = try? await nodeDao.find(nodeNum: nodeNum),
            let encoded = node.publicKey,
            let key = Data(base64Encoded: encoded),
            key.count == Self.publicKeySize
        else {
            return nil
        }
        var user = User()
        user.id = MeshConstants.formatNodeId(nodeNum)
        user.longName = node.longName ?? ""
        user.shortName = node.shortName ?? ""
        user.publicKey = key
        return user
    }

    /// How far our own packets travel: the radio's own setting, within the firmware's bounds.
    internal func hopLimitForSending() -> Int {
        hopLimitState.withLock { $0 }
    }

    public func observeChannel(channel: Int) -> AsyncStream<[ChatMessage]> {
        messageDao.observeChannel(channel: channel)
    }

    /// Newest message per channel, for the chat list previews.
    public func observeLatestPerChannel() -> AsyncStream<[ChatMessage]> {
        messageDao.latestPerChannel()
    }

    /// One conversation with one person.
    public func observeDirect(peer: Int32) -> AsyncStream<[ChatMessage]> {
        messageDao.observeDirect(peer: peer)
    }

    /// Newest message per person, for the Direct list.
    public func observeDirectLatest() -> AsyncStream<[ChatMessage]> {
        messageDao.directLatest()
    }

    public func observeNodes() -> AsyncStream<[MeshNode]> {
        nodeDao.observeAll()
    }

    /**
     * What kind of conversation a slot holds.
     *
     * Holding the Firepit key is the only thing that makes a room sealed, so it
     * is asked first and nothing else can stand in for it. Everything else is
     * an ordinary Meshtastic channel, described by how private its own key is.
     */
    private func kindOf(roomId: Int32, psk: Data?, secondary: Bool) -> RoomKind {
        if roomId != 0, roomKeys.holds(roomId: roomId) {
            return roomKeys.isSuperseded(roomId: roomId) ? .firepitMovedOn : .firepit
        }
        if secondary, roomId != 0, psk?.count == RoomCrypto.pskSize {
            return .firepitKeyMissing
        }
        switch ChannelKey.of(psk) {
        case .private:
            return .meshtasticPrivate
        case .default:
            return .meshtasticPublic
        case .none:
            return .unencrypted
        }
    }

    private func roomChannelOf(channel: Channel) -> RoomChannel {
        let id = Int32(bitPattern: channel.settings.id)
        let role: ChannelRole
        switch channel.role {
        case .primary:
            role = .primary
        case .secondary:
            role = .secondary
        default:
            role = .disabled
        }
        let psk = channel.settings.psk.isEmpty ? nil : channel.settings.psk
        return RoomChannel(
            index: Int(channel.index),
            name: sanitizeMeshText(channel.settings.name),
            role: role,
            id: id,
            positionPrecision: Int(channel.settings.moduleSettings.positionPrecision),
            kind: kindOf(roomId: id, psk: psk, secondary: role == .secondary)
        )
    }

    /**
     * Re-reads what each slot is, after something changed that the radio does
     * not know about: a room moving on without us, or a key arriving.
     */
    internal func refreshRoomKinds() {
        guard let channels = snapshot.value?.channels.values else {
            return
        }
        self.channels.set(channels.sorted { $0.index < $1.index }.map(roomChannelOf(channel:)))
    }

    /**
     * The radio lists channels only during the config download. Admin writes
     * that we performed ourselves are mirrored here so the UI sees them before
     * the next reconnect.
     */
    internal func applyChannelWrite(_ channel: Channel) {
        guard var snap = snapshot.value else {
            return
        }
        snap.channels[Int32(channel.index)] = channel
        snapshot.set(snap)
        channels.set(
            (channels.value.filter { $0.index != Int(channel.index) } + [roomChannelOf(channel: channel)])
                .sorted { $0.index < $1.index }
        )
    }

    internal func roomIdForChannel(_ channel: Int) -> Int32? {
        {
            let id = channels.value.first { $0.index == channel && $0.isRoom }?.id ?? 0
            return id == 0 ? nil : id
        }()
    }

    internal func isRoomSlot(_ channel: Int) -> Bool {
        channels.value.contains { $0.index == channel && $0.isRoom }
    }

    internal func channelKeyOf(_ channel: Int) -> ChannelKey {
        guard let psk = snapshot.value?.channels[Int32(channel)]?.settings.psk else {
            return .none
        }
        return ChannelKey.of(psk)
    }

    /**
     * Saves text sent by the UI, choosing the same carriage Android does.
     *
     * Room messages are sealed before the radio sees them. Direct messages are
     * sealed to the peer phone when possible, then to the peer radio so relays
     * cannot read them. Meshtastic channels intentionally remain interoperable.
     */
    public func sendText(channel: Int, text: String, to: Int32 = broadcastNodeNum, replyId: Int32? = nil) async throws {
        guard let myNodeNum = myNodeNum.value else {
            throw SendError.notConnected
        }
        let payload = Data(text.utf8)
        let roomId = roomIdForChannel(channel) ?? 0
        let canSeal = roomId != 0 && roomKeys.canSeal(roomId: roomId)
        let peerKey = to == broadcastNodeNum ? nil : await publicKeyOf(nodeNum: to)
        let peerPhoneKey = to == broadcastNodeNum ? nil : await phoneKeyOf(nodeNum: to)
        let kind = channels.value.first { $0.index == channel }?.kind
        let carriage = MessagePrivacy.carriageFor(
            to: to,
            channel: channel,
            isRoomSlot: isRoomSlot(channel),
            sealingRoomId: canSeal ? roomId : nil,
            hasPeerKey: peerKey != nil,
            channelKey: channelKeyOf(channel),
            hasPeerPhoneKey: peerPhoneKey != nil,
            roomKind: kind?.isStalledRoom == true ? kind : nil
        )
        let limit = MessagePrivacy.textBudgetFor(carriage: carriage)
        if case .refused = carriage {
            // Refused messages throw below before this limit matters.
        } else if payload.count > limit {
            throw SendError.tooLong(bytes: payload.count, limit: limit)
        }
        let packet = try buildTextPacket(
            carriage: carriage,
            to: to,
            channel: channel,
            text: text,
            payload: payload,
            peerKey: peerKey,
            peerPhoneKey: peerPhoneKey,
            myNodeNum: myNodeNum,
            replyId: replyId
        )
        try await messageDao.save(
            message: ChatMessage(
                id: Int32(bitPattern: packet.id),
                channel: channel,
                fromNodeNum: myNodeNum,
                toNodeNum: to,
                text: text,
                sentAt: currentEpochMillis(),
                status: .queued,
                isOutgoing: true,
                replyId: replyId,
                roomId: to == broadcastNodeNum ? conversationIdOf(channel: channel) : 0
            ),
            myNodeNum: myNodeNum
        )
        if case .sealedRoom(let roomId, _) = carriage {
            try? await roomActivity.recordActivity(roomId: roomId, now: currentEpochMillis())
        }
        await pacer.awaitSlot(portNum: .textMessageApp)
        do {
            var toRadio = ToRadio()
            toRadio.packet = packet
            try await link.send(toRadio)
        } catch {
            await setStatus(packetId: Int32(bitPattern: packet.id), next: .failed, reason: "Send failed")
        }
        scheduleAckTimeout(packetId: Int32(bitPattern: packet.id))
    }

    private func buildTextPacket(
        carriage: Carriage,
        to: Int32,
        channel: Int,
        text: String,
        payload: Data,
        peerKey: Data?,
        peerPhoneKey: Data?,
        myNodeNum: Int32,
        replyId: Int32?
    ) throws -> MeshPacket {
        switch carriage {
        case .refused(let reason):
            throw sendError(for: reason)
        case .sealedDirect(let nodeNum):
            var inner = Meshchat_MeshChatControl()
            inner.version = UInt32(InviteCodec.version)
            var roomText = Meshchat_RoomText()
            roomText.text = text
            roomText.replyID = UInt32(bitPattern: replyId ?? 0)
            inner.roomText = roomText
            let sealed = try phoneKeys.sealDirect(
                peerPublic: peerPhoneKey!,
                plaintext: try inner.serializedData(),
                context: DirectSeal.contextOf(senderNodeNum: myNodeNum, recipientNodeNum: nodeNum)
            )
            var direct = Meshchat_SealedDirect()
            direct.ciphertext = sealed
            var outer = Meshchat_MeshChatControl()
            outer.version = UInt32(InviteCodec.version)
            outer.sealedDirect = direct
            return try MeshPacketBuilder.meshPacket(
                to: nodeNum,
                channel: channel,
                portNum: .privateApp,
                payload: try outer.serializedData(),
                hopLimit: hopLimitForSending(),
                wantAck: true,
                pkiEncrypted: true,
                publicKey: peerKey!
            )
        case .toOneNode(let nodeNum):
            return try MeshPacketBuilder.meshPacket(
                to: nodeNum,
                channel: channel,
                portNum: .textMessageApp,
                payload: payload,
                hopLimit: hopLimitForSending(),
                wantAck: true,
                pkiEncrypted: true,
                publicKey: peerKey!,
                replyId: replyId
            )
        case .openChannel(let channel, _):
            return try MeshPacketBuilder.meshPacket(
                to: to,
                channel: channel,
                portNum: .textMessageApp,
                payload: payload,
                hopLimit: hopLimitForSending(),
                wantAck: true,
                replyId: replyId
            )
        case .sealedRoom(let roomId, let channel):
            var inner = Meshchat_MeshChatControl()
            var roomText = Meshchat_RoomText()
            roomText.text = text
            roomText.replyID = UInt32(bitPattern: replyId ?? 0)
            inner.roomText = roomText
            guard
                let message = roomKeys.seal(
                    roomId: roomId, sender: myNodeNum, plaintext: try inner.serializedData())
            else {
                throw SendError.roomKeyMissing
            }
            var outer = Meshchat_MeshChatControl()
            outer.sealedMessage = message
            return try MeshPacketBuilder.meshPacket(
                to: to,
                channel: channel,
                portNum: .privateApp,
                payload: try outer.serializedData(),
                hopLimit: hopLimitForSending(),
                wantAck: true
            )
        }
    }

    private func sendError(for reason: Carriage.Reason) -> SendError {
        switch reason {
        case .noPeerKey:
            return .noPeerKey
        case .notARoom:
            return .notARoom
        case .notEncrypted:
            return .notEncrypted
        case .roomKeyMissing:
            return .roomKeyMissing
        case .roomMovedOn:
            return .roomMovedOn
        }
    }

    private func scheduleAckTimeout(packetId: Int32) {
        Task { [weak self] in
            try? await Task.sleep(for: MessageStatusRules.ackTimeout)
            guard let self,
                let current = try? await self.messageDao.find(id: packetId),
                current.status == .sentToNode
            else {
                return
            }
            await self.setStatus(packetId: packetId, next: .unknown, reason: nil)
        }
    }

    private func applySnapshot(_ next: RadioSnapshot) {
        snapshot.set(next)
        if let myNodeNum = next.myNodeNum {
            self.myNodeNum.set(myNodeNum)
            sessionStore.myNodeNum = myNodeNum
        }
        let hopLimit = next.lora.map { Int($0.hopLimit) }
        hopLimitState.withLock { current in
            current =
                hopLimit.flatMap { (1...MeshConstants.maxHopLimit).contains($0) ? $0 : nil }
                ?? MeshConstants.defaultHopLimit
        }
        let roomChannels = next.channels.values
            .sorted { $0.index < $1.index }
            .map(roomChannelOf(channel:))
        channels.set(roomChannels)
        Task { [weak self] in
            guard let self else {
                return
            }
            for node in next.nodes.values {
                try? await self.saveNode(info: node)
            }
        }
    }

    private func handle(message: FromRadio) async {
        switch message.payloadVariant {
        case .packet(let packet):
            await handlePacket(packet)
        case .queueStatus(let status):
            await handleQueueStatus(status)
        case .nodeInfo(let info):
            try? await saveNode(info: info)
        default:
            break
        }
    }

    /**
     * The radio hands a packet over as soon as it has it, so its stamp should
     * match the phone's clock. Whatever it is out by is what it will put on
     * every message it passes us.
     */
    private func noteClockSkew(packet: MeshPacket) {
        guard packet.hasRxTime, packet.rxTime != 0 else {
            return
        }
        let skew = Int64(packet.rxTime) * 1_000 - currentEpochMillis()
        if clockSkewMillis.value == nil {
            log.info("radio clock skew noted")
        }
        clockSkewMillis.set(skew)
    }

    /**
     * A stamp from our own radio, read in the phone's terms. See RadioClock.
     */
    private func onPhoneClock(_ radioSeconds: UInt32) -> Int64? {
        RadioClock.onPhoneClock(
            radioSeconds: Int32(bitPattern: radioSeconds),
            skewMillis: clockSkewMillis.value,
            now: currentEpochMillis()
        )
    }

    /**
     * A stamp another node put on its own fix, kept only while it could be
     * true. See RadioClock.
     */
    private func ifPlausible(_ claimSeconds: UInt32) -> Int64? {
        RadioClock.ifPlausible(claimSeconds: Int32(bitPattern: claimSeconds), now: currentEpochMillis())
    }

    private func handlePacket(_ packet: MeshPacket) async {
        guard case .decoded(let data)? = packet.payloadVariant else {
            return
        }
        noteClockSkew(packet: packet)
        try? await markHeard(packet: packet)
        switch data.portnum {
        case .textMessageApp:
            await saveIncomingText(packet: packet, data: data)
        case .textMessageCompressedApp:
            log.warning("dropped compressed text")
        case .routingApp:
            await handleRouting(packet: packet, data: data)
        case .positionApp:
            await handlePosition(packet: packet, data: data)
        case .telemetryApp:
            await handleTelemetry(packet: packet, data: data)
        default:
            break
        }
    }

    /**
     * Notes that a node was heard, from any packet at all.
     *
     * NodeInfo carries last_heard once at connection, so without this a live
     * node can look stale until it happens to announce itself again.
     */
    private func markHeard(packet: MeshPacket) async throws {
        let from = Int32(bitPattern: packet.from)
        if from == 0 || from == myNodeNum.value {
            return
        }
        let now = currentEpochMillis()
        let hops = packet.hopStart > 0 ? Int(packet.hopStart) - Int(packet.hopLimit) : nil
        try await nodeDao.markHeard(
            nodeNum: from,
            heardAt: now,
            snr: packet.rxSnr == 0 ? nil : packet.rxSnr,
            rssi: packet.hasRxRssi && packet.rxRssi != 0 ? Int(packet.rxRssi) : nil,
            hopsAway: hops
        )
    }

    private func saveIncomingText(packet: MeshPacket, data: DataMessage) async {
        let direct = Int32(bitPattern: packet.to) == myNodeNum.value
        let acceptable = TrustRules.plainTextAcceptable(
            direct: direct,
            pkiEncrypted: packet.pkiEncrypted,
            onPrimary: Int(packet.channel) == ChannelSlotManager.primarySlot,
            onFirepitRoom: roomIdForChannel(Int(packet.channel)).map { roomKeys.holds(roomId: $0) } == true
        )
        guard acceptable else {
            log.warning("dropped unsealed text")
            return
        }
        let text = String(data: data.payload, encoding: .utf8) ?? ""
        let reply = data.replyID == 0 ? nil : Int32(bitPattern: data.replyID)
        let emoji = data.emoji == 0 ? nil : Int(data.emoji)
        await saveText(packet: packet, text: sanitizeMeshText(text), replyId: reply, emoji: emoji)
    }

    /**
     * Words that arrived sealed in roomId. Stored like any other message: the
     * encryption is how it travelled, and the database is encrypted in its turn.
     */
    internal func saveSealedText(packet: MeshPacket, text: String, replyId: Int32?, roomId: Int32) async {
        await saveText(packet: packet, text: sanitizeMeshText(text), replyId: replyId, emoji: nil, roomId: roomId)
    }

    /// One person's words, sealed by their phone to ours and already opened.
    internal func saveSealedDirectText(packet: MeshPacket, text: String, replyId: Int32?) async {
        await saveText(packet: packet, text: sanitizeMeshText(text), replyId: replyId, emoji: nil, roomId: 0)
    }

    private func saveText(
        packet: MeshPacket,
        text: String,
        replyId: Int32?,
        emoji: Int?,
        roomId: Int32? = nil
    ) async {
        guard let myNodeNum = myNodeNum.value else {
            return
        }
        let from = Int32(bitPattern: packet.from)
        if from == myNodeNum || text.isEmpty {
            return
        }
        let to = Int32(bitPattern: packet.to)
        let storedRoomId = roomId ?? (to == myNodeNum ? 0 : conversationIdOf(channel: Int(packet.channel)))
        let message = ChatMessage(
            id: Int32(bitPattern: packet.id),
            channel: Int(packet.channel),
            fromNodeNum: from,
            toNodeNum: to,
            text: text,
            sentAt: currentEpochMillis(),
            rxTime: packet.hasRxTime ? onPhoneClock(packet.rxTime) : nil,
            status: .received,
            isOutgoing: false,
            rxSnr: packet.rxSnr == 0 ? nil : packet.rxSnr,
            rxRssi: packet.hasRxRssi && packet.rxRssi != 0 ? Int(packet.rxRssi) : nil,
            hopsAway: packet.hopStart > 0 ? Int(packet.hopStart) - Int(packet.hopLimit) : nil,
            replyId: replyId,
            emoji: emoji,
            signed: packet.xeddsaSigned,
            roomId: storedRoomId
        )
        do {
            if try await messageDao.saveIfNew(message: message, myNodeNum: myNodeNum) {
                incomingMessages.send(message)
            }
        } catch {
            log.warning("could not save message")
        }
    }

    private func handleRouting(packet: MeshPacket, data: DataMessage) async {
        guard let myNodeNum = myNodeNum.value else {
            return
        }
        let originalId = Int32(bitPattern: data.requestID)
        guard originalId != 0,
            let decoded = try? Routing(serializedBytes: data.payload)
        else {
            return
        }
        let error = decoded.errorReason
        let from = Int32(bitPattern: packet.from)
        routing.send(RoutingEvent(requestId: originalId, from: from, error: error))
        guard let sent = try? await messageDao.find(id: originalId), sent.isOutgoing else {
            return
        }
        guard
            let next = MessageStatusRules.fromRouting(
                errorReason: error,
                ackFrom: from,
                myNodeNum: myNodeNum,
                sentTo: sent.toNodeNum
            )
        else {
            log.warning("routing from unrelated node ignored")
            return
        }
        await setStatus(packetId: originalId, next: next, reason: next.isFailure ? String(describing: error) : nil)
    }

    private func handleQueueStatus(_ status: QueueStatus) async {
        let packetId = Int32(bitPattern: status.meshPacketID)
        guard packetId != 0 else {
            return
        }
        let next = MessageStatusRules.fromQueueStatus(res: status.res)
        await setStatus(
            packetId: packetId,
            next: next,
            reason: next.isFailure ? "QueueStatus res=\(status.res)" : nil
        )
    }

    /**
     * The person a sealed message went to says their phone could not open it.
     * Set outright rather than advanced: their radio's acknowledgement may
     * already have marked it delivered, which it was — to a radio, not a
     * reader. False when messageId is not ours to by.
     */
    internal func markNotOpened(messageId: Int32, by: Int32) async -> Bool {
        guard let message = try? await messageDao.find(id: messageId),
            message.isOutgoing,
            message.toNodeNum == by
        else {
            return false
        }
        try? await messageDao.updateStatus(
            id: message.id,
            status: .failed,
            reason: "Their phone could not open it: it has not learned your key yet."
        )
        return true
    }

    private func setStatus(packetId: Int32, next: MessageStatus, reason: String?) async {
        guard let current = try? await messageDao.find(id: packetId), current.isOutgoing else {
            return
        }
        let advanced = MessageStatusRules.advance(current: current.status, next: next)
        guard advanced != current.status else {
            return
        }
        try? await messageDao.updateStatus(id: packetId, status: advanced, reason: reason)
    }

    private func saveNode(info: NodeInfo) async throws {
        let user: User? = info.hasUser ? info.user : nil
        let metrics: DeviceMetrics? = info.hasDeviceMetrics ? info.deviceMetrics : nil
        // The radio learned this from an unsealed broadcast, if at all; a member's position is only believed sealed,
        // from their phone.
        let isMember = (try? await memberDao.isInAnyRoom(nodeNum: Int32(bitPattern: info.num))) == true
        let position: Position? =
            info.hasPosition && TrustRules.unsealedPositionAcceptable(senderInOurRooms: isMember) ? info.position : nil
        try await nodeDao.save(
            node: MeshNode(
                nodeNum: Int32(bitPattern: info.num),
                userId: user?.id,
                longName: user.map { sanitizeMeshText($0.longName) },
                shortName: user.map { sanitizeMeshText($0.shortName) },
                hwModel: user.map { Self.protoName(of: $0, field: "hwModel") },
                role: user.map { Self.protoName(of: $0, field: "role") },
                publicKey: user.flatMap { $0.publicKey.isEmpty ? nil : $0.publicKey.base64EncodedString() },
                isUnmessagable: user?.isUnmessagable == true,
                lastHeard: onPhoneClock(info.lastHeard),
                snr: info.snr == 0 ? nil : info.snr,
                hopsAway: info.hasHopsAway ? Int(info.hopsAway) : nil,
                batteryLevel: metrics.flatMap { $0.hasBatteryLevel ? Int($0.batteryLevel) : nil },
                voltage: metrics.flatMap { $0.hasVoltage ? $0.voltage : nil },
                channelUtilization: metrics.flatMap { $0.hasChannelUtilization ? $0.channelUtilization : nil },
                airUtilTx: metrics.flatMap { $0.hasAirUtilTx ? $0.airUtilTx : nil },
                isFavorite: info.isFavorite,
                latitudeI: position.flatMap { $0.hasLatitudeI && $0.latitudeI != 0 ? $0.latitudeI : nil },
                longitudeI: position.flatMap { $0.hasLongitudeI && $0.longitudeI != 0 ? $0.longitudeI : nil },
                altitude: position.flatMap { $0.hasAltitude ? Int($0.altitude) : nil },
                // Nothing here says when this reached the radio, so an unbelievable stamp leaves no time at all rather
                // than a wrong one.
                positionTime: position.flatMap { ifPlausible($0.time) },
                positionPrecision: position.flatMap { $0.precisionBits == 0 ? nil : Int($0.precisionBits) }
            ),
            now: currentEpochMillis()
        )
    }

    /// A `User` enum field by its protobuf name ("TBEAM", "CLIENT"), as Android stores it, rather than the Swift case
    /// name. The JSON form is the public route to those names; a field left at its default is omitted there, and that
    /// default's name is "UNSET" for the hardware model and "CLIENT" for the role.
    private static func protoName(of user: User, field: String) -> String {
        let fallback = field == "role" ? "CLIENT" : "UNSET"
        guard let json = try? user.jsonString(),
            let object = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
            let name = object[field] as? String
        else { return fallback }
        return name
    }

    /// A position the firmware broadcast in the open.
    ///
    /// Kept for nodes outside our rooms, where it is all there is. Never for a member: their phones only ever send
    /// positions sealed, so an unsealed one naming a member was put on the air by whoever holds a radio.
    private func handlePosition(packet: MeshPacket, data: DataMessage) async {
        let from = Int32(bitPattern: packet.from)
        let isMember = (try? await memberDao.isInAnyRoom(nodeNum: from)) == true
        if !TrustRules.unsealedPositionAcceptable(senderInOurRooms: isMember) {
            log.warning("dropped an unsealed position for a room member")
            return
        }
        guard let position = try? Position(serializedBytes: data.payload) else {
            return
        }
        await storePosition(
            nodeNum: from,
            position: position,
            precision: position.precisionBits == 0 ? nil : Int(position.precisionBits)
        )
    }

    /**
     * A member's position, sealed by their phone under the room's key and
     * already opened and checked by the room layer.
     */
    internal func storeSealedPosition(nodeNum: Int32, position: Position) async {
        await storePosition(nodeNum: nodeNum, position: position, precision: PositionPrecision.full)
    }

    private func storePosition(nodeNum: Int32, position: Position, precision: Int?) async {
        guard position.hasLatitudeI, position.hasLongitudeI else {
            return
        }
        let latitude = position.latitudeI
        let longitude = position.longitudeI
        if latitude == 0 && longitude == 0 {
            return
        }
        try? await nodeDao.updatePosition(
            nodeNum: nodeNum,
            latitudeI: latitude,
            longitudeI: longitude,
            altitude: position.hasAltitude ? Int(position.altitude) : nil,
            positionTime: ifPlausible(position.time) ?? currentEpochMillis(),
            positionPrecision: precision,
            groundSpeed: position.hasGroundSpeed ? Int(position.groundSpeed) : nil,
            groundTrack: position.hasGroundTrack ? Int(position.groundTrack) : nil
        )
    }

    private func handleTelemetry(packet: MeshPacket, data: DataMessage) async {
        guard let telemetry = try? Telemetry(serializedBytes: data.payload),
            case .deviceMetrics(let metrics)? = telemetry.variant
        else {
            return
        }
        try? await nodeDao.updateMetrics(
            nodeNum: Int32(bitPattern: packet.from),
            batteryLevel: metrics.hasBatteryLevel ? Int(metrics.batteryLevel) : nil,
            voltage: metrics.hasVoltage ? metrics.voltage : nil,
            channelUtilization: metrics.hasChannelUtilization ? metrics.channelUtilization : nil,
            airUtilTx: metrics.hasAirUtilTx ? metrics.airUtilTx : nil
        )
    }

    private static let publicKeySize = 32
}
