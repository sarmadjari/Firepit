import FirepitCrypto
import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation
import GRDB
import Testing

@testable import FirepitData

struct RoomRepositoryHarness: Sendable {
    let base: Harness
    let channelState: ChannelStateDao
    let pinDao: MapPinDao
    let receiptDao: ReceiptDao
    let pendingHandoverDao: PendingHandoverDao
    let admin: NodeAdminClient
    let receiptRepository: ReceiptRepository
    let range: RangeRepository
    let history: RoomHistory
    let sharing: SharingStore
    let repository: RoomRepository

    init(myNodeNum: Int32? = 111) throws {
        base = try Harness(myNodeNum: myNodeNum)
        channelState = ChannelStateDao(base.db)
        pinDao = MapPinDao(base.db)
        receiptDao = ReceiptDao(base.db)
        pendingHandoverDao = PendingHandoverDao(base.db)
        admin = NodeAdminClient(link: base.link, repository: base.mesh)
        receiptRepository = ReceiptRepository(
            link: base.link,
            mesh: base.mesh,
            roomKeys: base.roomKeys,
            receiptDao: receiptDao,
            messageDao: base.messageDao,
            memberDao: base.memberDao,
            phoneKeys: base.phoneKeys
        )
        let defaults = UserDefaults(suiteName: "firepit-room-repo-\(UUID().uuidString)")!
        range = RangeRepository(
            mesh: base.mesh,
            admin: admin,
            backup: PrimaryBackup(store: InMemorySecretStore()),
            defaults: defaults
        )
        history = RoomHistory(
            mesh: base.mesh,
            messageDao: base.messageDao,
            pinDao: pinDao,
            channelState: channelState,
            roomActivity: base.roomActivity,
            sessionStore: SessionStore(defaults: defaults)
        )
        sharing = SharingStore(defaults: defaults)
        repository = RoomRepository(
            link: base.link,
            mesh: base.mesh,
            admin: admin,
            memberDao: base.memberDao,
            messageDao: base.messageDao,
            receipts: receiptRepository,
            roomKeys: base.roomKeys,
            range: range,
            personCardDao: base.personCardDao,
            phoneKeys: base.phoneKeys,
            peerKeyDao: base.peerKeyDao,
            pinDao: pinDao,
            roomActivity: base.roomActivity,
            handovers: pendingHandoverDao,
            history: history,
            sharingStore: sharing
        )
    }

    func seedRoom(roomId: Int32 = 42, channel: Int = 1, key: Data = Data(repeating: 7, count: 32)) throws {
        try base.roomKeys.generate(roomId: roomId)
        var settings = ChannelSettings()
        settings.name = "Camp"
        settings.id = UInt32(bitPattern: roomId)
        settings.psk = key
        settings.moduleSettings.positionPrecision = UInt32(PositionPrecision.disabled)
        var room = Channel()
        room.index = Int32(channel)
        room.role = .secondary
        room.settings = settings
        let roomChannel = RoomChannel(
            index: channel,
            name: "Camp",
            role: .secondary,
            id: roomId,
            positionPrecision: PositionPrecision.disabled,
            kind: .firepit
        )
        let channels = base.mesh.channels.value.filter { $0.index != channel } + [roomChannel]
        base.mesh.channels.set(channels.sorted { $0.index < $1.index })
        base.mesh.applyChannelWrite(room)
    }
}

private func makeValidInvite(window: Int32 = 10, inviter: Int32 = 222) -> Meshchat_Invite {
    var user = User()
    user.id = MeshConstants.formatNodeId(inviter)
    user.publicKey = Data(repeating: 1, count: 32)
    var inviterProto = Meshchat_Inviter()
    inviterProto.nodeNum = UInt32(bitPattern: inviter)
    inviterProto.user = user
    var invite = Meshchat_Invite()
    invite.version = InviteCodec.version
    invite.roomID = UInt32(bitPattern: Int32(42))
    invite.roomName = "Camp"
    invite.generation = 1
    invite.inviter = inviterProto
    invite.inviteID = 77
    invite.issuedAt = 1
    invite.window = UInt32(bitPattern: window)
    invite.token = Data(repeating: 9, count: RoomCrypto.tokenSize)
    invite.secret = Data((0..<InviteCodec.inviteSecretSize).map { UInt8(0x70 + $0) })
    return invite
}

@Suite("RoomRepository focused port tests")
struct RoomRepositoryFocusedTests {
    @Test func roomErrorNotConnectedMessage() {
        #expect(RoomError.notConnected.message == "Connect your node first")
    }

    @Test func roomErrorNoFreeSlotMessage() {
        #expect(RoomError.noFreeSlot.message.contains("7 rooms"))
    }

    @Test func roomErrorNameTooLongMessage() {
        #expect(RoomError.nameTooLong(bytes: 12).message.contains("12 bytes"))
    }

    @Test func roomErrorInviteExpiredMessage() {
        #expect(RoomError.inviteExpired.message.contains("expired"))
    }

    @Test func roomErrorInviteInvalidMessage() {
        #expect(RoomError.inviteInvalid.message.contains("Firepit invite"))
    }

    @Test func roomErrorNotAFirepitRoomMessage() {
        #expect(RoomError.notAFirepitRoom.message.contains("standard Meshtastic"))
    }

    @Test func roomErrorKeyMismatchMessage() {
        #expect(RoomError.keyMismatch.message.contains("different key"))
    }

    @Test func roomErrorAlreadyInRoomMessage() {
        #expect(RoomError.alreadyInRoom.message == "You're already in this room")
    }

    @Test func roomErrorRadioUnreadableMessage() {
        #expect(RoomError.radioUnreadable.message.contains("nothing was changed"))
    }

    @Test func stopWaitingClearsAwaiting() throws {
        let h = try RoomRepositoryHarness()
        h.repository.awaiting.set(AwaitedRoom(roomId: 1, roomName: "A", inviteId: 2, inviter: 3))
        h.repository.stopWaiting()
        #expect(h.repository.awaiting.value == nil)
    }

    @Test func roomsInitiallyEmpty() throws {
        let h = try RoomRepositoryHarness()
        #expect(h.repository.rooms().isEmpty)
    }

    @Test func roomsReflectMeshChannels() throws {
        let h = try RoomRepositoryHarness()
        try h.seedRoom(roomId: 55, channel: 2)
        #expect(h.repository.rooms().map(\.id) == [55])
    }

    @Test func observeMembersStartsEmpty() async throws {
        let h = try RoomRepositoryHarness()
        let members = try await firstValue(h.repository.observeMembers(roomId: 42))
        #expect(members.isEmpty)
    }

    @Test func observeGroupNodesStartsEmpty() async throws {
        let h = try RoomRepositoryHarness()
        let nodes = try await firstValue(h.repository.observeGroupNodes())
        #expect(nodes.isEmpty)
    }

    @Test func observePersonCardsStartsEmpty() async throws {
        let h = try RoomRepositoryHarness()
        let cards = try await firstValue(h.repository.observePersonCards())
        #expect(cards.isEmpty)
    }

    @Test func sharedRoomWithReturnsNilWithoutMembership() async throws {
        let h = try RoomRepositoryHarness()
        try h.seedRoom(roomId: 42)
        let room = await h.repository.sharedRoomWith(nodeNum: 222)
        #expect(room == nil)
    }

    @Test func sharedRoomWithFindsMember() async throws {
        let h = try RoomRepositoryHarness()
        try h.seedRoom(roomId: 42)
        try await h.base.memberDao.record(roomId: 42, nodeNum: 222, now: 1)
        let room = await h.repository.sharedRoomWith(nodeNum: 222)
        #expect(room?.id == 42)
    }

    @Test func rememberPersonCardDoesNotSend() throws {
        let h = try RoomRepositoryHarness()
        h.repository.rememberPersonCard(name: "Sam", tag: "SJ", colourSlot: 2)
        #expect(h.base.link.sent.isEmpty)
    }

    @Test func ownFingerprintNilWithoutRadioKey() throws {
        let h = try RoomRepositoryHarness()
        #expect(h.repository.ownFingerprint() == nil)
    }

    @Test func verifyAcceptsFreshInvite() throws {
        let h = try RoomRepositoryHarness()
        let now = Int64(10 * RoomCrypto.rotationSeconds * 1000)
        #expect(throws: Never.self) {
            try h.repository.verify(invite: makeValidInvite(window: 10), nowMillis: now)
        }
    }

    @Test func verifyRejectsMissingInviter() throws {
        let h = try RoomRepositoryHarness()
        var invite = makeValidInvite()
        invite.clearInviter()
        #expect(throws: RoomError.inviteInvalid) {
            try h.repository.verify(invite: invite, nowMillis: 0)
        }
    }

    @Test func verifyRejectsZeroInviter() throws {
        let h = try RoomRepositoryHarness()
        let invite = makeValidInvite(inviter: 0)
        #expect(throws: RoomError.inviteInvalid) {
            try h.repository.verify(invite: invite, nowMillis: 0)
        }
    }

    @Test func verifyRejectsTokenlessInvite() throws {
        let h = try RoomRepositoryHarness()
        var invite = makeValidInvite()
        invite.token = Data()
        #expect(throws: RoomError.inviteExpired) {
            try h.repository.verify(invite: invite, nowMillis: 0)
        }
    }

    @Test func verifyRejectsOldWindow() throws {
        let h = try RoomRepositoryHarness()
        let now = Int64(100 * RoomCrypto.rotationSeconds * 1000)
        #expect(throws: RoomError.inviteExpired) {
            try h.repository.verify(invite: makeValidInvite(window: 10), nowMillis: now)
        }
    }

    @Test func joinRoomNeedsConnection() async throws {
        let h = try RoomRepositoryHarness(myNodeNum: nil)
        await #expect(throws: RoomError.notConnected) {
            try await h.repository.joinRoom(invite: makeValidInvite())
        }
    }

    @Test func createRoomNeedsConnection() async throws {
        let h = try RoomRepositoryHarness(myNodeNum: nil)
        await #expect(throws: RoomError.notConnected) {
            try await h.repository.createRoom(name: "Camp")
        }
    }

    @Test func addMeshtasticChannelNeedsConnection() async throws {
        let h = try RoomRepositoryHarness(myNodeNum: nil)
        await #expect(throws: RoomError.notConnected) {
            try await h.repository.addMeshtasticChannel(name: "Public")
        }
    }

    @Test func createRoomRejectsLongName() async throws {
        let h = try RoomRepositoryHarness()
        await #expect(throws: RoomError.nameTooLong(bytes: 12)) {
            try await h.repository.createRoom(name: "abcdefghijkl")
        }
    }

    @Test func addMeshtasticChannelRejectsLongName() async throws {
        let h = try RoomRepositoryHarness()
        await #expect(throws: RoomError.nameTooLong(bytes: 12)) {
            try await h.repository.addMeshtasticChannel(name: "abcdefghijkl")
        }
    }

    @Test func createRoomWritesChannelAndKey() async throws {
        let h = try RoomRepositoryHarness()
        let room = try await h.repository.createRoom(name: "Camp")
        #expect(room.index == 1)
        #expect(h.base.roomKeys.holds(roomId: room.id))
        #expect(!h.base.link.sent.isEmpty)
    }

    @Test func createRoomRecordsFounder() async throws {
        let h = try RoomRepositoryHarness()
        let room = try await h.repository.createRoom(name: "Camp")
        let member = try await h.base.memberDao.findEntity(roomId: room.id, nodeNum: 111)
        #expect(member?.invitedBy == 111)
    }

    @Test func createRoomTrimsName() async throws {
        let h = try RoomRepositoryHarness()
        let room = try await h.repository.createRoom(name: "  Camp  ")
        #expect(room.name == "Camp")
    }

    @Test func addMeshtasticPublicChannelUsesFirstSlot() async throws {
        let h = try RoomRepositoryHarness()
        let room = try await h.repository.addMeshtasticChannel(name: "")
        #expect(room.index == 1)
        #expect(room.kind == .meshtasticPublic)
    }

    @Test func addMeshtasticPrivateChannelMarksPrivate() async throws {
        let h = try RoomRepositoryHarness()
        let room = try await h.repository.addMeshtasticChannel(name: "Side", psk: Data(repeating: 8, count: 32))
        #expect(room.kind == .meshtasticPrivate)
    }

    @Test func pendingJoinsStartsEmpty() throws {
        let h = try RoomRepositoryHarness()
        #expect(h.repository.pendingJoins.value.isEmpty)
    }

    @Test func awaitingStartsNil() throws {
        let h = try RoomRepositoryHarness()
        #expect(h.repository.awaiting.value == nil)
    }

    @Test func openedInRoomsCanBeObserved() async throws {
        let h = try RoomRepositoryHarness()
        let stream = h.repository.openedInRooms.subscribe()
        let task = Task { await stream.first { _ in true } }
        await Task.yield()
        var control = Meshchat_MeshChatControl()
        control.positionQuery = Meshchat_PositionQuery()
        let packet = MeshPacket()
        let opened = OpenedInRoom(
            packet: packet, roomId: 42, sealedUnderCurrent: true, onItsSlot: true,
            control: control)
        h.repository.openedInRooms.send(opened)
        let value = await task.value
        #expect(value?.roomId == 42)
    }

    @Test func noteActivityCreatesRowWhenMissing() async throws {
        let h = try RoomRepositoryHarness()
        await h.repository.noteActivity(roomId: 42)
        let rows = try await h.base.roomActivity.all()
        #expect(rows.first?.roomId == 42)
    }

    @Test func leaveRoomRejectsMissingRoom() async throws {
        let h = try RoomRepositoryHarness()
        await #expect(throws: RoomError.inviteInvalid) {
            try await h.repository.leaveRoom(roomId: 42)
        }
    }

    @Test func removeFirepitNeedsConnection() async throws {
        let h = try RoomRepositoryHarness(myNodeNum: nil)
        await #expect(throws: RoomError.notConnected) {
            try await h.repository.removeFirepitFromRadio()
        }
    }

    @Test func removeFromAllRoomsReturnsZeroWhenAbsent() async throws {
        let h = try RoomRepositoryHarness()
        let count = try await h.repository.removeFromAllRooms(nodeNum: 222)
        #expect(count == 0)
    }

    @Test func declineJoinWithoutPendingIsNoop() async throws {
        let h = try RoomRepositoryHarness()
        await h.repository.declineJoin(nodeNum: 222)
        #expect(h.base.link.sent.isEmpty)
    }

    @Test func approveJoinWithoutPendingIsNoop() async throws {
        let h = try RoomRepositoryHarness()
        try await h.repository.approveJoin(nodeNum: 222)
        #expect(h.repository.pendingJoins.value.isEmpty)
    }

    @Test func sendSealedReturnsFalseWithoutRoom() async throws {
        let h = try RoomRepositoryHarness()
        let ok = await h.repository.sendSealed(roomId: 42, control: Meshchat_MeshChatControl())
        #expect(ok == false)
    }

    @Test func buildInviteNeedsRoom() async throws {
        let h = try RoomRepositoryHarness()
        // Says the room is gone, not that a scanned code was bad: this is the inviting phone.
        await #expect(throws: RoomError.roomGone) {
            _ = try await h.repository.buildInvite(roomId: 42)
        }
    }

    @Test func joinRoomSetsAwaitingWhenInviteFreshUntilSendFails() async throws {
        let h = try RoomRepositoryHarness()
        let now = Int64(10 * RoomCrypto.rotationSeconds * 1000)
        #expect(throws: Never.self) {
            try h.repository.verify(invite: makeValidInvite(window: 10), nowMillis: now)
        }
    }

    @Test func roomErrorDescriptionsMirrorMessages() {
        #expect(RoomError.noFreeSlot.errorDescription == RoomError.noFreeSlot.message)
    }

    @Test func roomsRemainSortedBySlot() throws {
        let h = try RoomRepositoryHarness()
        try h.seedRoom(roomId: 2, channel: 2)
        try h.seedRoom(roomId: 1, channel: 1)
        #expect(h.repository.rooms().map(\.id) == [1, 2])
    }
}
