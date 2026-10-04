import FirepitData
import FirepitModel
import FirepitProtocol
import Foundation
import Observation

/// State for chats, ported from android/app/…/chat/ChatsViewModel.kt.
struct ChatsUiState: Equatable {
    var connected = false
    var channels: [RoomChannel] = []
    var selected: Int?
    /// The conversation's lines; reactions are not among them, see `reactions`.
    var messages: [ChatMessage] = []
    /// Reactions counted under the message they react to, by its id (UX §5.4).
    var reactions: [Int32: [Reactions.Count]] = [:]
    var nodes: [Int32: MeshNode] = [:]
    /// How the people in our rooms name themselves, by node number.
    var cards: [Int32: PersonCard] = [:]
    var draft = ""
    var error: String?
    /// 2.8 firmware only; drives the signing budget hint.
    var signingAvailable = false
    var replyingTo: ChatMessage?
    /// The message whose delivery details are being shown, if any.
    var inspecting: ChatMessage?
    var unread: [Int: Int] = [:]
    var muted: Set<Int> = []
    var query = ""
    /// Null until our radio reports; absent telemetry is not a clear channel.
    var channelLoad: ChannelLoad?
    /// Our own node, for the header's name and battery.
    var myNode: MeshNode?
    var person: Person?
    /// Newest message per channel, for the list previews.
    var latest: [Int: ChatMessage] = [:]
    /// The person whose conversation is open, if it is a direct one.
    var directPeer: Int32?
    /// True when words to `directPeer` are sealed to their phone.
    var directSealed = false
    /// Newest message per person, for the Direct list.
    var directLatest: [ChatMessage] = []

    var selectedChannel: RoomChannel? { channels.first { $0.index == selected } }

    /// What to call somebody: their Firepit card, then their radio name, then the node id.
    func nameOf(_ nodeNum: Int32) -> String {
        if nodeNum == myNode?.nodeNum, let name = person?.name, !name.trimmingCharacters(in: .whitespaces).isEmpty {
            return name
        }
        if let name = cards[nodeNum]?.name, !name.trimmingCharacters(in: .whitespaces).isEmpty {
            return name
        }
        return nodes[nodeNum]?.displayName ?? MeshConstants.formatNodeId(nodeNum)
    }

    /// The initials they chose, falling back to the radio's short name.
    func tagOf(_ nodeNum: Int32) -> String? {
        if nodeNum == myNode?.nodeNum, let tag = person?.tag, !tag.trimmingCharacters(in: .whitespaces).isEmpty {
            return tag
        }
        if let tag = cards[nodeNum]?.tag, !tag.trimmingCharacters(in: .whitespaces).isEmpty {
            return tag
        }
        return nodes[nodeNum]?.shortName
    }

    /// What kind of conversation is open, or nil for a direct one.
    var kind: RoomKind? { selectedChannel?.isRoom == true ? selectedChannel?.kind : nil }

    /// True when the open room seals its own words.
    var sealed: Bool { kind == .firepit }

    /// A room Firepit will send on, or one person.
    var hasPrivateTarget: Bool {
        directPeer != nil || kind.map { $0 != .unencrypted && !$0.isStalledRoom } == true
    }

    /// True when a direct message would be readable by whoever holds either radio.
    var directOnlyByRadio: Bool { directPeer != nil && !directSealed }

    /// True when the open conversation is readable by people outside Firepit.
    var isOpenConversation: Bool { kind?.isInteroperable == true }

    var draftBytes: Int { draft.utf8.count }

    var textBudget: Int {
        if directPeer != nil && directSealed { return MessagePrivacy.maxDirectSealedTextBytes }
        if directPeer == nil && sealed { return MeshConstants.maxTextBytes - MessagePrivacy.sealedOverhead }
        return MeshConstants.maxTextBytes
    }

    var remainingBytes: Int { textBudget - draftBytes }

    var canSend: Bool {
        connected && hasPrivateTarget && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && remainingBytes >= 0
    }

    /// True when this message is long enough that the radio will send it unsigned.
    var losesSignature: Bool {
        directPeer == nil && !sealed && signingAvailable && draftBytes > MeshConstants.signedBroadcastTextBudget
    }

    /// Looks up what a message was replying to, if it is still in this channel.
    func repliedTo(_ message: ChatMessage) -> ChatMessage? {
        guard let id = message.replyId else { return nil }
        return messages.first { $0.id == id }
    }

    /// Messages matching the search box, or all of them when it is empty.
    var visibleMessages: [ChatMessage] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return messages }
        return messages.filter { $0.text.localizedCaseInsensitiveContains(trimmed) }
    }

    var isSearching: Bool { !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}

// MARK: - Presentation rules, ported from ChatsPane.kt and kept pure so they are testable without a screen.

/// Rooms, and one-to-one conversations.
enum ChannelFilter: CaseIterable, Hashable {
    case all
    case rooms
    case direct

    var label: String {
        switch self {
        case .all: String(localized: "All")
        case .rooms: String(localized: "Rooms")
        case .direct: String(localized: "Direct")
        }
    }
}

extension ChatMessage {
    /// Whoever is not us. Outgoing names the recipient, incoming names the sender.
    func peerOf(myNodeNum: Int32?) -> Int32 {
        peerNode(myNodeNum: myNodeNum)
    }
}

/// What the composer says under the field, and how loudly.
struct ComposerHint: Equatable {
    enum Tone: Equatable {
        case quiet
        case warn
        case danger
    }

    let text: String
    let tone: Tone
}

extension ChatsUiState {
    /// Only appears near a limit, or where the conversation is not as private as it looks, so the composer stays
    /// quiet otherwise. First match wins, in the order Android checks them.
    var composerHint: ComposerHint? {
        // A room this phone can no longer seal for says exactly why.
        if let kind, kind.isStalledRoom {
            return ComposerHint(text: kind.summary, tone: .warn)
        }
        if !hasPrivateTarget {
            return ComposerHint(
                text: String(localized: "Firepit won't send here. Open a room, or a conversation with one person."),
                tone: .warn
            )
        }
        if remainingBytes <= 0 {
            return ComposerHint(
                text: String(localized: "Full — \(textBudget) bytes is the radio's limit"), tone: .danger)
        }
        // Said on every message, not once on joining: this is the difference between a room and a Meshtastic channel.
        if isOpenConversation {
            return ComposerHint(text: kind?.summary ?? "", tone: .warn)
        }
        // Somebody who has never shared a room with us: their phone key is unknown, so only the two radios protect it.
        if directOnlyByRadio {
            return ComposerHint(
                text: String(
                    localized: """
                        Only your two radios protect this, so anyone holding either one can read it. \
                        Share a room with them to seal it to their phone.
                        """
                ),
                tone: .warn
            )
        }
        if losesSignature {
            return ComposerHint(
                text: String(localized: "Over \(MeshConstants.signedBroadcastTextBudget) bytes: sent unsigned"),
                tone: .warn
            )
        }
        if draftBytes >= 150 {
            return ComposerHint(text: String(localized: "\(remainingBytes) bytes left"), tone: .quiet)
        }
        return nil
    }
}

/// Wording for the chats screens, in the same words Android uses.
enum ChatsCopy {
    /// WISMESH_TAG reads as shouting; the radio's own name does not.
    static func prettyHardware(_ model: String) -> String {
        model.split(separator: "_")
            .filter { !$0.allSatisfy(\.isWhitespace) }
            .map { part in
                let lower = part.lowercased()
                return lower.prefix(1).uppercased() + lower.dropFirst()
            }
            .joined(separator: " ")
    }

    /// Which node we are speaking through, and how much charge it has left. The name above is the person; this says
    /// which radio is carrying them today.
    static func nodeStatus(connected: Bool, myNode: MeshNode?) -> String {
        guard connected else { return String(localized: "Not connected — open Settings") }
        guard let myNode else { return String(localized: "Connected") }
        var parts: [String] = []
        if let model = myNode.hwModel, !model.allSatisfy(\.isWhitespace) {
            parts.append(prettyHardware(model))
        }
        if let level = myNode.batteryLevel {
            parts.append(level > 100 ? String(localized: "powered") : "\(level)%")
        }
        return parts.isEmpty ? String(localized: "Connected") : parts.joined(separator: " · ")
    }

    /// The line under a room's name. Before anyone has spoken, it says who would be able to hear it.
    static func channelPreview(channel: RoomChannel, latest: ChatMessage?, senderName: String?) -> String {
        guard let latest else {
            return channel.isRoom ? channel.kind.readableBy : String(localized: "Primary channel")
        }
        if latest.isOutgoing { return latest.text }
        if let senderName { return "\(senderName): \(latest.text)" }
        return latest.text
    }

    static func emptyList(filter: ChannelFilter, channels: [RoomChannel]) -> String {
        if filter == .direct {
            return String(
                localized: """
                    No direct messages yet. To start one, tap someone in a room's info, or open them in \
                    Settings → Nodes.
                    """
            )
        }
        if !channels.contains(where: { $0.role != .disabled }) {
            return String(localized: "No channels yet. Connect your node in Settings.")
        }
        return String(localized: "No rooms yet. Create one with the + button.")
    }

    /// What the room has told us about a message we sent. Counts only what arrived: silence carries no information,
    /// so nobody is ever reported as not having read it.
    static func receiptFootnote(_ receipts: [Receipt]?) -> String? {
        guard let receipts, !receipts.isEmpty else { return nil }
        let read = receipts.count { $0.state == .read }
        return read > 0 ? String(localized: "Read by \(read)") : String(localized: "Delivered to \(receipts.count)")
    }

    /// Only when it is worth knowing: a relayed message may be slow or stale, a direct one is unremarkable.
    static func hopsFootnote(_ hopsAway: Int?) -> String? {
        guard let hops = hopsAway, hops > 0 else { return nil }
        return hops == 1 ? String(localized: "1 hop") : String(localized: "\(hops) hops")
    }

    /// Wording that never implies more than the mesh actually told us.
    static func statusLabel(_ status: MessageStatus) -> String {
        switch status {
        case .queued: String(localized: "Waiting for the radio")
        case .sentToNode: String(localized: "Handed to your node")
        case .unknown: String(localized: "Handed to your node, no confirmation")
        case .unheard: String(localized: "Nobody repeated it")
        case .failed: String(localized: "Failed")
        case .reachedMesh: String(localized: "Heard by at least one node")
        case .delivered: String(localized: "Acknowledged by the recipient")
        case .received: String(localized: "Received")
        }
    }

    static func sendFailure(_ error: any Error) -> String {
        failureMessage(error, fallback: String(localized: "Could not send"))
    }
}

// MARK: - View model

/// The chat screen's state and actions. Ported from android/app/…/chat/ChatsViewModel.kt.
///
/// Repository subscriptions live inside `observe()`, so they end with the view that started them. The open
/// conversation's subscriptions are a single task that is replaced whenever the conversation changes — Kotlin's
/// `flatMapLatest` — so a room left behind can never write its messages into the one now on screen.
@MainActor
@Observable
final class ChatsViewModel {
    private var state = ChatsUiState()
    private(set) var receiptsOnScreen: [Int32: [Receipt]] = [:]
    private(set) var inspectedReceipts: [Receipt] = []
    /// False until the open conversation's first read, so an empty state never flashes before it loads.
    private(set) var messagesLoaded = false

    /// The state as the screen sees it: the chosen name is read live from PersonStore, and the inspected message is
    /// re-read from the open list so its status keeps moving while the sheet is up.
    var uiState: ChatsUiState {
        var current = state
        current.person = people.person
        current.inspecting = state.inspecting.map { open in state.messages.first { $0.id == open.id } ?? open }
        return current
    }

    @ObservationIgnored private let repository: MeshRepository
    @ObservationIgnored private let channelState: ChannelStateDao
    @ObservationIgnored private let presence: ChatPresence
    @ObservationIgnored private let people: PersonStore
    @ObservationIgnored private let receipts: ReceiptRepository
    @ObservationIgnored private let history: RoomHistory
    @ObservationIgnored private let rooms: RoomRepository
    @ObservationIgnored private let quickReplyStore: QuickReplyStore

    /// What ⚡ offers above the composer (UX §5.4).
    var quickReplies: [String] { quickReplyStore.replies }

    /// Where words go. Switched at once, while `state.directPeer` waits for the seal flag so the two change together.
    @ObservationIgnored private var openChannel: Int?
    @ObservationIgnored private var openPeer: Int32?
    @ObservationIgnored private var nodeList: [MeshNode] = []
    @ObservationIgnored private var conversation: Task<Void, Never>?
    @ObservationIgnored private var receiptsTask: Task<Void, Never>?
    @ObservationIgnored private var receiptIds: [Int32] = []
    @ObservationIgnored private var inspection: Task<Void, Never>?

    init(
        repository: MeshRepository,
        channelState: ChannelStateDao,
        presence: ChatPresence,
        people: PersonStore,
        receipts: ReceiptRepository,
        history: RoomHistory,
        rooms: RoomRepository,
        quickReplyStore: QuickReplyStore = QuickReplyStore()
    ) {
        self.quickReplyStore = quickReplyStore
        self.repository = repository
        self.channelState = channelState
        self.presence = presence
        self.people = people
        self.receipts = receipts
        self.history = history
        self.rooms = rooms
    }

    convenience init(app: AppContainer) {
        self.init(
            repository: app.mesh,
            channelState: app.channelStateDao,
            presence: app.chatPresence,
            people: app.people,
            receipts: app.receipts,
            history: app.history,
            rooms: app.rooms,
            quickReplyStore: app.quickReplies
        )
    }

    deinit {
        conversation?.cancel()
        receiptsTask?.cancel()
        inspection?.cancel()
    }

    /// Follows the repositories while the chats pane is on screen. Call from `.task`; returns when cancelled.
    func observe() async {
        let repository = repository
        let channelState = channelState
        let rooms = rooms
        await awaitObservers([
            Task {
                for await value in repository.isConnected.subscribe() { self.state.connected = value }
            },
            Task {
                for await value in repository.channels.subscribe() { self.state.channels = value }
            },
            Task {
                for await value in repository.observeNodes() {
                    self.nodeList = value
                    self.state.nodes = Dictionary(value.map { ($0.nodeNum, $0) }, uniquingKeysWith: { _, last in last })
                    self.refreshMyNode()
                }
            },
            Task {
                for await _ in repository.myNodeNum.subscribe() { self.refreshMyNode() }
            },
            Task {
                for await value in rooms.observePersonCards() { self.state.cards = value }
            },
            Task {
                for await value in channelState.observeUnread() {
                    self.state.unread = Dictionary(
                        value.map { ($0.channel, $0.count) }, uniquingKeysWith: { _, last in last })
                }
            },
            Task {
                for await value in channelState.observeMuted() { self.state.muted = value }
            },
            Task {
                for await value in repository.channelLoad.subscribe() { self.state.channelLoad = value }
            },
            Task {
                for await value in repository.observeLatestPerChannel() {
                    self.state.latest = Dictionary(
                        value.map { ($0.channel, $0) }, uniquingKeysWith: { _, last in last })
                }
            },
            Task {
                for await value in repository.observeDirectLatest() {
                    self.state.directLatest = value.sorted { $0.sentAt > $1.sentAt }
                }
            },
            Task {
                for await value in repository.snapshot.subscribe() {
                    self.state.signingAvailable = value?.capabilities.supportsSigning == true
                }
            },
        ])
    }

    private func refreshMyNode() {
        let me = repository.myNodeNum.value
        state.myNode = nodeList.first { $0.nodeNum == me }
    }

    func updateQuery(_ text: String) {
        state.query = text
    }

    /// Remembered against the room as well, so it survives the room moving slot or radio.
    func toggleMute(_ channel: Int) {
        let muted = !state.muted.contains(channel)
        let history = history
        Task { try? await history.setMuted(channel: channel, muted: muted) }
    }

    func select(_ index: Int?) {
        openChannel = index
        openPeer = nil
        state.selected = index
        clearComposing()
        presence.setOpenChannel(index)
        follow(channel: index, peer: nil)
    }

    /// Opens the conversation with one person.
    func openDirect(_ peer: Int32) {
        openPeer = peer
        openChannel = nil
        state.selected = nil
        clearComposing()
        // Not a channel, so nothing to suppress notifications for by index.
        presence.setOpenChannel(nil)
        follow(channel: nil, peer: peer)
    }

    private func clearComposing() {
        state.error = nil
        state.replyingTo = nil
        inspect(nil)
        state.query = ""
    }

    func startReply(_ message: ChatMessage) {
        state.replyingTo = message
    }

    func cancelReply() {
        state.replyingTo = nil
    }

    func inspect(_ message: ChatMessage?) {
        state.inspecting = message
        inspection?.cancel()
        inspection = nil
        inspectedReceipts = []
        guard let message else { return }
        let receipts = receipts
        inspection = Task {
            for await value in receipts.observe(messageId: message.id) {
                guard !Task.isCancelled else { break }
                self.inspectedReceipts = value
            }
        }
    }

    func updateDraft(_ text: String) {
        // Hard stop rather than a warning: the radio would reject the send, and truncating at a byte index would split
        // Arabic or emoji. A room's budget is smaller than the radio's, because sealing costs bytes.
        state.draft = MeshConstants.truncateToBytes(text, maxBytes: uiState.textBudget)
        state.error = nil
    }

    func send() {
        let text = state.draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, deliver(text, replyId: state.replyingTo?.id) else { return }
        state.draft = ""
        state.replyingTo = nil
    }

    /// Sends a quick reply as it is, leaving anything half-typed in the composer alone. A reply being written answers
    /// a message, and so does this.
    func sendQuickReply(_ text: String) {
        if deliver(text, replyId: state.replyingTo?.id) { state.replyingTo = nil }
    }

    /// Reacts to `message` with one of the six (UX §5.4): one small packet of its own, sealed as the conversation's
    /// words are. Your latest reaction to a message is the one that counts, so choosing again replaces it.
    func react(_ message: ChatMessage, emoji: String) {
        guard Reactions.choices.contains(emoji) else { return }
        deliver(emoji, replyId: message.id, reaction: true)
    }

    /// A message that failed goes out again as a new attempt, which takes its place (UX §5.4).
    func sendAgain(_ message: ChatMessage) {
        guard message.isOutgoing, message.status.isFailure else { return }
        if state.inspecting?.id == message.id { inspect(nil) }
        let repository = repository
        Task {
            do {
                try await repository.sendAgain(message)
            } catch {
                self.state.error = ChatsCopy.sendFailure(error)
            }
        }
    }

    /// False when no conversation is open to send to.
    @discardableResult
    private func deliver(_ text: String, replyId: Int32?, reaction: Bool = false) -> Bool {
        let peer = openPeer
        let channel = openChannel
        guard peer != nil || channel != nil else { return false }
        let repository = repository
        Task {
            do {
                if let peer {
                    // The channel index is ignored for a direct message: the firmware encrypts to the recipient's key
                    // and puts a channel hash of 0 on the wire.
                    try await repository.sendText(channel: 0, text: text, to: peer, replyId: replyId, reaction: reaction)
                } else if let channel {
                    try await repository.sendText(channel: channel, text: text, replyId: replyId, reaction: reaction)
                }
            } catch {
                self.state.error = ChatsCopy.sendFailure(error)
            }
        }
        return true
    }

    /// Replaces the open conversation's subscriptions: its messages, the seal flag for a person, receipts for what we
    /// sent, and read reporting.
    private func follow(channel: Int?, peer: Int32?) {
        conversation?.cancel()
        receiptsTask?.cancel()
        receiptsTask = nil
        receiptIds = []
        receiptsOnScreen = [:]
        state.messages = []
        messagesLoaded = false
        if peer == nil {
            state.directPeer = nil
            state.directSealed = false
        }
        let source: AsyncStream<[ChatMessage]>
        if let peer {
            source = repository.observeDirect(peer: peer)
        } else if let channel {
            source = repository.observeChannel(channel: channel)
        } else {
            conversation = nil
            return
        }
        let repository = repository
        let presence = presence
        var observers = [
            Task {
                for await messages in source {
                    guard !Task.isCancelled else { break }
                    self.state.messages = messages.filter { !Reactions.isReaction($0) }
                    self.state.reactions = Reactions.countsByTarget(messages, myNodeNum: self.state.myNode?.nodeNum)
                    self.messagesLoaded = true
                    self.followReceipts(for: messages)
                    await self.markRead(channel: channel, messages: messages)
                    await self.reportRead(channel: channel, peer: peer)
                }
            },
            // On screen and looked at is the only honest definition of read, so coming back counts too.
            Task {
                for await foreground in presence.foreground.subscribe() where foreground {
                    guard !Task.isCancelled else { break }
                    await self.reportRead(channel: channel, peer: peer)
                }
            },
        ]
        if let peer {
            observers.append(
                Task {
                    for await sealed in repository.observeDirectSealed(peer: peer) {
                        guard !Task.isCancelled else { break }
                        // Published together, so the budget and the warning never describe the wrong person.
                        self.state.directPeer = peer
                        self.state.directSealed = sealed
                    }
                })
        }
        conversation = Task { await awaitObservers(observers) }
    }

    /// Receipts for the messages we sent that are on screen, so a sender can see them arrive. Reactions get none.
    private func followReceipts(for messages: [ChatMessage]) {
        let ids = messages.filter { $0.isOutgoing && !Reactions.isReaction($0) }.map(\.id)
        guard ids != receiptIds else { return }
        receiptIds = ids
        receiptsTask?.cancel()
        let receipts = receipts
        receiptsTask = Task {
            for await value in receipts.observeAll(messageIds: ids) {
                guard !Task.isCancelled else { break }
                self.receiptsOnScreen = value
            }
        }
    }

    /// Reading the open conversation is what marks it read, so a message arriving while it is on screen never becomes
    /// a stale badge.
    private func markRead(channel: Int?, messages: [ChatMessage]) async {
        guard let channel, !messages.isEmpty else { return }
        let roomId = history.roomIn(channel: channel)
        try? await channelState.markRead(channel: channel, now: currentEpochMillis(), roomId: roomId)
    }

    private func reportRead(channel: Int?, peer: Int32?) async {
        guard presence.foreground.value, channel != nil || peer != nil else { return }
        // `state.messages` holds no reactions: they show no ticks, so they are not reported.
        let incoming = Set(state.messages.filter { !$0.isOutgoing && !$0.isNotice }.map(\.id))
        await receipts.read(channel: channel ?? 0, messageIds: incoming, peer: peer)
    }
}
