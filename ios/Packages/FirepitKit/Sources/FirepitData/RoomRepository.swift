import FirepitCrypto
import FirepitModel
import FirepitProtocol
import FirepitProtos
import FirepitTransport
import Foundation
import Security
import os

/// Why a room operation could not be carried out, in terms the UI can show.
public enum RoomError: Error, Sendable, Equatable, LocalizedError {
    case notConnected
    case noFreeSlot
    case nameTooLong(bytes: Int)
    case inviteExpired
    case inviteInvalid
    /// Firepit's own features need a Firepit room; a shared channel has none of them.
    case notAFirepitRoom
    /// The code claims a radio this one already knows, under a key that is not that radio's.
    case keyMismatch
    case alreadyInRoom
    /// Asked to invite to a room that is no longer on the radio: left, or moved off it by another app.
    case roomGone
    /// A channel read timed out; writing on regardless would rewrite a room with no key.
    case radioUnreadable

    public var message: String {
        switch self {
        case .notConnected:
            return "Connect your node first"
        case .noFreeSlot:
            return "You're in 7 rooms — leave one to create or join another"
        case .nameTooLong(let bytes):
            return "Room name is \(bytes) bytes; the radio allows \(InviteCodec.maxRoomNameBytes)"
        case .inviteExpired:
            return "This code expired — ask for a fresh one"
        case .inviteInvalid:
            return "That isn't a Firepit invite or a Meshtastic channel link"
        case .notAFirepitRoom:
            return "This is a standard Meshtastic channel, so it only does what Meshtastic does. "
                + "Create a private room for Firepit's own features."
        case .keyMismatch:
            return "This code names a radio yours already knows, but with a different key. Ask them to "
                + "show a fresh code, and check the key their screen shows."
        case .alreadyInRoom:
            return "You're already in this room"
        case .roomGone:
            return "This room isn't on your radio any more"
        case .radioUnreadable:
            return "Couldn't read the radio's channels, so nothing was changed. Try again."
        }
    }

    public var errorDescription: String? {
        message
    }
}

private enum DirectOpening {
    case read(DirectSeal.Opening)
    case noPeerKey
    case replayed
    case outOfHours
    case unreadable
}

/// Creating, inviting to, joining and leaving rooms.
///
/// A room is a Meshtastic secondary channel: its 32-byte key *is* the access
/// control, so there is nothing else to grant or revoke.
public final class RoomRepository: Sendable {
    public let link: any RadioLinking
    public let mesh: MeshRepository
    public let admin: NodeAdminClient
    public let memberDao: RoomMemberDao
    public let messageDao: MessageDao
    public let receipts: ReceiptRepository
    public let roomKeys: RoomKeyStore
    public let range: RangeRepository
    public let personCardDao: PersonCardDao
    public let phoneKeys: PhoneKeyStore
    public let peerKeyDao: PeerKeyDao
    public let pinDao: MapPinDao
    public let roomActivity: RoomActivityDao
    public let handovers: PendingHandoverDao
    public let history: RoomHistory
    public let sharingStore: SharingStore
    /// The time now, in epoch millis. Android reads the system clock directly; here it can be replaced so tests can
    /// let the handover retry interval pass.
    private let clock: @Sendable () -> Int64

    /// Positions, pins and position questions, opened from a room's seal and
    /// handed to whoever owns them. Opening is done once, here, where the room
    /// keys and the rules about them live.
    public let openedInRooms = Broadcast<OpenedInRoom>()

    /// When each member with a key still owed to them was last tried, so hearing them often costs one resend. */
    private let handoverTried = Mutex<[Int32: Int64]>([:])

    /// When each member heard still sealing under an old key was last handed the new one, apart from `handoverTried`.
    private let oldKeyRetried = Mutex<[Int32: Int64]>([:])

    /// Handovers on their way right now, counted by room and member, so a retry never runs alongside one. Counted, so
    /// whichever finishes first cannot clear the mark while another is still going.
    private let handingOver = Mutex<[MemberInRoom: Int]>([:])

    /// How often each member's radio has acknowledged a key handed to them, by room and member. A radio taking it
    /// proves nothing about their app keeping it, so the handover stays owed until the app's own word (see
    /// `settleHandover`); this only stops it being sent for ever to someone whose radio takes it every time.
    private let handoverAcks = Mutex<[MemberInRoom: Int]>([:])

    /// When each sender was last told a sealed message of theirs would not open here. */
    private let refusedAt = Mutex<[Int32: Int64]>([:])

    /// Who the room has been told is on an older build, once per room and sender while the app runs.
    private let outdatedNoticed = Mutex<Set<MemberInRoom>>([])

    /// Invites we have issued, so a join hello can be tied back to a room.
    ///
    /// Only the derived invite key is kept, never the room PSK. Entries are
    /// dropped once no hello could still plausibly arrive, which is also what
    /// stops the map growing for the life of the process.
    private let issuedInvites = Mutex<[Int32: IssuedInvite]>([:])

    /// Recent attempts per node, so one stranger cannot grind the token check. */
    private let joinAttempts = Mutex<[Int32: [Int64]]>([:])
    private let phoneKeyMutex = AsyncMutex()
    private let approvingJoins = Mutex<Set<Int32>>([])

    /// People asking to be let in, waiting on an answer from whoever is holding this phone. */
    public let pendingJoins = CurrentValue<[PendingJoin]>([])

    /// The room we have asked to join, until the answer arrives or is given up on. */
    public let awaiting = CurrentValue<AwaitedRoom?>(nil)

    private let tasks = Mutex<[Task<Void, Never>]>([])
    private let latestCard = Mutex<Card?>(nil)
    private let log = Logger(subsystem: "com.getfirepit.app", category: "FirepitRooms")

    /// Stops waiting, for when the answer never came or the reader walked away. */
    public func stopWaiting() {
        awaiting.set(nil)
    }

    /// Kept so a room joined later can be told who we are without asking the UI again. */
    private struct IssuedInvite: Sendable, Equatable {
        var roomId: Int32
        var inviteKey: Data
        var secret: Data
        var issuedAt: Int64
        /// Set once somebody has been let in on it, which spends it. */
        var usedBy: Int32?

        func withIssuedAt(_ value: Int64) -> IssuedInvite {
            IssuedInvite(roomId: roomId, inviteKey: inviteKey, secret: secret, issuedAt: value, usedBy: usedBy)
        }
    }

    private struct Card: Sendable, Equatable {
        var name: String
        var tag: String
        var colourSlot: Int?
    }

    /// Sent when nobody has chosen a name, so the card carries only the phone
    /// key. Without it, somebody who never opened the settings — typically the
    /// room's founder — could never be handed a new room key.
    private static let noName = Card(name: "", tag: "", colourSlot: nil)

    private static func randomInviteSecret() -> Data {
        var bytes = Data(count: Int(InviteCodec.inviteSecretSize))
        let ok = bytes.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, buffer.count, buffer.baseAddress!)
        }
        precondition(ok == errSecSuccess, "could not draw invite secret")
        return bytes
    }

    public init(
        link: any RadioLinking,
        mesh: MeshRepository,
        admin: NodeAdminClient,
        memberDao: RoomMemberDao,
        messageDao: MessageDao,
        receipts: ReceiptRepository,
        roomKeys: RoomKeyStore,
        range: RangeRepository,
        personCardDao: PersonCardDao,
        phoneKeys: PhoneKeyStore,
        peerKeyDao: PeerKeyDao,
        pinDao: MapPinDao,
        roomActivity: RoomActivityDao,
        handovers: PendingHandoverDao,
        history: RoomHistory,
        sharingStore: SharingStore,
        clock: @escaping @Sendable () -> Int64 = { currentEpochMillis() }
    ) {
        self.link = link
        self.mesh = mesh
        self.admin = admin
        self.memberDao = memberDao
        self.messageDao = messageDao
        self.receipts = receipts
        self.roomKeys = roomKeys
        self.range = range
        self.personCardDao = personCardDao
        self.phoneKeys = phoneKeys
        self.peerKeyDao = peerKeyDao
        self.pinDao = pinDao
        self.roomActivity = roomActivity
        self.handovers = handovers
        self.history = history
        self.sharingStore = sharingStore
        self.clock = clock
    }

    deinit {
        tasks.withLock { jobs in
            for job in jobs {
                job.cancel()
            }
            jobs.removeAll()
        }
    }

    /// Who we have seen in `roomId`. See `RoomMember` for what this can and cannot know. */
    public func observeMembers(roomId: Int32) -> AsyncStream<[RoomMember]> {
        memberDao.observeRoom(roomId: roomId)
    }

    /// Every node in any room we are in — the people this phone counts as ours. */
    public func observeGroupNodes() -> AsyncStream<Set<Int32>> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let task = Task {
                for await nodes in memberDao.observeAllNodeNums() {
                    continuation.yield(Set(nodes))
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// A Firepit room we and `nodeNum` are both in, or nil when we share none.
    ///
    /// Asking somebody's radio where it is travels inside a room and nowhere
    /// else: the question and the answer both ride the room's key, so nobody
    /// outside it learns that the question was asked or what came back.
    public func sharedRoomWith(nodeNum: Int32) async -> RoomChannel? {
        for room in rooms() {
            if roomKeys.holds(roomId: room.id),
                (try? await memberDao.nodeNumsIn(roomId: room.id).contains(nodeNum)) == true
            {
                return room
            }
        }
        return nil
    }

    /// How the people in our rooms describe themselves, by node number. */
    public func observePersonCards() -> AsyncStream<[Int32: PersonCard]> {
        personCardDao.observeAll()
    }

    /// Remembers the card without sending it, for restoring it at startup. */
    public func rememberPersonCard(name: String, tag: String, colourSlot: Int?) {
        latestCard.withLock { card in
            card = Card(name: name, tag: tag, colourSlot: colourSlot)
        }
    }

    /// Tells every room we are in who is holding this radio.
    ///
    /// Sealed per room, so it reaches the people who already share a key with us
    /// and nobody else. Nothing goes on the primary channel: on the open mesh a
    /// Firepit node looks like any other node, which is the point.
    public func sharePersonCard(name: String, tag: String, colourSlot: Int?) async {
        latestCard.withLock { card in
            card = Card(name: name, tag: tag, colourSlot: colourSlot)
        }
        await sharePersonCard()
    }

    /// Re-sends whatever was last set, for when a new room appears. */
    private func sharePersonCard() async {
        await shareCard(
            card: latestCard.withLock { $0 } ?? Self.noName,
            rooms: ChannelSlotManager.rooms(channels: mesh.channels.value))
    }

    /// Introduces us to one room, for when somebody new turns up in it. */
    private func shareCardWith(roomId: Int32) async {
        guard let room = ChannelSlotManager.findByRoomId(channels: mesh.channels.value, roomId: roomId) else {
            return
        }
        await shareCard(card: latestCard.withLock { $0 } ?? Self.noName, rooms: [room])
    }

    /// Answers a join after a random pause, so a room full of people does not
    /// reply to the same arrival at once and talk over each other on the air.
    private func greet(roomId: Int32) {
        let spread = Self.greetingSpread
        tasks.withLock { jobs in
            jobs.append(
                Task { [weak self] in
                    let delay = Int64.random(in: 0..<spread)
                    try? await Task.sleep(for: .milliseconds(delay))
                    await self?.shareCardWith(roomId: roomId)
                })
        }
    }

    private func shareCard(card: Card, rooms: [RoomChannel]) async {
        var cardProto = Meshchat_PersonCard()
        cardProto.name = OwnerName.longName(text: card.name)
        cardProto.tag = OwnerName.shortName(text: card.tag)
        cardProto.colourSlotPlusOne = UInt32(card.colourSlot.map { $0 + 1 } ?? 0)
        cardProto.phoneKey = (try? phoneKeys.publicKey()) ?? Data()
        var control = Meshchat_MeshChatControl()
        control.version = InviteCodec.version
        control.personCard = cardProto
        guard let myNodeNum = mesh.myNodeNum.value else {
            return
        }
        for room in rooms {
            guard let sealed = sealFor(roomId: room.id, myNodeNum: myNodeNum, control: control) else {
                continue
            }
            var outer = Meshchat_MeshChatControl()
            outer.sealedMessage = sealed
            guard let payload = try? outer.serializedData() else {
                continue
            }
            do {
                let packet = try MeshPacketBuilder.meshPacket(
                    to: broadcastNodeNum,
                    channel: room.index,
                    portNum: .privateApp,
                    payload: payload,
                    hopLimit: mesh.hopLimitForSending(),
                    wantAck: true,
                    priority: .background
                )
                var toRadio = ToRadio()
                toRadio.packet = packet
                try await link.send(toRadio)
            } catch {
                log.warning("could not share card")
            }
        }
        log.info("shared person card")
    }

    /// `control` sealed under this hour's key of one generation of `roomId`,
    /// ready to travel. Nil when this phone holds no such key, which means the
    /// slot is not a Firepit room.
    ///
    /// Nothing new is sealed under a key the room has moved on from; only a
    /// rotation names an older generation, and does so on purpose.
    private func sealFor(
        roomId: Int32,
        myNodeNum: Int32,
        control: Meshchat_MeshChatControl,
        generation: Int? = nil
    ) -> Meshchat_SealedMessage? {
        guard let plaintext = try? control.serializedData() else {
            return nil
        }
        return roomKeys.seal(roomId: roomId, sender: myNodeNum, plaintext: plaintext, generation: generation)
    }

    /// Seals `control` under `roomId`'s current key and puts it on the room's
    /// own slot, to everyone or to one member. False when this phone cannot
    /// seal for the room, or the radio would not take it.
    ///
    /// Always asks for an acknowledgement, whatever the payload, so the one
    /// header bit anyone can read does not tell words from receipts or pins.
    public func sendSealed(
        roomId: Int32,
        control: Meshchat_MeshChatControl,
        to: Int32 = broadcastNodeNum,
        priority: MeshPacket.Priority = .background
    ) async -> Bool {
        guard let myNodeNum = mesh.myNodeNum.value,
            let slot = ChannelSlotManager.slotOf(channels: mesh.channels.value, roomId: roomId),
            let sealed = sealFor(roomId: roomId, myNodeNum: myNodeNum, control: control)
        else {
            return false
        }
        var outer = Meshchat_MeshChatControl()
        outer.sealedMessage = sealed
        do {
            let packet = try MeshPacketBuilder.meshPacket(
                to: to,
                channel: slot,
                portNum: .privateApp,
                payload: try outer.serializedData(),
                hopLimit: mesh.hopLimitForSending(),
                wantAck: true,
                priority: priority
            )
            var toRadio = ToRadio()
            toRadio.packet = packet
            try await link.send(toRadio)
            return true
        } catch {
            log.warning("could not send to room")
            return false
        }
    }

    /// Something happened in `roomId` that a person did, for judging when it went quiet. */
    public func noteActivity(roomId: Int32) async {
        try? await roomActivity.recordActivity(roomId: roomId, now: clock())
    }

    /// Keeps a phone key per `TrustRules.shouldStorePhoneKey`, tracking which
    /// keys this phone saw checked in person.
    private struct PhoneKeyLearned: Sendable {
        var stored: Bool
        var replaced: Bool
    }

    @discardableResult
    private func learnPhoneKey(nodeNum: Int32, key: Data, source: TrustRules.PhoneKeySource) async -> PhoneKeyLearned {
        if !KeyEnvelope.isValidPublicKey(key) {
            return PhoneKeyLearned(stored: false, replaced: false)
        }
        let learned =
            (try? await phoneKeyMutex.withLock {
                let row = try? await peerKeyDao.find(nodeNum: nodeNum)
                let known = row.flatMap { Data(base64Encoded: $0.phoneKey) }
                let decision = TrustRules.shouldStorePhoneKey(
                    known: known,
                    knownInPerson: row?.inPerson == true,
                    incoming: key,
                    source: source)
                if !decision.store {
                    if known != nil && known != key {
                        log.warning("a different phone key was offered, kept the one first seen")
                    }
                    return PhoneKeyLearned(stored: false, replaced: false)
                }
                try? await peerKeyDao.upsert(
                    key: PeerKeyEntity(
                        nodeNum: nodeNum,
                        phoneKey: key.base64EncodedString(),
                        learnedAt: clock(),
                        inPerson: decision.inPerson)
                )
                return PhoneKeyLearned(stored: true, replaced: decision.replaced)
            }) ?? PhoneKeyLearned(stored: false, replaced: false)
        if learned.replaced {
            await noticeKeyChanged(nodeNum: nodeNum)
        }
        return learned
    }

    private func noticeKeyChanged(nodeNum: Int32) async {
        guard let myNodeNum = mesh.myNodeNum.value else {
            return
        }
        let cardName = (try? await personCardDao.findEntity(nodeNum: nodeNum))?.name
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let name: String
        if let cardName, !cardName.isEmpty {
            name = cardName
        } else {
            name = MeshConstants.formatNodeId(nodeNum)
        }
        try? await messageDao.save(
            message: ChatMessage(
                id: MeshPacketBuilder.randomPacketId(),
                channel: 0,
                fromNodeNum: Self.noticeNode,
                toNodeNum: nodeNum,
                text: "\(name)'s phone key changed. If they did not get a new phone, check with them in person.",
                sentAt: clock(),
                status: .received,
                isOutgoing: false
            ),
            myNodeNum: myNodeNum
        )
    }

    /// Starts listening for join and roster traffic. Safe to call once per process. */
    public func start() {
        tasks.withLock { jobs in
            guard jobs.isEmpty else {
                return
            }
            jobs.append(
                Task { [weak self] in
                    guard let self else {
                        return
                    }
                    for await message in self.link.inbound.subscribe() {
                        guard case .packet(let packet)? = message.payloadVariant else {
                            continue
                        }
                        await self.handlePacket(packet: packet)
                    }
                })
            jobs.append(
                Task { [weak self] in
                    guard let self else {
                        return
                    }
                    for await connected in self.mesh.isConnected.subscribe() {
                        if !connected {
                            continue
                        }
                        let ready = try? await withTimeoutOrNil(Self.channelsTimeout) {
                            await self.mesh.channels.subscribe().first { !$0.isEmpty }
                        }
                        if ready != nil {
                            await self.sharePersonCard()
                        }
                    }
                })
            jobs.append(
                Task { [weak self] in
                    // On the clock rather than on traffic: a quiet room has to
                    // forget its old keys as surely as a busy one.
                    while !Task.isCancelled, self != nil {
                        await self?.eraseOldKeys()
                        try? await Task.sleep(for: RoomRepository.keyEraseEvery)
                    }
                })
        }
    }

    /// Destroys every hour's key no longer needed, keeping only the generations
    /// a member still owed a rotation holds: their new key is sealed under it.
    private func eraseOldKeys() async {
        // Not knowing who is owed a key is no reason to erase every key they might need.
        guard let pending = try? await handovers.all() else {
            return
        }
        roomKeys.erase(
            owed: Set(
                pending.flatMap { record in
                    mayHold(record).map { RoomGeneration(roomId: record.roomId, generation: $0) }
                }))
    }

    /// Rooms currently provisioned on the radio, lowest slot first. */
    public func rooms() -> [RoomChannel] {
        ChannelSlotManager.rooms(channels: mesh.channels.value)
    }

    public func createRoom(name: String) async throws -> RoomChannel {
        guard let myNodeNum = mesh.myNodeNum.value else {
            throw RoomError.notConnected
        }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let nameBytes = trimmed.utf8.count
        if nameBytes > InviteCodec.maxRoomNameBytes {
            throw RoomError.nameTooLong(bytes: nameBytes)
        }
        guard let slot = ChannelSlotManager.nextFreeSlot(channels: mesh.channels.value) else {
            throw RoomError.noFreeSlot
        }
        let roomId = RoomCrypto.generateRoomId()
        _ = try roomKeys.generate(roomId: roomId)
        let psk = RoomCrypto.generatePsk()
        try await admin.setChannel(channelFor(index: slot, name: trimmed, psk: psk, roomId: roomId))
        let now = clock()
        try await memberDao.record(roomId: roomId, nodeNum: myNodeNum, now: now, invitedBy: myNodeNum)
        try await roomActivity.joined(roomId: roomId, now: now)
        return RoomChannel(
            index: slot,
            name: trimmed,
            role: .secondary,
            id: roomId,
            positionPrecision: PositionPrecision.disabled,
            kind: .firepit
        )
    }

    /// Adds a channel that other Meshtastic clients can take part in.
    ///
    /// Not a Firepit room and deliberately not sealed: a stock client cannot
    /// open our envelope, so the whole point of this is to speak the plain
    /// protocol.
    public func addMeshtasticChannel(name: String, psk: Data? = nil) async throws -> RoomChannel {
        guard mesh.myNodeNum.value != nil else {
            throw RoomError.notConnected
        }
        let preset = mesh.snapshot.value?.lora?.modemPreset
        let fallback = MeshtasticChannel.publicNameFor(preset: preset)
        let trimmed = sanitizeMeshText(name).trimmingCharacters(in: .whitespacesAndNewlines)
        let finalName = trimmed.isEmpty ? fallback : trimmed
        let nameBytes = finalName.utf8.count
        if nameBytes > InviteCodec.maxRoomNameBytes {
            throw RoomError.nameTooLong(bytes: nameBytes)
        }
        let existing = ChannelSlotManager.rooms(channels: mesh.channels.value)
            .first { $0.name == finalName && $0.kind != .firepit }
        let slot = existing?.index ?? ChannelSlotManager.nextFreeSlot(channels: mesh.channels.value)
        guard let slot else {
            throw RoomError.noFreeSlot
        }
        let channel: Channel
        if let psk {
            channel = try MeshtasticChannel.privateChannel(index: slot, name: finalName, psk: psk)
        } else {
            channel = MeshtasticChannel.publicChannel(index: slot, preset: preset)
        }
        try await admin.setChannel(channel)
        return RoomChannel(
            index: slot,
            name: finalName,
            role: .secondary,
            id: Int32(bitPattern: channel.settings.id),
            positionPrecision: PositionPrecision.disabled,
            kind: psk == nil ? .meshtasticPublic : .meshtasticPrivate
        )
    }

    /// Joins the channels in a Meshtastic share link.
    ///
    /// Reading these is one-way on purpose: Firepit issues its own invites, which
    /// carry a rotating token and a room key the radio never holds.
    public func joinMeshtasticChannels(shared: ChannelUrl.Shared) async throws -> [RoomChannel] {
        guard mesh.myNodeNum.value != nil else {
            throw RoomError.notConnected
        }
        var added: [RoomChannel] = []
        for settings in shared.channels {
            let name =
                sanitizeMeshText(settings.name).isEmpty
                ? MeshtasticChannel.publicNameFor(preset: shared.lora?.modemPreset)
                : sanitizeMeshText(settings.name)
            do {
                let key = MeshtasticChannel.isWellKnown(settings.psk) ? nil : settings.psk
                added.append(try await addMeshtasticChannel(name: name, psk: key))
            } catch {
                log.warning("could not add channel from link")
            }
        }
        if added.isEmpty {
            throw RoomError.noFreeSlot
        }
        return added
    }

    /// Builds a QR invite for `roomId`, valid for the current rotation window.
    ///
    /// Carries no key material. A photograph of the result is a request to be
    /// let in, which the inviter still has to approve, and which stops being
    /// accepted a few seconds later.
    public func buildInvite(roomId: Int32, nowMillis: Int64 = currentEpochMillis()) async throws -> Meshchat_Invite {
        guard let myNodeNum = mesh.myNodeNum.value else {
            throw RoomError.notConnected
        }
        guard let room = ChannelSlotManager.findByRoomId(channels: mesh.channels.value, roomId: roomId) else {
            throw RoomError.roomGone
        }
        guard let settings = await admin.getChannel(index: room.index)?.settings else {
            throw RoomError.notConnected
        }
        let psk = settings.psk
        let generation: Int32 = Int32(RoomKeyStore.first)
        let inviteKey = RoomCrypto.inviteKey(roomPsk: psk, roomId: roomId, generation: generation)
        let window = RoomCrypto.windowFor(epochMillis: nowMillis)
        forgetStaleInvites(nowMillis: nowMillis)
        let existingInvite = issuedInvites.withLock { ledger in
            ledger.first(where: { $0.value.roomId == roomId && $0.value.usedBy == nil })
        }
        let inviteId = existingInvite?.key ?? RoomCrypto.generateRoomId()
        let issued = existingInvite?.value.withIssuedAt(nowMillis)
            ?? IssuedInvite(roomId: roomId, inviteKey: inviteKey, secret: Self.randomInviteSecret(), issuedAt: nowMillis)
        issuedInvites.withLock { ledger in
            ledger[inviteId] = issued
        }
        var invite = Meshchat_Invite()
        invite.version = InviteCodec.version
        invite.roomID = UInt32(bitPattern: roomId)
        invite.roomName = room.name
        invite.generation = UInt32(bitPattern: generation)
        var inviter = Meshchat_Inviter()
        inviter.nodeNum = UInt32(bitPattern: myNodeNum)
        if let user = mesh.myUser {
            inviter.user = user
        }
        invite.inviter = inviter
        invite.inviteID = UInt32(bitPattern: inviteId)
        invite.issuedAt = UInt32(nowMillis / 1000)
        invite.window = UInt32(bitPattern: window)
        invite.token = RoomCrypto.token(inviteKey: issued.inviteKey, inviterNodeNum: myNodeNum, window: window)
        invite.secret = issued.secret
        var lora = Meshchat_LoRaProfile()
        lora.meshMode = range.mode.value.wire
        invite.lora = lora
        return invite
    }

    /// Asks to be let into the invited room.
    ///
    /// Nothing is written to the radio here: the code carries no keys, so there
    /// is no room to write until the inviter answers with a `RoomGrant`.
    public func joinRoom(invite: Meshchat_Invite, nowMillis: Int64 = currentEpochMillis()) async throws {
        guard mesh.myNodeNum.value != nil else {
            throw RoomError.notConnected
        }
        try verify(invite: invite, nowMillis: nowMillis)
        let inviter = Int32(bitPattern: invite.inviter.nodeNum)
        let invitedRoomId = Int32(bitPattern: invite.roomID)
        if ChannelSlotManager.findByRoomId(channels: mesh.channels.value, roomId: invitedRoomId) != nil {
            if try await memberDao.findEntity(roomId: invitedRoomId, nodeNum: inviter) == nil {
                throw RoomError.alreadyInRoom
            }
        } else if ChannelSlotManager.nextFreeSlot(channels: mesh.channels.value) == nil {
            throw RoomError.noFreeSlot
        }
        try await range.alignWith(choice: RangeMode.of(invite.lora.meshMode))
        awaiting.set(
            AwaitedRoom(
                roomId: Int32(bitPattern: invite.roomID),
                roomName: invite.roomName,
                inviteId: Int32(bitPattern: invite.inviteID),
                inviter: inviter,
                ownFingerprint: ownFingerprint(),
                inviteSecret: invite.secret
            )
        )
        try await askToJoin(invite: invite)
    }

    /// This radio's key and this phone's key as one line, short enough to read
    /// aloud to the person being asked. Their screen shows the same line for
    /// the hello they received. See `KeyFingerprint.ofJoin`.
    public func ownFingerprint() -> String? {
        guard let radioKey = mesh.myUser?.publicKey,
            radioKey.count == Self.publicKeySize,
            let phoneKey = try? phoneKeys.publicKey()
        else {
            return nil
        }
        return KeyFingerprint.ofJoin(radioKey: radioKey, phoneKey: phoneKey)
    }

    /// Sends the hello, encrypted to the key in the invite.
    ///
    /// Straight to PKI with no NodeInfo broadcast first: the inviter's public
    /// key came with the code, so nothing has to propagate before we can speak
    /// privately.
    private func askToJoin(invite: Meshchat_Invite) async throws {
        let inviter = invite.inviter
        let publicKey = inviter.user.publicKey
        if publicKey.count != Self.publicKeySize {
            throw RoomError.inviteInvalid
        }
        guard let mine = mesh.myUser?.publicKey,
            mine.count == Self.publicKeySize
        else {
            throw RoomError.notConnected
        }
        let radioKnows = mesh.snapshot.value?.nodes[Int32(bitPattern: inviter.nodeNum)]?.user.publicKey
        if TrustRules.contactFor(knownKey: radioKnows, codeKey: publicKey) == .mismatch {
            awaiting.set(nil)
            throw RoomError.keyMismatch
        }
        do {
            try await admin.addContact(nodeNum: Int32(bitPattern: inviter.nodeNum), user: inviter.user)
        } catch {
            log.warning("could not add inviter as contact")
        }
        var hello = Meshchat_JoinHello()
        hello.inviteID = invite.inviteID
        hello.token = invite.token
        hello.generation = invite.generation
        hello.appVersion = InviteCodec.version
        hello.joinerKey = mine
        hello.phoneKey = (try? phoneKeys.publicKey()) ?? Data()
        var control = Meshchat_MeshChatControl()
        control.version = InviteCodec.version
        control.joinHello = hello
        let packet = try MeshPacketBuilder.meshPacket(
            to: Int32(bitPattern: inviter.nodeNum),
            channel: 0,
            portNum: .privateApp,
            payload: try control.serializedData(),
            wantAck: true,
            pkiEncrypted: true,
            publicKey: publicKey
        )
        var toRadio = ToRadio()
        toRadio.packet = packet
        try await link.send(toRadio)
    }

    /// Moves the room to new keys and leaves `remove` behind.
    ///
    /// Nothing can take a key back from someone who already has it, so removal
    /// is really everyone else moving on without them.
    public func rotateRoom(roomId: Int32, remove: Set<Int32> = []) async throws -> RotationResult {
        guard let myNodeNum = mesh.myNodeNum.value else {
            throw RoomError.notConnected
        }
        guard let room = ChannelSlotManager.findByRoomId(channels: mesh.channels.value, roomId: roomId) else {
            throw RoomError.inviteInvalid
        }
        guard roomKeys.canSeal(roomId: roomId) else {
            throw RoomError.notAFirepitRoom
        }
        let previous = roomKeys.generationOf(roomId: roomId)
        let keys = NewKeys(
            roomId: roomId,
            roomName: room.name,
            generation: previous + 1,
            psk: RoomCrypto.generatePsk(),
            firepitKey: HourKey(
                hour: RoomRatchet.hourOf(unixMillis: roomKeys.time.wallMillis()), key: RoomCipher.generateKey())
        )
        let stillOwed = (try? await handovers.forRoom(roomId: roomId)) ?? []
        let owed: [PendingHandoverEntity] = try await history.whileRearranging {
            await announceRotation(
                slot: room.index, roomId: roomId, myNodeNum: myNodeNum,
                previous: previous, generation: keys.generation)
            try await admin.setChannel(channelFor(index: room.index, name: room.name, psk: keys.psk, roomId: roomId))
            try roomKeys.remember(roomId: roomId, key: keys.firepitKey, generation: keys.generation)
            for member in remove {
                try await memberDao.remove(roomId: roomId, nodeNum: member)
            }
            let now = clock()
            let earlier = Dictionary(uniqueKeysWithValues: stillOwed.map { ($0.nodeNum, $0) })
            let records = try await memberDao.nodeNumsIn(roomId: roomId)
                .filter { $0 != myNodeNum }
                .map { member -> PendingHandoverEntity in
                    let old = earlier[member]
                    let removed = (old?.removedNodes() ?? []) + Array(remove)
                    return PendingHandoverEntity(
                        roomId: roomId,
                        nodeNum: member,
                        generation: keys.generation,
                        heldGeneration: old?.heldGeneration ?? previous,
                        removed: unique(removed).map(String.init).joined(separator: ","),
                        createdAt: now,
                        lastTriedAt: now
                    )
                }
            // Written before anything stale is cleared, never the other way round: the hourly erase keeps whichever
            // generation a record says a member still holds, and must never catch a moment with none.
            for record in records {
                try await handovers.upsert(handover: record)
            }
            let owedNodes = Set(records.map(\.nodeNum))
            for stale in try await handovers.forRoom(roomId: roomId) where !owedNodes.contains(stale.nodeNum) {
                try await handovers.delete(roomId: roomId, nodeNum: stale.nodeNum)
            }
            await noticeInRoom(slot: room.index, text: rotationNotice(removed: remove), roomId: roomId)
            return records
        }
        // Just tried, so their radio's own acknowledgement of it does not set off a retry; and counted afresh for the
        // key being handed now.
        let handedAt = clock()
        handoverTried.withLock { tried in
            for record in owed {
                tried[record.nodeNum] = handedAt
            }
        }
        handoverAcks.withLock { acks in
            for record in owed {
                acks.removeValue(forKey: MemberInRoom(roomId: roomId, nodeNum: record.nodeNum))
            }
        }
        // Sent a moment apart and awaited together: one member out of range must not hold up everyone else's key for
        // the length of a timeout.
        let inFlight = owed.map { MemberInRoom(roomId: roomId, nodeNum: $0.nodeNum) }
        handingOver.withLock { counts in
            for member in inFlight {
                counts[member, default: 0] += 1
            }
        }
        defer {
            for member in inFlight {
                doneHandingOver(member)
            }
        }
        let reached = await withTaskGroup(of: Int32?.self) { group in
            for (order, record) in owed.enumerated() {
                group.addTask {
                    try? await Task.sleep(for: .seconds(Self.handoverSpacingSeconds * order))
                    return await self.handOver(record: record, keys: keys) ? record.nodeNum : nil
                }
            }
            var reached = Set<Int32>()
            for await member in group {
                if let member {
                    reached.insert(member)
                }
            }
            return reached
        }
        // Still owed until their app seals something under the new key.
        handoverAcks.withLock { acks in
            for member in reached {
                acks[MemberInRoom(roomId: roomId, nodeNum: member), default: 0] += 1
            }
        }
        let keeping = Set(owed.map(\.nodeNum))
        return RotationResult(generation: keys.generation, reached: reached, missed: keeping.subtracting(reached))
    }

    /// The keys a rotation moves a room to, before they are sealed to anybody.
    private struct NewKeys: Sendable {
        var roomId: Int32
        var roomName: String
        var generation: Int
        var psk: Data
        /// One hour's key: whoever is handed it reads from that hour on, and nothing before.
        var firepitKey: HourKey
    }

    private func removedNodes(_ record: PendingHandoverEntity) -> [Int32] {
        record.removed.split(separator: ",").compactMap { Int32($0) }
    }

    /// Hands one member the new keys, and says whether their radio confirmed it.
    ///
    /// The new room key is sealed to their phone, so the radio in between cannot
    /// read it, and the whole rotation is sealed under the key they hold now.
    private func handOver(record: PendingHandoverEntity, keys: NewKeys) async -> Bool {
        let member = record.nodeNum
        guard let myNodeNum = mesh.myNodeNum.value,
            let radioKey = await mesh.publicKeyOf(nodeNum: member),
            let phoneText = (try? await peerKeyDao.find(nodeNum: member))?.phoneKey,
            let phoneKey = Data(base64Encoded: phoneText),
            KeyEnvelope.isValidPublicKey(phoneKey)
        else {
            return false
        }
        if TrustRules.contactFor(knownKey: mesh.radioKeyOf(nodeNum: member), codeKey: radioKey) == .mismatch {
            return false
        }
        if let user = await mesh.contactFor(nodeNum: member), user.publicKey == radioKey {
            try? await admin.addContact(nodeNum: member, user: user)
        }
        var rotation = Meshchat_KeyRotation()
        rotation.roomID = UInt32(bitPattern: keys.roomId)
        rotation.generation = UInt32(keys.generation)
        rotation.roomPsk = keys.psk
        rotation.roomName = keys.roomName
        rotation.removed = removedNodes(record).map { UInt32(bitPattern: $0) }
        guard
            let sealedKey = try? KeyEnvelope.seal(
                recipient: phoneKey,
                secret: keys.firepitKey.key,
                context: KeyEnvelope.contextOf(
                    roomId: keys.roomId,
                    generation: Int32(keys.generation),
                    recipientNodeNum: member,
                    hour: Int32(keys.firepitKey.hour)
                )
            )
        else {
            return false
        }
        rotation.sealedKey = sealedKey
        rotation.keyHour = UInt32(keys.firepitKey.hour)
        var inner = Meshchat_MeshChatControl()
        inner.version = InviteCodec.version
        inner.keyRotation = rotation
        // Sealed under each generation they might hold, newest first: they only accept it under the one they hold
        // now, and when a rotation came while an earlier key was on its way, that is not known here.
        var confirmed = false
        for held in mayHold(record).reversed() {
            guard
                let sealed = sealFor(roomId: keys.roomId, myNodeNum: myNodeNum, control: inner, generation: held)
            else {
                continue
            }
            var outer = Meshchat_MeshChatControl()
            outer.sealedMessage = sealed
            guard let payload = try? outer.serializedData(), payload.count <= Self.pkiPayloadBudget else {
                return false
            }
            guard
                let packet = try? MeshPacketBuilder.meshPacket(
                    to: member,
                    channel: 0,
                    portNum: .privateApp,
                    payload: payload,
                    hopLimit: mesh.hopLimitForSending(),
                    wantAck: true,
                    pkiEncrypted: true,
                    publicKey: radioKey
                )
            else {
                continue
            }
            if await mesh.sendAwaitingAck(packet: packet, from: member, timeout: Self.handoverAckTimeout) {
                confirmed = true
            }
        }
        return confirmed
    }

    /// Every generation a member owed `record` might be holding: the one they were last seen sealing under, up to the
    /// one before the key they are owed. Usually just the first; more when a rotation came while an earlier key was on
    /// its way to them.
    private func mayHold(_ record: PendingHandoverEntity) -> Range<Int> {
        record.heldGeneration..<max(record.heldGeneration, record.generation)
    }

    /// Tries again to hand `nodeNum` a key a rotation could not deliver.
    ///
    /// Hearing anything from them means they are back in range. At most once
    /// every retry interval per member, however chatty they are.
    ///
    /// Once their radio has taken a key `maxUnconfirmedHandovers` times, only `stillOnOldKey` sends it again: them
    /// sealing under the key they had is proof the new one never reached their app.
    private func retryHandoversTo(nodeNum: Int32, stillOnOldKey: Bool = false) {
        let now = clock()
        // Proof they lack the key keeps its own interval, so ordinary traffic heard just before it cannot swallow the
        // one retry that would help.
        let throttle = stillOnOldKey ? oldKeyRetried : handoverTried
        let shouldTry = throttle.withLock { tried -> Bool in
            if let last = tried[nodeNum], now - last < Self.handoverRetryMillis {
                return false
            }
            tried[nodeNum] = now
            return true
        }
        if !shouldTry {
            return
        }
        // And the ordinary retry the same packet sets off afterwards has nothing left to do.
        if stillOnOldKey {
            handoverTried.withLock { $0[nodeNum] = now }
        }
        tasks.withLock { jobs in
            jobs.append(
                Task { [weak self] in
                    guard let self else {
                        return
                    }
                    let owed = (try? await self.handovers.forNode(nodeNum: nodeNum)) ?? []
                    for record in owed {
                        let acked = self.handoverAcks.withLock {
                            $0[MemberInRoom(roomId: record.roomId, nodeNum: nodeNum)] ?? 0
                        }
                        if stillOnOldKey || acked < Self.maxUnconfirmedHandovers {
                            await self.retryHandover(owed: record, now: now)
                        }
                    }
                })
        }
    }

    private func retryHandover(owed: PendingHandoverEntity, now: Int64) async {
        let member = MemberInRoom(roomId: owed.roomId, nodeNum: owed.nodeNum)
        let first = handingOver.withLock { counts -> Bool in
            if counts[member] != nil {
                return false
            }
            counts[member] = 1
            return true
        }
        guard first else {
            return
        }
        defer { doneHandingOver(member) }
        await retryHandoverNow(owed: owed, now: now)
    }

    private func doneHandingOver(_ member: MemberInRoom) {
        handingOver.withLock { counts in
            if let count = counts[member], count > 1 {
                counts[member] = count - 1
            } else {
                counts.removeValue(forKey: member)
            }
        }
    }

    private func retryHandoverNow(owed: PendingHandoverEntity, now: Int64) async {
        let memberStillPresent = (try? await memberDao.findEntity(roomId: owed.roomId, nodeNum: owed.nodeNum)) != nil
        let stillOwed = owed.generation == roomKeys.generationOf(roomId: owed.roomId) && memberStillPresent
        if !stillOwed {
            // This record only: a later rotation's, written meanwhile, stays.
            try? await handovers.deleteUpTo(roomId: owed.roomId, nodeNum: owed.nodeNum, generation: owed.generation)
            return
        }
        guard let room = ChannelSlotManager.findByRoomId(channels: mesh.channels.value, roomId: owed.roomId),
            let psk = await admin.getChannel(index: room.index)?.settings.psk,
            psk.count == RoomCrypto.pskSize,
            // This hour's key, not the one the rotation started with: they read
            // from when they are handed it, as anybody joining would.
            let key = roomKeys.currentKey(roomId: owed.roomId, generation: owed.generation)
        else {
            return
        }
        let keys = NewKeys(
            roomId: owed.roomId, roomName: room.name, generation: owed.generation,
            psk: psk, firepitKey: key)
        let handed = await handOver(record: owed, keys: keys)
        // Only while still owed as tried: their app may have settled it while this was in flight, and a settled
        // handover must stay settled.
        let touched =
            (try? await handovers.touch(
                roomId: owed.roomId, nodeNum: owed.nodeNum, generation: owed.generation, at: now)) ?? 0
        if handed, touched > 0 {
            handoverAcks.withLock { $0[MemberInRoom(roomId: owed.roomId, nodeNum: owed.nodeNum), default: 0] += 1 }
            log.info("handed a member the key for a room again; waiting for their app to use it")
        }
    }

    /// What a member sealing under `generation` of `roomId` says about a key still owed to them: under the key they
    /// were handed, or a later one, their app has it and the handover is done; under an older one it never arrived.
    private func settleHandover(roomId: Int32, member: Int32, generation: Int) async {
        guard let owed = (try? await handovers.forNode(nodeNum: member))?.first(where: { $0.roomId == roomId }) else {
            return
        }
        if generation >= owed.generation {
            let cleared =
                (try? await handovers.deleteUpTo(roomId: roomId, nodeNum: member, generation: generation)) ?? 0
            if cleared > 0 {
                _ = handoverAcks.withLock { $0.removeValue(forKey: MemberInRoom(roomId: roomId, nodeNum: member)) }
                log.info("a member confirmed the key for a room")
            }
        } else {
            retryHandoversTo(nodeNum: member, stillOnOldKey: true)
        }
    }

    /// Tells the room it is moving on, while our radio still holds the old
    /// channel key — so this goes out on it — sealed under the old room key.
    private func announceRotation(
        slot: Int,
        roomId: Int32,
        myNodeNum: Int32,
        previous: Int,
        generation: Int
    ) async {
        var event = Meshchat_RosterEvent()
        event.kind = .keyRotated
        event.nodeNum = UInt32(bitPattern: myNodeNum)
        event.generation = UInt32(generation)
        var control = Meshchat_MeshChatControl()
        control.version = InviteCodec.version
        control.rosterEvent = event
        guard let sealed = sealFor(roomId: roomId, myNodeNum: myNodeNum, control: control, generation: previous) else {
            return
        }
        var outer = Meshchat_MeshChatControl()
        outer.sealedMessage = sealed
        do {
            let packet = try MeshPacketBuilder.meshPacket(
                to: broadcastNodeNum,
                channel: slot,
                portNum: .privateApp,
                payload: try outer.serializedData(),
                hopLimit: mesh.hopLimitForSending(),
                wantAck: true,
                priority: .reliable
            )
            var toRadio = ToRadio()
            toRadio.packet = packet
            try await link.send(toRadio)
        } catch {
            log.warning("could not announce rotation")
        }
    }

    /// Applies a rotation somebody else performed. See `TrustRules.rotationAcceptable`. */
    private func handleKeyRotation(
        packet: MeshPacket,
        rotation: Meshchat_KeyRotation,
        sealedRoomId: Int32?,
        sealedGeneration: Int?,
        myNodeNum: Int32
    ) async {
        let roomId = Int32(bitPattern: rotation.roomID)
        let acceptable = TrustRules.rotationAcceptable(
            sealedUnderRoom: sealedRoomId,
            sealedUnderGeneration: sealedGeneration.map(Int32.init),
            rotationRoom: roomId,
            rotationGeneration: Int32(bitPattern: rotation.generation),
            currentGeneration: Int32(roomKeys.generationOf(roomId: roomId)),
            privatelyToUs: packet.pkiEncrypted && Int32(bitPattern: packet.to) == myNodeNum,
            senderIsMember: (try? await memberDao.findEntity(
                roomId: roomId,
                nodeNum: Int32(bitPattern: packet.from)
            )) != nil
        )
        if !acceptable {
            return
        }
        let psk = rotation.roomPsk
        let firepitKey = phoneKeys.open(
            sealed: rotation.sealedKey,
            context: KeyEnvelope.contextOf(
                roomId: roomId,
                generation: Int32(bitPattern: rotation.generation),
                recipientNodeNum: myNodeNum,
                hour: Int32(bitPattern: rotation.keyHour)
            )
        )
        guard psk.count == RoomCrypto.pskSize,
            let firepitKey,
            firepitKey.count == RoomCipher.keySize,
            let slot = ChannelSlotManager.slotOf(channels: mesh.channels.value, roomId: roomId)
        else {
            return
        }
        try? await admin.setChannel(channelFor(index: slot, name: rotation.roomName, psk: psk, roomId: roomId))
        try? roomKeys.remember(
            roomId: roomId, key: HourKey(hour: Int(Int32(bitPattern: rotation.keyHour)), key: firepitKey),
            generation: Int(rotation.generation))
        for removed in rotation.removed {
            try? await memberDao.remove(roomId: roomId, nodeNum: Int32(bitPattern: removed))
        }
        mesh.refreshRoomKinds()
        let removed = Set(rotation.removed.map { Int32(bitPattern: $0) })
        await noticeInRoom(slot: slot, text: rotationNotice(removed: removed), roomId: roomId)
        // Something sealed under the new key, so whoever handed it over knows our app has it, not only our radio.
        await shareCardWith(roomId: roomId)
    }

    /// Says who is on a build from before hourly keys, rather than leave their messages failing to open without a
    /// word. Once per room and sender.
    private func noticeOutdated(roomId: Int32, sender: Int32) async {
        let first = outdatedNoticed.withLock { $0.insert(MemberInRoom(roomId: roomId, nodeNum: sender)).inserted }
        guard first else {
            return
        }
        log.warning("sealed payload from \(sender) is from a build before hourly keys")
        guard let slot = ChannelSlotManager.slotOf(channels: mesh.channels.value, roomId: roomId) else {
            return
        }
        await noticeInRoom(slot: slot, text: outdatedNotice(sender: sender), roomId: roomId)
    }

    private func outdatedNotice(sender: Int32) -> String {
        "\(MeshConstants.formatNodeId(sender)) is using an older version of Firepit. "
            + "Their messages can't be opened here until they update."
    }

    private func rotationNotice(removed: Set<Int32>) -> String {
        if removed.isEmpty {
            return "The room's key was changed. Everyone still here has the new one."
        }
        if removed.count == 1, let first = removed.first {
            return "\(MeshConstants.formatNodeId(first)) was removed. "
                + "The room's key was changed, so they cannot read anything from now on."
        }
        return "\(removed.count) people were removed. "
            + "The room's key was changed, so they cannot read anything from now on."
    }

    /// A line in the room that nobody said.
    ///
    /// Stored like a message so it survives a restart and sits in the history at
    /// the moment it happened, with no sender so the UI can tell it apart.
    private func noticeInRoom(slot: Int, text: String, roomId: Int32) async {
        guard let myNodeNum = mesh.myNodeNum.value else {
            return
        }
        try? await messageDao.save(
            message: ChatMessage(
                id: MeshPacketBuilder.randomPacketId(),
                channel: slot,
                fromNodeNum: Self.noticeNode,
                toNodeNum: broadcastNodeNum,
                text: text,
                sentAt: clock(),
                status: .received,
                isOutgoing: false,
                roomId: roomId
            ),
            myNodeNum: myNodeNum
        )
    }

    /// Frees a slot and closes the gap so the channels stay consecutive.
    ///
    /// `slot` names the channel exactly: a Meshtastic channel has no id of its
    /// own, so two of them both answer to `roomId` 0, and only the slot says
    /// which is meant.
    public func leaveRoom(roomId: Int32, slot: Int? = nil) async throws {
        try await history.whileRearranging {
            let channels = ChannelSlotManager.rooms(channels: mesh.channels.value)
            let leaving: RoomChannel?
            if let slot {
                leaving = channels.first { $0.index == slot && $0.id == roomId }
            } else {
                leaving = channels.first { $0.id == roomId && roomId != 0 }
            }
            guard let leaving else {
                throw RoomError.inviteInvalid
            }
            let plan = try await planTakingOff(slot: leaving.index)
            try await messageDao.deleteUnfiled(slot: leaving.index)
            try await pinDao.deleteUnfiled(slot: leaving.index)
            if roomId != 0 {
                try await messageDao.deleteRoom(roomId: roomId)
                try await pinDao.deleteRoom(roomId: roomId)
            }
            for write in plan.writes {
                try await admin.setChannel(write)
            }
            try await followSlots(moves: plan.moves)
            if roomId == 0 {
                return
            }
            try await memberDao.deleteRoom(roomId: roomId)
            try roomKeys.forget(roomId: roomId)
            try await roomActivity.forget(roomId: roomId)
            try await handovers.deleteRoom(roomId: roomId)
            issuedInvites.withLock { ledger in
                ledger = ledger.filter { $0.value.roomId != roomId }
            }
            if sharingStore.deadline.value?.roomId == roomId {
                sharingStore.clear()
            }
        }
    }

    /// Takes every Firepit room off the connected radio and puts back the
    /// primary channel it came with, for a radio that is being given away,
    /// lent, or retired.
    public func removeFirepitFromRadio() async throws {
        guard mesh.myNodeNum.value != nil else {
            throw RoomError.notConnected
        }
        try await history.whileRearranging {
            let rooms = ChannelSlotManager.rooms(channels: mesh.channels.value)
                .filter { $0.id != 0 && ($0.kind == .firepit || $0.kind.isStalledRoom) }
                .sorted { $0.index > $1.index }
            for room in rooms {
                let plan = try await planTakingOff(slot: room.index)
                for write in plan.writes {
                    try await admin.setChannel(write)
                }
                try await followSlots(moves: plan.moves)
            }
        }
        try await range.keepPublic()
    }

    /// Moves every room `nodeNum` is in to new keys without it, for a radio
    /// that is lost or in somebody else's hands.
    public func removeFromAllRooms(nodeNum: Int32) async throws -> Int {
        var rooms: [RoomChannel] = []
        for room in ChannelSlotManager.rooms(channels: mesh.channels.value) where room.kind == .firepit {
            if (try? await memberDao.findEntity(roomId: room.id, nodeNum: nodeNum)) != nil {
                rooms.append(room)
            }
        }
        for room in rooms {
            _ = try await rotateRoom(roomId: room.id, remove: [nodeNum])
        }
        return rooms.count
    }

    /// The slot writes that free one slot and close the gap, and which slots move where. */
    private struct TakeOff: Sendable {
        var writes: [Channel]
        var moves: [(Int, Int)]
    }

    /// Works out, reading every slot that moves, how to free `slot` and close
    /// the gap. Changes nothing, so a read that fails stops everything before it
    /// starts.
    private func planTakingOff(slot: Int) async throws -> TakeOff {
        let channels = mesh.channels.value
        let moves = ChannelSlotManager.slotMovesForLeavingSlot(channels: channels, slot: slot)
        let movingFrom = Dictionary(uniqueKeysWithValues: moves.map { ($0.1, $0.0) })
        let slotWrites = ChannelSlotManager.writesForLeavingSlot(channels: channels, slot: slot)
        let writes = try await slotWrites.asyncMap { write in
            guard let channel = write.channel else {
                var disabled = Channel()
                disabled.index = Int32(write.index)
                disabled.role = .disabled
                disabled.settings = ChannelSettings()
                return disabled
            }
            guard let source = movingFrom[write.index],
                let psk = await admin.getChannel(index: source)?.settings.psk
            else {
                throw RoomError.radioUnreadable
            }
            if channel.kind == .firepit && psk.count != RoomCrypto.pskSize {
                throw RoomError.radioUnreadable
            }
            return channelFor(index: write.index, name: channel.name, psk: psk, roomId: channel.id)
        }
        return TakeOff(writes: writes, moves: moves)
    }

    /// Moves what is stored against a slot but filed under no room — a
    /// Meshtastic channel's history and pins — to where that channel went.
    private func followSlots(moves: [(Int, Int)]) async throws {
        for (from, to) in moves {
            try await messageDao.moveUnfiled(from: from, to: to)
            try await pinDao.moveUnfiled(from: from, to: to)
        }
    }

    /// What a scanner can check before asking.
    ///
    /// Only the window: the token is an HMAC under the room's key, and this code
    /// no longer carries one. Proving it is the inviter's job.
    public func verify(invite: Meshchat_Invite, nowMillis: Int64) throws {
        let inviter = invite.inviter
        if inviter.nodeNum == 0 {
            throw RoomError.inviteInvalid
        }
        if invite.secret.count != InviteCodec.inviteSecretSize {
            throw RoomError.inviteInvalid
        }
        if !InviteCodec.isTimeBound(invite) {
            throw RoomError.inviteExpired
        }
        let drift = abs(Int32(bitPattern: invite.window) - RoomCrypto.windowFor(epochMillis: nowMillis))
        if drift > RoomCrypto.windowTolerance {
            throw RoomError.inviteExpired
        }
    }

    /// The Firepit room a slot carries, or nil when it is an ordinary
    /// Meshtastic channel.
    ///
    /// Holding the room's key is what makes it ours, and it is the gate on every
    /// Firepit-only behaviour.
    private func firepitRoomFor(channel: Int) -> Int32? {
        guard let roomId = mesh.roomIdForChannel(channel),
            roomKeys.holds(roomId: roomId)
        else {
            return nil
        }
        return roomId
    }

    private func handlePacket(packet: MeshPacket) async {
        guard case .decoded(let data)? = packet.payloadVariant,
            let myNodeNum = mesh.myNodeNum.value
        else {
            return
        }
        let from = Int32(bitPattern: packet.from)
        if from == myNodeNum {
            return
        }
        // Anything at all from somebody a new key never reached means they are back in range: try them again. After
        // the packet, so one that confirms the key is counted first.
        defer { retryHandoversTo(nodeNum: from) }
        if data.portnum == .privateApp {
            await handleControl(
                packet: packet, payload: data.payload, myNodeNum: myNodeNum,
                authenticated: packet.pkiEncrypted)
        }
        if data.portnum == .textMessageApp,
            Int32(bitPattern: packet.to) == myNodeNum,
            packet.pkiEncrypted,
            // A reaction shows no ticks, so it gets no receipt.
            !(data.emoji != 0 && data.replyID != 0)
        {
            await receipts.received(channel: Int(packet.channel), messageId: Int32(bitPattern: packet.id), peer: from)
        }
    }

    private func handleControl(
        packet: MeshPacket,
        payload: Data,
        myNodeNum: Int32,
        authenticated: Bool,
        sealedRoomId: Int32? = nil,
        sealedGeneration: Int? = nil
    ) async {
        guard let control = try? Meshchat_MeshChatControl(serializedBytes: payload) else {
            return
        }
        let sealedUnderCurrent = sealedRoomId != nil && sealedGeneration == roomKeys.generationOf(roomId: sealedRoomId!)
        switch control.payload {
        case .joinHello(let hello):
            await handleJoinHello(packet: packet, hello: hello, myNodeNum: myNodeNum)
        case .roomGrant(let grant):
            if packet.pkiEncrypted {
                await handleRoomGrant(packet: packet, grant: grant, myNodeNum: myNodeNum)
            }
        case .rosterEvent(let event):
            if let sealedRoomId, sealedUnderCurrent {
                await handleRosterEvent(packet: packet, event: event, sealedRoomId: sealedRoomId)
            }
        case .rosterSync(let sync):
            await handleRosterSync(
                packet: packet, sync: sync, myNodeNum: myNodeNum,
                sealedRoomId: sealedRoomId, sealedUnderCurrent: sealedUnderCurrent)
        case .sealedMessage(let sealed):
            await handleSealed(packet: packet, sealed: sealed, myNodeNum: myNodeNum)
        case .sealedDirect(let direct):
            await handleSealedDirect(packet: packet, direct: direct, myNodeNum: myNodeNum)
        case .sealedDirectRefused(let refused):
            await handleDirectRefused(packet: packet, refused: refused, myNodeNum: myNodeNum)
        case .receipt(let receipt):
            if authenticated, sealedRoomId != nil {
                await receipts.handle(from: Int32(bitPattern: packet.from), receipt: receipt)
            }
        case .keyRotation(let rotation):
            await handleKeyRotation(
                packet: packet, rotation: rotation, sealedRoomId: sealedRoomId,
                sealedGeneration: sealedGeneration, myNodeNum: myNodeNum)
        case .roomText(let room):
            if let sealedRoomId, firepitRoomFor(channel: Int(packet.channel)) == sealedRoomId {
                await mesh.saveSealedText(
                    packet: packet,
                    text: room.text,
                    replyId: room.replyID == 0 ? nil : Int32(bitPattern: room.replyID),
                    roomId: sealedRoomId,
                    emoji: room.emoji == 0 ? nil : Int(room.emoji)
                )
                // A reaction shows no ticks, so it gets no receipt.
                if room.emoji == 0 || room.replyID == 0 {
                    await receipts.received(channel: Int(packet.channel), messageId: Int32(bitPattern: packet.id))
                }
                await noteActivity(roomId: sealedRoomId)
            }
        case .personCard(let card):
            if sealedUnderCurrent {
                await learnPhoneKey(nodeNum: Int32(bitPattern: packet.from), key: card.phoneKey, source: .announced)
                let name = sanitizeMeshText(card.name)
                let tag = sanitizeMeshText(card.tag)
                if name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    && tag.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                {
                    try? await personCardDao.forget(nodeNum: Int32(bitPattern: packet.from))
                } else {
                    try? await personCardDao.upsert(
                        card: PersonCardEntity(
                            nodeNum: Int32(bitPattern: packet.from),
                            name: name,
                            tag: tag,
                            colourSlot: card.colourSlotPlusOne > 0 ? Int(card.colourSlotPlusOne - 1) : nil,
                            updatedAt: clock()
                        )
                    )
                }
            }
        case .position, .pin, .positionQuery:
            if let sealedRoomId {
                openedInRooms.send(
                    OpenedInRoom(
                        packet: packet,
                        roomId: sealedRoomId,
                        sealedUnderCurrent: sealedUnderCurrent,
                        onItsSlot: firepitRoomFor(channel: Int(packet.channel)) == sealedRoomId,
                        control: control
                    )
                )
            }
        case .liveLocationRequest, .none:
            break
        }
    }

    /// Open it and treat what is inside as if it had arrived in the clear. The
    /// key is the room's, so a radio relaying this cannot do the same, and
    /// opening it is itself proof the sender holds the key.
    private func handleSealed(packet: MeshPacket, sealed: Meshchat_SealedMessage, myNodeNum: Int32) async {
        let from = Int32(bitPattern: packet.from)
        let privatelyToUs = packet.pkiEncrypted && Int32(bitPattern: packet.to) == myNodeNum
        let roomId = Int32(bitPattern: sealed.roomID)
        if !TrustRules.sealedPlacementOk(
            slotRoom: firepitRoomFor(channel: Int(packet.channel)),
            sealedRoom: roomId, privatelyToUs: privatelyToUs)
        {
            return
        }
        // The generation it was sealed under, not the one we have moved on to:
        // a packet sealed just before a rotation can arrive just after it.
        let generation = sealed.generation == 0 ? RoomKeyStore.first : Int(sealed.generation)
        let plain: Data
        switch roomKeys.open(roomId: roomId, generation: generation, sender: from, payload: sealed.ciphertext) {
        case .read(let opened):
            plain = opened
        case .noKey:
            return
        case .outOfHours(let hour, let now):
            // An old recording, or two clocks more than an hour apart.
            log.warning("sealed payload from \(from) was sealed in hour \(hour), and it is \(now) here; ignored")
            return
        case .replayed:
            log.warning("sealed payload from \(from) was opened before; ignored")
            return
        case .outdated:
            // Only on the room's own slot and from somebody in it: the version byte proves nothing, and anyone can
            // address a packet to us.
            let onItsSlot = firepitRoomFor(channel: Int(packet.channel)) == roomId
            let isMember = (try? await memberDao.findEntity(roomId: roomId, nodeNum: from)) != nil
            if onItsSlot && isMember {
                await noticeOutdated(roomId: roomId, sender: from)
            }
            return
        case .unreadable:
            log.warning("sealed payload for a room would not open")
            return
        }
        if generation == roomKeys.generationOf(roomId: roomId) {
            try? await memberDao.record(roomId: roomId, nodeNum: from, now: clock())
            try? await memberDao.recordOpenedGeneration(roomId: roomId, nodeNum: from, generation: generation)
        }
        await settleHandover(roomId: roomId, member: from, generation: generation)
        await handleControl(
            packet: packet, payload: plain, myNodeNum: myNodeNum,
            authenticated: true, sealedRoomId: roomId, sealedGeneration: generation)
    }

    /// One person's words or receipts, sealed from their phone to ours.
    ///
    /// Only privately to us, and only from somebody whose phone key we hold.
    private func handleSealedDirect(packet: MeshPacket, direct: Meshchat_SealedDirect, myNodeNum: Int32) async {
        let from = Int32(bitPattern: packet.from)
        if !(packet.pkiEncrypted && Int32(bitPattern: packet.to) == myNodeNum) {
            return
        }
        let peerKey = await mesh.phoneKeyOf(nodeNum: from)
        let opened = await openDirect(sender: from, myNodeNum: myNodeNum, peerKey: peerKey, sealed: direct.ciphertext)
        let plain: Data
        switch opened {
        case .read(let opening):
            plain = opening.plain
        case .noPeerKey:
            await refuseDirect(packet: packet)
            return
        case .replayed:
            log.warning("sealed direct was opened before; ignored")
            return
        case .outOfHours:
            log.warning("sealed direct was outside the open hour window; ignored")
            return
        case .unreadable:
            await refuseDirect(packet: packet)
            return
        }
        guard let inner = try? Meshchat_MeshChatControl(serializedBytes: plain) else { return }
        switch inner.payload {
        case .roomText(let words):
            await mesh.saveSealedDirectText(
                packet: packet,
                text: words.text,
                replyId: words.replyID == 0 ? nil : Int32(bitPattern: words.replyID),
                emoji: words.emoji == 0 ? nil : Int(words.emoji)
            )
            if words.emoji == 0 || words.replyID == 0 {
                await receipts.received(
                    channel: Int(packet.channel), messageId: Int32(bitPattern: packet.id), peer: from)
            }
        case .receipt(let receipt):
            await receipts.handle(from: from, receipt: receipt)
        default:
            break
        }
    }

    private func openDirect(sender: Int32, myNodeNum: Int32, peerKey: Data?, sealed: Data) async -> DirectOpening {
        guard let peerKey else { return .noPeerKey }
        let tag = DirectSeal.hourTagOf(sealed)
        if let tag, !roomKeys.directTagInWindow(tag: tag) {
            return .outOfHours
        }
        let rooms = tag.map { roomKeys.directSecretsForOpening(tag: $0) } ?? []
        guard
            let opening = phoneKeys.openDirect(
                peerPublic: peerKey,
                sealed: sealed,
                context: DirectSeal.contextOf(senderNodeNum: sender, recipientNodeNum: myNodeNum),
                rooms: rooms)
        else {
            return .unreadable
        }
        guard roomKeys.firstDirectSight(sender: sender, opening: opening) else {
            return .replayed
        }
        if let room = opening.room {
            try? await memberDao.recordOpenedGeneration(roomId: room.roomId, nodeNum: sender, generation: room.generation)
        }
        return .read(opening)
    }

    /// Tells the sender their sealed message did not open here, so they are not
    /// left believing it was read. Most often this phone has not learned their
    /// phone key yet. At most once a minute per sender.
    private func refuseDirect(packet: MeshPacket) async {
        let from = Int32(bitPattern: packet.from)
        let now = clock()
        let shouldSend = refusedAt.withLock { state -> Bool in
            if let last = state[from], now - last < Self.refusalGapMillis {
                return false
            }
            state[from] = now
            return true
        }
        if !shouldSend {
            return
        }
        guard let radioKey = await mesh.publicKeyOf(nodeNum: from) else {
            return
        }
        var refused = Meshchat_SealedDirectRefused()
        refused.requestID = packet.id
        var control = Meshchat_MeshChatControl()
        control.version = InviteCodec.version
        control.sealedDirectRefused = refused
        do {
            let packet = try MeshPacketBuilder.meshPacket(
                to: from,
                channel: 0,
                portNum: .privateApp,
                payload: try control.serializedData(),
                hopLimit: mesh.hopLimitForSending(),
                priority: .background,
                pkiEncrypted: true,
                publicKey: radioKey
            )
            var toRadio = ToRadio()
            toRadio.packet = packet
            try await link.send(toRadio)
        } catch {
            log.warning("could not refuse sealed direct")
        }
    }

    /// Somebody says a sealed message of ours did not open on their phone. The
    /// message is marked so, rather than left reading as delivered, and our card
    /// goes again to the rooms we share with them so their phone learns our key.
    private func handleDirectRefused(
        packet: MeshPacket,
        refused: Meshchat_SealedDirectRefused,
        myNodeNum: Int32
    ) async {
        let from = Int32(bitPattern: packet.from)
        if !(packet.pkiEncrypted && Int32(bitPattern: packet.to) == myNodeNum) {
            return
        }
        if !(await mesh.handleDirectRefusal(messageId: Int32(bitPattern: refused.requestID), by: from)) {
            return
        }
        var shared: [RoomChannel] = []
        for room in ChannelSlotManager.rooms(channels: mesh.channels.value) {
            if roomKeys.canSeal(roomId: room.id),
                (try? await memberDao.findEntity(roomId: room.id, nodeNum: from)) != nil
            {
                shared.append(room)
            }
        }
        if !shared.isEmpty {
            await shareCard(card: latestCard.withLock { $0 } ?? Self.noName, rooms: shared)
        }
    }

    /// Somebody is asking to be let in.
    ///
    /// Every check here is about narrowing what a photographed code is worth.
    /// Nothing is handed over: the keys only move once a person says so.
    private func handleJoinHello(packet: MeshPacket, hello: Meshchat_JoinHello, myNodeNum: Int32) async {
        let from = Int32(bitPattern: packet.from)
        if !admitAttempt(nodeNum: from) {
            return
        }
        let bound = TrustRules.helloIsBound(
            pkiEncrypted: packet.pkiEncrypted,
            addressedToUs: Int32(bitPattern: packet.to) == myNodeNum,
            decryptedWith: packet.publicKey,
            claimed: hello.joinerKey
        )
        if !bound {
            return
        }
        guard var issued = issuedInvites.withLock({ $0[Int32(bitPattern: hello.inviteID)] }) else {
            return
        }
        if issued.usedBy != nil {
            return
        }
        if !PacketOrigin.arrivedDirectly(packet: packet) {
            return
        }
        let proved = RoomCrypto.matchesRecentToken(
            inviteKey: issued.inviteKey,
            inviterNodeNum: myNodeNum,
            token: hello.token,
            nowMillis: clock()
        )
        if !proved {
            return
        }
        let phoneKey = hello.phoneKey
        if !KeyEnvelope.isValidPublicKey(phoneKey) {
            return
        }
        let waiting = pendingJoins.value.first { $0.nodeNum == from }
        if !TrustRules.mayReplacePending(
            waitingRadioKey: waiting?.joinerKey,
            waitingPhoneKey: waiting?.phoneKey,
            radioKey: hello.joinerKey,
            phoneKey: phoneKey
        ) {
            return
        }
        let request = PendingJoin(
            nodeNum: from,
            roomId: issued.roomId,
            inviteId: Int32(bitPattern: hello.inviteID),
            generation: Int(hello.generation),
            joinerKey: hello.joinerKey,
            phoneKey: phoneKey,
            askedAt: waiting?.askedAt ?? clock()
        )
        pendingJoins.set(pendingJoins.value.filter { $0.nodeNum != from } + [request])
        issuedInvites.withLock { ledger in
            issued = ledger[Int32(bitPattern: hello.inviteID)] ?? issued
        }
    }

    /// Lets `nodeNum` in, handing over the room's keys.
    ///
    /// The room's own key is sealed to the phone key that came inside their
    /// hello, so the radios between us carry it without being able to read it.
    public func approveJoin(nodeNum: Int32) async throws {
        let entered = approvingJoins.withLock { state -> Bool in
            let (inserted, _) = state.insert(nodeNum)
            return inserted
        }
        if !entered { return }
        defer { _ = approvingJoins.withLock { $0.remove(nodeNum) } }
        try await approveJoinOnce(nodeNum: nodeNum)
    }

    private func approveJoinOnce(nodeNum: Int32) async throws {
        guard let myNodeNum = mesh.myNodeNum.value else {
            throw RoomError.notConnected
        }
        guard let request = pendingJoins.value.first(where: { $0.nodeNum == nodeNum }) else {
            return
        }
        guard let issued = issuedInvites.withLock({ $0[request.inviteId] }) else {
            clearPending(nodeNum: nodeNum)
            var grant = Meshchat_RoomGrant()
            grant.answer = .declined
            grant.inviteID = UInt32(bitPattern: request.inviteId)
            grant.roomID = UInt32(bitPattern: request.roomId)
            try? await sendGrant(to: nodeNum, key: request.joinerKey, grant: grant)
            throw RoomError.inviteExpired
        }
        guard let room = ChannelSlotManager.findByRoomId(channels: mesh.channels.value, roomId: request.roomId) else {
            throw RoomError.inviteInvalid
        }
        guard let psk = await admin.getChannel(index: room.index)?.settings.psk else {
            throw RoomError.notConnected
        }
        // This hour's key: what they read starts when they are let in.
        let firepitKey = try roomKeys.currentKey(roomId: request.roomId) ?? roomKeys.generate(roomId: request.roomId)
        let generation = roomKeys.generationOf(roomId: request.roomId)
        let hedge = KeyEnvelope.inviteHedge(secret: issued.secret, roomId: request.roomId, inviteId: request.inviteId)
        var grant = Meshchat_RoomGrant()
        grant.answer = .granted
        grant.inviteID = UInt32(bitPattern: request.inviteId)
        grant.roomID = UInt32(bitPattern: request.roomId)
        grant.roomName = room.name
        grant.roomPsk = psk
        grant.generation = UInt32(generation)
        grant.sealedKey = try KeyEnvelope.seal(
            recipient: request.phoneKey,
            secret: firepitKey.key,
            context: KeyEnvelope.contextOf(
                roomId: request.roomId,
                generation: Int32(generation),
                recipientNodeNum: nodeNum,
                hour: Int32(firepitKey.hour)
            ),
            hedge: hedge
        )
        grant.keyHour = UInt32(firepitKey.hour)
        try await sendGrant(to: nodeNum, key: request.joinerKey, grant: grant)
        _ = issuedInvites.withLock { ledger in
            ledger.removeValue(forKey: request.inviteId)
        }
        clearPending(nodeNum: nodeNum)
        try await memberDao.record(
            roomId: request.roomId, nodeNum: nodeNum,
            now: clock(), invitedBy: myNodeNum)
        await learnPhoneKey(nodeNum: nodeNum, key: request.phoneKey, source: .inPerson)
        await noteActivity(roomId: request.roomId)
        await announceJoined(
            roomId: request.roomId, joiner: nodeNum, myNodeNum: myNodeNum,
            generation: generation, phoneKey: request.phoneKey)
        await sendRosterTo(joiner: nodeNum, roomId: request.roomId)
        await shareCardWith(roomId: request.roomId)
    }

    /// Turns somebody away, so they are told rather than left waiting. */
    public func declineJoin(nodeNum: Int32) async {
        guard let request = pendingJoins.value.first(where: { $0.nodeNum == nodeNum }) else {
            return
        }
        clearPending(nodeNum: nodeNum)
        var grant = Meshchat_RoomGrant()
        grant.answer = .declined
        grant.inviteID = UInt32(bitPattern: request.inviteId)
        grant.roomID = UInt32(bitPattern: request.roomId)
        try? await sendGrant(to: nodeNum, key: request.joinerKey, grant: grant)
    }

    private func sendGrant(to: Int32, key: Data, grant: Meshchat_RoomGrant) async throws {
        var control = Meshchat_MeshChatControl()
        control.version = InviteCodec.version
        control.roomGrant = grant
        let packet = try MeshPacketBuilder.meshPacket(
            to: to,
            channel: 0,
            portNum: .privateApp,
            payload: try control.serializedData(),
            wantAck: true,
            pkiEncrypted: true,
            publicKey: key
        )
        var toRadio = ToRadio()
        toRadio.packet = packet
        try await link.send(toRadio)
    }

    private func clearPending(nodeNum: Int32) {
        pendingJoins.set(pendingJoins.value.filter { $0.nodeNum != nodeNum })
    }

    /// Tells the room who was vouched for, so every roster shows the same chain,
    /// and where to seal them a future key. Sealed under the room's own key.
    private func announceJoined(
        roomId: Int32,
        joiner: Int32,
        myNodeNum: Int32,
        generation: Int,
        phoneKey: Data
    ) async {
        guard let slot = ChannelSlotManager.findByRoomId(channels: mesh.channels.value, roomId: roomId)?.index else {
            return
        }
        var event = Meshchat_RosterEvent()
        event.kind = .joined
        event.nodeNum = UInt32(bitPattern: joiner)
        event.invitedBy = UInt32(bitPattern: myNodeNum)
        event.generation = UInt32(generation)
        event.phoneKey = phoneKey
        var control = Meshchat_MeshChatControl()
        control.version = InviteCodec.version
        control.rosterEvent = event
        guard let sealed = sealFor(roomId: roomId, myNodeNum: myNodeNum, control: control) else {
            return
        }
        var outer = Meshchat_MeshChatControl()
        outer.sealedMessage = sealed
        do {
            let packet = try MeshPacketBuilder.meshPacket(
                to: broadcastNodeNum,
                channel: slot,
                portNum: .privateApp,
                payload: try outer.serializedData(),
                hopLimit: mesh.hopLimitForSending(),
                wantAck: true,
                priority: .background
            )
            var toRadio = ToRadio()
            toRadio.packet = packet
            try await link.send(toRadio)
        } catch {
            log.warning("could not announce joined")
        }
    }

    /// The answer to our own request.
    ///
    /// Only believed from the node we actually asked, and only while we are
    /// still waiting.
    private func handleRoomGrant(packet: MeshPacket, grant: Meshchat_RoomGrant, myNodeNum: Int32) async {
        guard var awaited = awaiting.value else {
            return
        }
        if grant.inviteID != UInt32(bitPattern: awaited.inviteId)
            || grant.roomID != UInt32(bitPattern: awaited.roomId)
        {
            return
        }
        if Int32(bitPattern: packet.from) != awaited.inviter {
            return
        }
        if grant.answer == .declined {
            awaited.declined = true
            awaiting.set(awaited)
            return
        }
        let generation = grant.generation == 0 ? RoomKeyStore.first : Int(grant.generation)
        let grantRoomId = Int32(bitPattern: grant.roomID)
        let held = ChannelSlotManager.findByRoomId(channels: mesh.channels.value, roomId: grantRoomId)
        let acceptable = TrustRules.mayTakeGrant(
            alreadyHeld: held != nil,
            senderIsMember: (try? await memberDao.findEntity(
                roomId: Int32(bitPattern: grant.roomID),
                nodeNum: Int32(bitPattern: packet.from)
            )) != nil,
            grantGeneration: Int32(generation),
            currentGeneration: Int32(roomKeys.generationOf(roomId: Int32(bitPattern: grant.roomID)))
        )
        if !acceptable {
            awaiting.set(nil)
            return
        }
        guard awaited.inviteSecret.count == InviteCodec.inviteSecretSize else {
            return
        }
        let hedge = KeyEnvelope.inviteHedge(
            secret: awaited.inviteSecret,
            roomId: Int32(bitPattern: grant.roomID),
            inviteId: Int32(bitPattern: grant.inviteID)
        )
        guard grant.roomPsk.count == RoomCrypto.pskSize,
            let firepitKey = phoneKeys.open(
                sealed: grant.sealedKey,
                context: KeyEnvelope.contextOf(
                    roomId: Int32(bitPattern: grant.roomID),
                    generation: Int32(generation),
                    recipientNodeNum: myNodeNum,
                    hour: Int32(bitPattern: grant.keyHour)
                ),
                hedge: hedge
            ),
            firepitKey.count == RoomCipher.keySize
        else {
            return
        }
        let slot = held?.index ?? ChannelSlotManager.nextFreeSlot(channels: mesh.channels.value)
        guard let slot else {
            return
        }
        try? roomKeys.remember(
            roomId: Int32(bitPattern: grant.roomID),
            key: HourKey(hour: Int(Int32(bitPattern: grant.keyHour)), key: firepitKey),
            generation: generation)
        try? await admin.setChannel(
            channelFor(index: slot, name: grant.roomName, psk: grant.roomPsk, roomId: Int32(bitPattern: grant.roomID))
        )
        let now = clock()
        try? await memberDao.record(
            roomId: Int32(bitPattern: grant.roomID), nodeNum: myNodeNum,
            now: now, invitedBy: Int32(bitPattern: packet.from))
        try? await memberDao.record(
            roomId: Int32(bitPattern: grant.roomID), nodeNum: Int32(bitPattern: packet.from),
            now: now, invitedBy: Int32(bitPattern: packet.from))
        try? await roomActivity.recordActivity(roomId: Int32(bitPattern: grant.roomID), now: now)
        mesh.refreshRoomKinds()
        awaiting.set(nil)
        await shareCardWith(roomId: Int32(bitPattern: grant.roomID))
    }

    /// Caps how often one node may ask.
    ///
    /// Each attempt costs a handful of HMACs, and a stranger who cannot pass the
    /// token check has no reason to keep trying.
    private func admitAttempt(nodeNum: Int32) -> Bool {
        let now = clock()
        return joinAttempts.withLock { attempts in
            let recent = (attempts[nodeNum] ?? []).filter { now - $0 < Self.attemptWindowMillis }
            if recent.count >= Self.maxAttempts {
                attempts[nodeNum] = recent
                return false
            }
            attempts[nodeNum] = recent + [now]
            return true
        }
    }

    /// Hands a fresh joiner the roster, so they do not have to wait for every
    /// member to speak before seeing who is around.
    private func sendRosterTo(joiner: Int32, roomId: Int32) async {
        guard let myNodeNum = mesh.myNodeNum.value else {
            return
        }
        let known = ((try? await firstValue(memberDao.observeRoom(roomId: roomId))) ?? [])
            .filter { $0.nodeNum != joiner }
            .sorted { ($0.lastHeard ?? 0) > ($1.lastHeard ?? 0) }
        if known.isEmpty {
            return
        }
        let entries =
            known
            .prefix(Self.maxRosterEntries)
            .map { member -> Meshchat_RosterEntry in
                var entry = Meshchat_RosterEntry()
                entry.nodeNum = UInt32(bitPattern: member.nodeNum)
                entry.invitedBy = UInt32(bitPattern: member.invitedBy ?? 0)
                return entry
            }
        if entries.isEmpty {
            return
        }
        var sync = Meshchat_RosterSync()
        sync.roomID = UInt32(bitPattern: roomId)
        sync.entries = Array(entries)
        sync.truncated = known.count > entries.count
        var control = Meshchat_MeshChatControl()
        control.version = InviteCodec.version
        control.rosterSync = sync
        guard let sealed = sealFor(roomId: roomId, myNodeNum: myNodeNum, control: control),
            let publicKey = await mesh.publicKeyOf(nodeNum: joiner)
        else {
            return
        }
        var outer = Meshchat_MeshChatControl()
        outer.sealedMessage = sealed
        do {
            let packet = try MeshPacketBuilder.meshPacket(
                to: joiner,
                channel: 0,
                portNum: .privateApp,
                payload: try outer.serializedData(),
                wantAck: true,
                priority: .background,
                pkiEncrypted: true,
                publicKey: publicKey
            )
            var toRadio = ToRadio()
            toRadio.packet = packet
            try await link.send(toRadio)
        } catch {
            log.warning("could not send roster")
        }
    }

    /// Somebody's view of the room. Stored without a last-heard time, because we
    /// have not heard these members ourselves — only been told about them.
    private func handleRosterSync(
        packet: MeshPacket,
        sync: Meshchat_RosterSync,
        myNodeNum: Int32,
        sealedRoomId: Int32?,
        sealedUnderCurrent: Bool
    ) async {
        let roomId = Int32(bitPattern: sync.roomID)
        if roomId == 0 || ChannelSlotManager.findByRoomId(channels: mesh.channels.value, roomId: roomId) == nil {
            return
        }
        let acceptable = TrustRules.rosterSyncAcceptable(
            privatelyToUs: packet.pkiEncrypted && Int32(bitPattern: packet.to) == myNodeNum,
            sender: Int32(bitPattern: packet.from),
            ourInviter: (try? await memberDao.findEntity(roomId: roomId, nodeNum: myNodeNum))?.invitedBy,
            senderIsMember: (try? await memberDao.findEntity(
                roomId: roomId,
                nodeNum: Int32(bitPattern: packet.from)
            )) != nil,
            sealedUnderCurrent: sealedUnderCurrent && sealedRoomId == roomId
        )
        if !acceptable {
            return
        }
        let now = clock()
        for entry in sync.entries where entry.nodeNum != 0 {
            try? await memberDao.recordReported(
                roomId: roomId,
                nodeNum: Int32(bitPattern: entry.nodeNum),
                now: now,
                invitedBy: entry.invitedBy == 0 ? nil : Int32(bitPattern: entry.invitedBy)
            )
        }
    }

    /// The inviter is vouching for somebody. Only believed sealed under the
    /// room's own key, on the room's own channel, with the sender claiming
    /// themselves as the inviter.
    private func handleRosterEvent(packet: MeshPacket, event: Meshchat_RosterEvent, sealedRoomId: Int32) async {
        guard let roomId = firepitRoomFor(channel: Int(packet.channel)), roomId == sealedRoomId else {
            return
        }
        if event.kind == .keyRotated {
            await handleRotationNotice(packet: packet, event: event, roomId: roomId)
            return
        }
        if event.kind != .joined {
            return
        }
        let from = Int32(bitPattern: packet.from)
        if Int32(bitPattern: event.invitedBy) != from {
            return
        }
        let joiner = Int32(bitPattern: event.nodeNum)
        if joiner == 0 {
            return
        }
        let isNews = (try? await memberDao.findEntity(roomId: roomId, nodeNum: joiner)) == nil
        let learned: PhoneKeyLearned
        if joiner == mesh.myNodeNum.value {
            learned = PhoneKeyLearned(stored: false, replaced: false)
        } else {
            learned = await learnPhoneKey(nodeNum: joiner, key: event.phoneKey, source: .vouched)
        }
        try? await memberDao.record(
            roomId: roomId,
            nodeNum: joiner,
            now: clock(),
            invitedBy: (isNews || learned.replaced) ? from : nil)
        await noteActivity(roomId: roomId)
        if isNews && joiner != mesh.myNodeNum.value {
            greet(roomId: roomId)
        }
    }

    /// A member says the room has moved to a new key.
    ///
    /// It arrives before our own copy of that key, or instead of it when ours
    /// never reaches us. Either way nothing more is said under the old key.
    private func handleRotationNotice(packet: MeshPacket, event: Meshchat_RosterEvent, roomId: Int32) async {
        let current = roomKeys.generationOf(roomId: roomId)
        let acceptable = TrustRules.rotationNoticeAcceptable(
            sealedUnderCurrent: true,
            senderIsMember: (try? await memberDao.findEntity(
                roomId: roomId,
                nodeNum: Int32(bitPattern: packet.from)
            )) != nil,
            noticeGeneration: Int32(bitPattern: event.generation),
            currentGeneration: Int32(current)
        )
        if !acceptable {
            return
        }
        try? roomKeys.markSuperseded(roomId: roomId, generation: Int(event.generation))
        mesh.refreshRoomKinds()
        if let slot = ChannelSlotManager.slotOf(channels: mesh.channels.value, roomId: roomId) {
            await noticeInRoom(
                slot: slot,
                text: "This room moved to a new key. Nothing more will be sent here until yours arrives. "
                    + "if it doesn't, ask a member to invite you again.",
                roomId: roomId
            )
        }
    }

    private func forgetStaleInvites(nowMillis: Int64) {
        issuedInvites.withLock { ledger in
            ledger = ledger.filter { nowMillis - $0.value.issuedAt <= Self.inviteLedgerTtlMillis }
        }
    }

    private func channelFor(index: Int, name: String, psk: Data, roomId: Int32) -> Channel {
        var moduleSettings = ModuleSettings()
        moduleSettings.positionPrecision = UInt32(PositionPrecision.disabled)
        var settings = ChannelSettings()
        settings.name = name
        settings.psk = psk
        settings.id = UInt32(bitPattern: roomId)
        settings.uplinkEnabled = false
        settings.downlinkEnabled = false
        settings.moduleSettings = moduleSettings
        var channel = Channel()
        channel.index = Int32(index)
        channel.role = .secondary
        channel.settings = settings
        return channel
    }

    private static let noticeNode: Int32 = 0
    private static let publicKeySize = 32
    private static let pkiPayloadBudget = MeshConstants.dataPayloadLen - MeshConstants.pkcOverhead
    private static let maxAttempts = 5
    private static let attemptWindowMillis: Int64 = 60_000
    private static let greetingSpread: Int64 = 30_000
    private static let inviteLedgerTtlMillis: Int64 = 10 * 60 * 1000
    private static let maxRosterEntries = 10
    private static let handoverAckTimeout: Duration = .seconds(45)
    private static let handoverRetryMillis: Int64 = 10 * 60 * 1000

    /// Times a member's radio may take a key without their app being heard to use it, before it is only sent again
    /// on proof the app lacks it.
    static let maxUnconfirmedHandovers = 3
    private static let handoverSpacingSeconds = 2
    private static let refusalGapMillis: Int64 = 60_000
    private static let channelsTimeout: Duration = .seconds(30)

    /// How often old hours' keys are looked for and destroyed. Well inside the
    /// hour, so a key outlives its use by minutes, not most of an hour.
    static let keyEraseEvery: Duration = .seconds(10 * 60)
}

/// What a rotation managed to hand over, and to whom it did not. */
public struct RotationResult: Sendable, Equatable {
    public var generation: Int
    public var reached: Set<Int32>
    public var missed: Set<Int32>

    public init(generation: Int, reached: Set<Int32>, missed: Set<Int32>) {
        self.generation = generation
        self.reached = reached
        self.missed = missed
    }
}

/// A payload opened from a room's seal, for the part of the app that owns it. */
public struct OpenedInRoom: Sendable, Equatable {
    public var packet: MeshPacket
    public var roomId: Int32
    /// Sealed under the room's current key, which is what makes the sender a member now. */
    public var sealedUnderCurrent: Bool
    /// True when it came on the room's own slot rather than privately. */
    public var onItsSlot: Bool
    public var control: Meshchat_MeshChatControl

    public init(
        packet: MeshPacket,
        roomId: Int32,
        sealedUnderCurrent: Bool,
        onItsSlot: Bool,
        control: Meshchat_MeshChatControl
    ) {
        self.packet = packet
        self.roomId = roomId
        self.sealedUnderCurrent = sealedUnderCurrent
        self.onItsSlot = onItsSlot
        self.control = control
    }
}

extension PendingHandoverEntity {
    fileprivate func removedNodes() -> [Int32] {
        removed.split(separator: ",").compactMap { Int32($0) }
    }
}

private func unique(_ values: [Int32]) -> [Int32] {
    var seen = Set<Int32>()
    var out: [Int32] = []
    for value in values where seen.insert(value).inserted {
        out.append(value)
    }
    return out
}

private func firstValue<T: Sendable>(_ stream: AsyncStream<T>) async throws -> T {
    var iterator = stream.makeAsyncIterator()
    guard let value = await iterator.next() else {
        throw RoomError.inviteInvalid
    }
    return value
}

extension Array {
    fileprivate func asyncMap<T>(_ transform: (Element) async throws -> T) async throws -> [T] {
        var results: [T] = []
        results.reserveCapacity(count)
        for element in self {
            results.append(try await transform(element))
        }
        return results
    }
}

/// One member of one room, for keeping count of what was handed to whom.
private struct MemberInRoom: Hashable, Sendable {
    let roomId: Int32
    let nodeNum: Int32
}
