import FirepitData
import FirepitModel
import FirepitProtocol
import Testing

@testable import Firepit

@Suite("Chats state")
struct ChatsTests {
    private let me: Int32 = 1
    private let maya: Int32 = 2

    private func state() -> ChatsUiState {
        ChatsUiState(
            connected: true,
            channels: [
                RoomChannel(index: 1, name: "Camp", role: .secondary, id: 10, positionPrecision: 32, kind: .firepit),
                RoomChannel(
                    index: 2,
                    name: "LongFast",
                    role: .secondary,
                    id: 20,
                    positionPrecision: 32,
                    kind: .meshtasticPrivate
                ),
                RoomChannel(
                    index: 3,
                    name: "Nope",
                    role: .secondary,
                    id: 30,
                    positionPrecision: 0,
                    kind: .unencrypted
                ),
            ],
            selected: 1,
            messages: [message(101, from: 2, text: "Need water"), message(102, from: 1, text: "Copy", replyId: 101)],
            nodes: [
                me: MeshNode(nodeNum: me, longName: "Radio Sam", shortName: "RS"),
                maya: MeshNode(nodeNum: maya, longName: "Radio Maya", shortName: "RM"),
            ],
            cards: [maya: PersonCard(nodeNum: maya, name: "Maya Chen", tag: "MC", colourSlot: nil, updatedAt: 1)],
            draft: "hi",
            signingAvailable: true,
            myNode: MeshNode(nodeNum: me, longName: "Radio Sam", shortName: "RS"),
            person: Person(id: 7, name: "Sam Rivera", tag: "SR"),
            latest: [:]
        )
    }

    private func message(_ id: Int32, from: Int32, text: String, replyId: Int32? = nil) -> ChatMessage {
        ChatMessage(
            id: id,
            channel: 1,
            fromNodeNum: from,
            toNodeNum: broadcastNodeNum,
            text: text,
            sentAt: Int64(id),
            status: .received,
            isOutgoing: from == me,
            replyId: replyId,
            roomId: 10
        )
    }

    @Test func nameUsesOwnPersonBeforeRadioName() {
        #expect(state().nameOf(me) == "Sam Rivera")
    }

    @Test func nameUsesRoomCardBeforeRadioName() {
        #expect(state().nameOf(maya) == "Maya Chen")
    }

    @Test func nameFallsBackToRadioNameThenNodeId() {
        var s = state()
        s.cards = [:]
        #expect(s.nameOf(maya) == "Radio Maya")
        #expect(s.nameOf(99) == MeshConstants.formatNodeId(99))
    }

    @Test func tagUsesOwnPersonBeforeRadioTag() {
        #expect(state().tagOf(me) == "SR")
    }

    @Test func tagUsesCardBeforeRadioTag() {
        #expect(state().tagOf(maya) == "MC")
    }

    @Test func roomKindAndSealedDeriveFromSelectedChannel() {
        let s = state()
        #expect(s.kind == .firepit)
        #expect(s.sealed)
    }

    @Test func interoperableConversationIsOpenConversation() {
        var s = state()
        s.selected = 2
        #expect(s.kind == .meshtasticPrivate)
        #expect(s.isOpenConversation)
        #expect(!s.sealed)
    }

    @Test func unencryptedRoomIsNotPrivateTarget() {
        var s = state()
        s.selected = 3
        #expect(!s.hasPrivateTarget)
    }

    @Test func directPeerIsPrivateTarget() {
        var s = state()
        s.selected = nil
        s.directPeer = maya
        #expect(s.hasPrivateTarget)
        #expect(s.kind == nil)
    }

    @Test func directNoticesBelongToThePersonNamedByToNodeNum() {
        let first = ChatMessage(
            id: 901,
            channel: 0,
            fromNodeNum: noticeNodeNum,
            toNodeNum: 7,
            text: "key changed",
            sentAt: 1,
            status: .received
        )
        let second = ChatMessage(
            id: 902,
            channel: 0,
            fromNodeNum: noticeNodeNum,
            toNodeNum: 8,
            text: "key changed",
            sentAt: 2,
            status: .received
        )
        #expect(first.peerOf(myNodeNum: me) == 7)
        #expect(second.peerOf(myNodeNum: me) == 8)
        #expect(Set([first.peerOf(myNodeNum: me), second.peerOf(myNodeNum: me)]) == [7, 8])
    }

    @Test func directOnlyByRadioTracksSealAvailability() {
        var s = state()
        s.directPeer = maya
        s.directSealed = false
        #expect(s.directOnlyByRadio)
        s.directSealed = true
        #expect(!s.directOnlyByRadio)
    }

    @Test func sealedRoomBudgetSubtractsOverhead() {
        let s = state()
        #expect(s.textBudget == MeshConstants.maxTextBytes - MessagePrivacy.sealedOverhead)
    }

    @Test func sealedDirectBudgetUsesDirectLimit() {
        var s = state()
        s.selected = nil
        s.directPeer = maya
        s.directSealed = true
        #expect(s.textBudget == MessagePrivacy.maxDirectSealedTextBytes)
    }

    @Test func draftBytesCountUtf8AndRemainingBytes() {
        var s = state()
        s.draft = "🔥"
        #expect(s.draftBytes == 4)
        #expect(s.remainingBytes == s.textBudget - 4)
    }

    @Test func canSendRequiresConnectionTargetNonBlankAndBudget() {
        var s = state()
        #expect(s.canSend)
        s.connected = false
        #expect(!s.canSend)
        s.connected = true
        s.draft = "   "
        #expect(!s.canSend)
        s.draft = String(repeating: "a", count: s.textBudget + 1)
        #expect(!s.canSend)
    }

    @Test func unsignedWarningOnlyForLongSignedBroadcasts() {
        var s = state()
        s.selected = 2
        s.draft = String(repeating: "a", count: MeshConstants.signedBroadcastTextBudget + 1)
        #expect(s.losesSignature)
        s.directPeer = maya
        #expect(!s.losesSignature)
    }

    @Test func repliedToFindsOriginalMessage() {
        let s = state()
        #expect(s.repliedTo(s.messages[1])?.id == 101)
    }

    @Test func visibleMessagesFilterSearchCaseInsensitively() {
        var s = state()
        s.query = "WATER"
        #expect(s.visibleMessages.map(\.id) == [101])
        #expect(s.isSearching)
    }

    @Test func unreadMutedAndLatestArePlainState() {
        var s = state()
        s.unread = [1: 3]
        s.muted = [1]
        s.latest = [1: message(200, from: maya, text: "Latest")]
        #expect(s.unread[1] == 3)
        #expect(s.muted.contains(1))
        #expect(s.latest[1]?.text == "Latest")
    }

    @MainActor
    @Test func replyAndCancelAreViewModelActions() throws {
        let app = try AppContainer(configuration: .demo)
        let model = ChatsViewModel(app: app)
        let original = message(500, from: maya, text: "Can you hear me?")
        model.startReply(original)
        #expect(model.uiState.replyingTo == original)
        model.cancelReply()
        #expect(model.uiState.replyingTo == nil)
    }

    @MainActor
    @Test func draftSendClearsDraftAndReplySynchronously() throws {
        let app = try AppContainer(configuration: .demo)
        let model = ChatsViewModel(app: app)
        model.select(1)
        model.updateDraft("  hello  ")
        model.startReply(message(1, from: maya, text: "question"))
        model.send()
        #expect(model.uiState.draft == "")
        #expect(model.uiState.replyingTo == nil)
    }

    @MainActor
    @Test func updateDraftTruncatesAtByteBudget() throws {
        let app = try AppContainer(configuration: .demo)
        let model = ChatsViewModel(app: app)
        model.select(1)
        model.updateDraft(String(repeating: "🔥", count: 100))
        #expect(model.uiState.draftBytes <= model.uiState.textBudget)
    }
}

/// The chats screens' wording and rules, which must say exactly what Android says.
@Suite("Chats wording")
struct ChatsWordingTests {
    private func room(_ index: Int, kind: RoomKind, role: ChannelRole = .secondary) -> RoomChannel {
        RoomChannel(index: index, name: "Camp", role: role, id: 10, positionPrecision: 32, kind: kind)
    }

    private func text(_ id: Int32, from: Int32, to: Int32 = broadcastNodeNum, outgoing: Bool = false) -> ChatMessage {
        ChatMessage(
            id: id, channel: 1, fromNodeNum: from, toNodeNum: to, text: "Need water", sentAt: 1, isOutgoing: outgoing)
    }

    @Test func hardwareNamesStopShouting() {
        #expect(ChatsCopy.prettyHardware("WISMESH_TAG") == "Wismesh Tag")
        #expect(ChatsCopy.prettyHardware("HELTEC_V3") == "Heltec V3")
        #expect(ChatsCopy.prettyHardware("T_ECHO__") == "T Echo")
    }

    @Test func nodeStatusSaysWhichRadioAndItsCharge() {
        let node = MeshNode(nodeNum: 1, hwModel: "HELTEC_V3", batteryLevel: 87)
        #expect(ChatsCopy.nodeStatus(connected: false, myNode: node) == "Not connected — open Settings")
        #expect(ChatsCopy.nodeStatus(connected: true, myNode: nil) == "Connected")
        #expect(ChatsCopy.nodeStatus(connected: true, myNode: node) == "Heltec V3 · 87%")
        #expect(ChatsCopy.nodeStatus(connected: true, myNode: MeshNode(nodeNum: 1, batteryLevel: 101)) == "powered")
        #expect(ChatsCopy.nodeStatus(connected: true, myNode: MeshNode(nodeNum: 1, hwModel: " ")) == "Connected")
    }

    @Test func previewSaysWhoCanHearBeforeAnyoneSpeaks() {
        #expect(
            ChatsCopy.channelPreview(channel: room(1, kind: .firepit), latest: nil, senderName: nil)
                == "Only the people you invited")
        #expect(
            ChatsCopy.channelPreview(
                channel: room(0, kind: .meshtasticPublic, role: .primary), latest: nil, senderName: nil)
                == "Primary channel")
        let incoming = text(5, from: 2)
        #expect(
            ChatsCopy.channelPreview(channel: room(1, kind: .firepit), latest: incoming, senderName: "Maya")
                == "Maya: Need water")
        // Our own words are not prefixed with our name.
        let outgoing = text(6, from: 1, outgoing: true)
        #expect(
            ChatsCopy.channelPreview(channel: room(1, kind: .firepit), latest: outgoing, senderName: "Sam")
                == "Need water")
    }

    @Test func emptyListExplainsWhatToDoNext() {
        #expect(
            ChatsCopy.emptyList(filter: .direct, channels: [])
                == "No direct messages yet. To start one, tap someone in a room's info, or open them in "
                + "Settings → Nodes.")
        #expect(
            ChatsCopy.emptyList(filter: .all, channels: [room(1, kind: .firepit, role: .disabled)])
                == "No channels yet. Connect your node in Settings.")
        #expect(
            ChatsCopy.emptyList(filter: .rooms, channels: [room(0, kind: .meshtasticPublic, role: .primary)])
                == "No rooms yet. Create one with the + button.")
    }

    @Test func footnotesCountOnlyWhatArrived() {
        #expect(ChatsCopy.receiptFootnote(nil) == nil)
        #expect(ChatsCopy.receiptFootnote([]) == nil)
        let received = [Receipt(nodeNum: 2, state: .received, at: 1), Receipt(nodeNum: 3, state: .received, at: 2)]
        #expect(ChatsCopy.receiptFootnote(received) == "Delivered to 2")
        #expect(ChatsCopy.receiptFootnote(received + [Receipt(nodeNum: 4, state: .read, at: 3)]) == "Read by 1")
        #expect(ChatsCopy.hopsFootnote(nil) == nil)
        #expect(ChatsCopy.hopsFootnote(0) == nil)
        #expect(ChatsCopy.hopsFootnote(1) == "1 hop")
        #expect(ChatsCopy.hopsFootnote(3) == "3 hops")
    }

    @Test func statusWordingNeverClaimsMoreThanTheMeshSaid() {
        #expect(ChatsCopy.statusLabel(.queued) == "Waiting for the radio")
        #expect(ChatsCopy.statusLabel(.sentToNode) == "Handed to your node")
        #expect(ChatsCopy.statusLabel(.unknown) == "Handed to your node, no confirmation")
        #expect(ChatsCopy.statusLabel(.unheard) == "Nobody repeated it")
        #expect(ChatsCopy.statusLabel(.failed) == "Failed")
        #expect(ChatsCopy.statusLabel(.reachedMesh) == "Heard by at least one node")
        #expect(ChatsCopy.statusLabel(.delivered) == "Acknowledged by the recipient")
        #expect(ChatsCopy.statusLabel(.received) == "Received")
    }

    @Test func peerIsWhoeverIsNotUs() {
        #expect(text(1, from: 2, to: 1).peerOf(myNodeNum: 1) == 2)
        #expect(text(1, from: 1, to: 2, outgoing: true).peerOf(myNodeNum: 1) == 2)
        // Our own node's words synced back from the radio count as ours even when not flagged outgoing.
        #expect(text(1, from: 1, to: 2).peerOf(myNodeNum: 1) == 2)
    }

    @Test func sendFailuresUseTheRepositoryWording() {
        #expect(ChatsCopy.sendFailure(SendError.notConnected) == "Connect your node first")
        struct Opaque: Error {}
        #expect(ChatsCopy.sendFailure(Opaque()) == "Could not send")
    }
}

/// The composer's hint, checked in Android's order: the first rule that applies is the one shown.
@Suite("Composer hint")
struct ComposerHintTests {
    private func state(kind: RoomKind? = .firepit, peer: Int32? = nil, sealed: Bool = false) -> ChatsUiState {
        var state = ChatsUiState()
        state.connected = true
        if let kind {
            state.channels = [
                RoomChannel(index: 1, name: "Camp", role: .secondary, id: 10, positionPrecision: 32, kind: kind)
            ]
            state.selected = peer == nil ? 1 : nil
        }
        state.directPeer = peer
        state.directSealed = sealed
        return state
    }

    @Test func quietInASealedRoom() {
        #expect(state().composerHint == nil)
    }

    @Test func stalledRoomSaysWhyBeforeAnythingElse() {
        var stalled = state(kind: .firepitKeyMissing)
        stalled.draft = String(repeating: "a", count: 300)
        #expect(stalled.composerHint == ComposerHint(text: RoomKind.firepitKeyMissing.summary, tone: .warn))
    }

    @Test func refusesWhereFirepitWillNotSend() {
        #expect(
            state(kind: .unencrypted).composerHint
                == ComposerHint(
                    text: "Firepit won't send here. Open a room, or a conversation with one person.", tone: .warn))
    }

    @Test func fullDraftIsDanger() {
        var full = state()
        full.draft = String(repeating: "a", count: full.textBudget)
        #expect(
            full.composerHint
                == ComposerHint(text: "Full — \(full.textBudget) bytes is the radio's limit", tone: .danger))
    }

    @Test func meshtasticChannelWarnsOnEveryMessage() {
        #expect(
            state(kind: .meshtasticPublic).composerHint
                == ComposerHint(text: RoomKind.meshtasticPublic.summary, tone: .warn))
    }

    @Test func directWithoutPhoneKeyWarnsOnlyRadiosProtectIt() {
        let hint = state(kind: nil, peer: 9, sealed: false).composerHint
        #expect(hint?.tone == .warn)
        #expect(hint?.text.hasPrefix("Only your two radios protect this") == true)
        #expect(state(kind: nil, peer: 9, sealed: true).composerHint == nil)
    }

    @Test func longUnsealedBroadcastLosesItsSignature() {
        var long = state(kind: .meshtasticPrivate)
        long.signingAvailable = true
        long.draft = String(repeating: "a", count: MeshConstants.signedBroadcastTextBudget + 1)
        // An interoperable channel's own warning comes first, as on Android.
        #expect(long.composerHint == ComposerHint(text: RoomKind.meshtasticPrivate.summary, tone: .warn))
    }

    @Test func countdownOnlyNearTheLimit() {
        var near = state()
        near.draft = String(repeating: "a", count: 149)
        #expect(near.composerHint == nil)
        near.draft = String(repeating: "a", count: 150)
        #expect(near.composerHint == ComposerHint(text: "\(near.remainingBytes) bytes left", tone: .quiet))
    }
}

/// The view model's conversation lifecycle, against the demo container's real database.
@Suite("Chats view model", .serialized)
@MainActor
struct ChatsViewModelTests {
    private let me: Int32 = 0x5A3C_91E2

    private func message(_ id: Int32, channel: Int, status: MessageStatus = .received) -> ChatMessage {
        ChatMessage(
            id: id, channel: channel, fromNodeNum: 7, toNodeNum: broadcastNodeNum, text: "m\(id)", sentAt: Int64(id),
            status: status)
    }

    @Test func switchingRoomsStopsTheOldRoomsUpdates() async throws {
        let app = try AppContainer(configuration: .demo)
        let model = ChatsViewModel(app: app)
        try await app.messageDao.save(message: message(1, channel: 1), myNodeNum: me)
        try await app.messageDao.save(message: message(2, channel: 2), myNodeNum: me)

        model.select(1)
        #expect(await eventually { model.uiState.messages.map(\.id) == [1] })
        model.select(2)
        #expect(await eventually { model.uiState.messages.map(\.id) == [2] })

        // Watch room 1 independently, so we know when its observers have heard the new message.
        var seen: [Int32] = []
        let watcher = Task { @MainActor in
            for await messages in app.mesh.observeChannel(channel: 1) { seen = messages.map(\.id) }
        }
        defer { watcher.cancel() }
        var arrival = message(3, channel: 1)
        arrival.sentAt = currentEpochMillis()
        try await app.messageDao.save(message: arrival, myNodeNum: me)
        #expect(await eventually { seen == [1, 3] })
        try await Task.sleep(for: .milliseconds(150))

        // Every table change re-emits every open query, so the room on screen can win a race against a leaked one and
        // hide it. What a leak cannot hide is its side effect: it would mark room 1 read while nobody is looking.
        var unread: [Int: Int] = [:]
        for await counts in app.channelStateDao.observeUnread() {
            unread = Dictionary(counts.map { ($0.channel, $0.count) }, uniquingKeysWith: { _, last in last })
            break
        }
        #expect(unread[1] == 1)
        #expect(model.uiState.messages.map(\.id) == [2])
    }

    @Test func inspectedMessageFollowsItsLiveStatus() async throws {
        let app = try AppContainer(configuration: .demo)
        let model = ChatsViewModel(app: app)
        let sent = ChatMessage(
            id: 40, channel: 1, fromNodeNum: me, toNodeNum: broadcastNodeNum, text: "hi", sentAt: 40,
            status: .sentToNode, isOutgoing: true)
        try await app.messageDao.save(message: sent, myNodeNum: me)
        model.select(1)
        #expect(await eventually { model.uiState.messages.count == 1 })
        model.inspect(sent)
        var delivered = sent
        delivered.status = .delivered
        try await app.messageDao.save(message: delivered, myNodeNum: me)
        #expect(await eventually { model.uiState.inspecting?.status == .delivered })
    }

    @Test func leavingAConversationStopsWatchingIt() throws {
        let app = try AppContainer(configuration: .demo)
        let model = ChatsViewModel(app: app)
        model.select(1)
        #expect(app.chatPresence.openChannel.value == 1)
        model.select(nil)
        #expect(app.chatPresence.openChannel.value == nil)
        #expect(model.uiState.messages.isEmpty)
    }
}
