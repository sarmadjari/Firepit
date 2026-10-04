import FirepitData
import FirepitModel
import FirepitProtocol
import SwiftUI

/// Where the chats stack can go. Android draws the room screens as overlays that take the whole display; on iPhone
/// they are pushed, which gives them the same full-screen presence and the system back gesture. The stack itself
/// lives in `Workspace`, so it outlives a change of layout.
enum ChatRoute: Hashable {
    /// `searching` opens the room with its search bar up, for the list's search button.
    case channel(Int, searching: Bool = false)
    case direct(Int32)
    case members(roomId: Int32, roomName: String, channelIndex: Int)
    case invite(roomId: Int32, roomName: String)
    case join

    var isConversation: Bool {
        switch self {
        case .channel, .direct: true
        case .members, .invite, .join: false
        }
    }
}

/// Chats: the list of rooms and people, and the conversations pushed over it. Ported from
/// android/app/…/chat/ChatsPane.kt.
struct ChatsPane: View {
    let app: AppContainer
    /// Kept for the life of the view: a screen shown on its own is re-initialised whenever its parent redraws, and a
    /// fresh workspace each time would empty its stack.
    @State private var workspace: Workspace

    @State private var viewModel: ChatsViewModel
    @State private var rooms: RoomsViewModel
    @State private var creating = false
    @Environment(\.besideMap) private var besideMap
    @Environment(\.listBesideConversation) private var listBesideConversation

    /// A chats screen of its own, for a screen shown on its own.
    init(app: AppContainer) {
        self.init(app: app, workspace: Workspace(app: app))
    }

    /// The chat side of the shell, whose models and stack outlive a change of layout.
    init(app: AppContainer, workspace: Workspace) {
        self.app = app
        _workspace = State(initialValue: workspace)
        _viewModel = State(initialValue: workspace.chats)
        _rooms = State(initialValue: workspace.rooms)
    }

    /// The stack, read and written through the workspace.
    private var path: [ChatRoute] {
        get { workspace.chatPath }
        nonmutating set { workspace.chatPath = newValue }
    }

    /// The last room opened, which the list's search button returns to: search reads a conversation, so it needs one.
    private var lastChannel: Int? {
        get { workspace.lastChannel }
        nonmutating set { workspace.lastChannel = newValue }
    }

    #if DEBUG
        /// Where `-route chat.*` starts, so each screen can be checked without tapping through to it.
        private var debugStart: ChatsDebugStart?

        init(app: AppContainer, debugStart: ChatsDebugStart) {
            self.init(app: app)
            self.debugStart = debugStart
        }
    #endif

    var body: some View {
        @Bindable var router = app.router
        @Bindable var workspace = workspace
        // The conversation stack is the row's last child either way, so going between two and three panes keeps it,
        // and whatever is open in it.
        HStack(spacing: 0) {
            if listBesideConversation {
                // Three panes (UX §6.11.3): the list keeps its own width beside the conversation.
                NavigationStack { channelList }
                    .frame(width: PaneLayouts.listWidth)
                FirepitColors.outline.frame(width: 1).ignoresSafeArea()
            }
            NavigationStack(path: $workspace.chatPath) {
                Group {
                    if listBesideConversation {
                        Text("Pick a channel to start reading.")
                            .font(FirepitFont.bodyMedium)
                            .foregroundStyle(FirepitColors.textSecondary)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .background(FirepitColors.surface)
                    } else {
                        channelList
                    }
                }
                .navigationDestination(for: ChatRoute.self, destination: destination)
            }
        }
        .sheet(isPresented: $creating) {
            CreateRoomDialog(
                onDismiss: { creating = false },
                onCreate: { name in
                    creating = false
                    rooms.createRoom(name: name)
                },
                onCreateShared: { name in
                    creating = false
                    rooms.addSharedChannel(name: name)
                }
            )
        }
        .sheet(isPresented: Binding(get: { rooms.askToMakeRadioPrivate }, set: { _ in })) {
            MakeRadioPrivateDialog(onMakePrivate: rooms.makeRadioPrivate, onKeepPublic: rooms.keepRadioPublic)
                .interactiveDismissDisabled()
        }
        .onChange(of: workspace.chatPath) { old, new in leave(from: old, to: new) }
        .onChange(of: router.pendingChannel, initial: true) { _, channel in
            guard let channel else { return }
            router.pendingChannel = nil
            path.removeAll()
            open(channel: channel)
        }
        .onChange(of: router.pendingDirect, initial: true) { _, peer in
            guard let peer else { return }
            router.pendingDirect = nil
            path.removeAll()
            open(direct: peer)
        }
        .onChange(of: router.pendingInvite, initial: true) { _, link in
            // The join screen takes the link from the router itself.
            guard link != nil, path.last != .join else { return }
            rooms.clearMessages()
            path.append(.join)
        }
        .task { await viewModel.observe() }
        .task { await rooms.observe() }
        #if DEBUG
            .task { await startDebug() }
        #endif
    }

    private var channelList: some View {
        @Bindable var workspace = workspace
        return ChannelList(
            state: viewModel.uiState,
            rooms: rooms.uiState,
            filter: $workspace.chatFilter,
            canSearch: lastChannel != nil,
            select: { open(channel: $0) },
            openDirect: { open(direct: $0) },
            toggleMute: viewModel.toggleMute,
            newRoom: { creating = true },
            joinRoom: {
                // A leftover "Created Camp" notice would otherwise close the scanner the moment it opens.
                rooms.clearMessages()
                path.append(.join)
            },
            search: {
                guard let lastChannel else { return }
                open(channel: lastChannel, searching: true)
            },
            dismissMessage: rooms.clearMessages
        )
    }

    #if DEBUG
        private func startDebug() async {
            guard let debugStart else { return }
            func message(_ id: Int32) async -> ChatMessage? {
                for _ in 0..<60 {
                    if let found = viewModel.uiState.messages.first(where: { $0.id == id }) { return found }
                    try? await Task.sleep(for: .milliseconds(50))
                }
                return nil
            }
            switch debugStart {
            case .room(let index):
                open(channel: index)
            case .search(let index):
                open(channel: index, searching: true)
            case .direct:
                for _ in 0..<60 where viewModel.uiState.directLatest.isEmpty || viewModel.uiState.myNode == nil {
                    try? await Task.sleep(for: .milliseconds(50))
                }
                if let latest = viewModel.uiState.directLatest.first {
                    open(direct: latest.peerOf(myNodeNum: viewModel.uiState.myNode?.nodeNum))
                }
            case .info(let id):
                open(channel: 1)
                if let found = await message(id) { viewModel.inspect(found) }
            case .reply(let id):
                open(channel: 1)
                if let found = await message(id) { viewModel.startReply(found) }
            case .newRoom:
                creating = true
            }
        }
    #endif

    @ViewBuilder private func destination(_ route: ChatRoute) -> some View {
        switch route {
        case .channel(let index, let searching):
            ChannelChat(
                viewModel: viewModel,
                rooms: rooms,
                index: index,
                startSearching: searching,
                sharing: besideMap ? workspace.sharing.state : nil,
                showMembers: { channel in
                    path.append(
                        .members(roomId: channel.id, roomName: channel.displayName, channelIndex: channel.index))
                }
            )
        case .direct(let peer):
            DirectChat(viewModel: viewModel, peer: peer)
        case .members(let roomId, let roomName, let channelIndex):
            RoomMembersScreen(
                roomId: roomId,
                channelIndex: channelIndex,
                roomName: roomName,
                onBack: { path.removeLast() },
                muted: viewModel.uiState.muted.contains(channelIndex),
                kind: viewModel.uiState.channels.first { $0.index == channelIndex }?.kind ?? .meshtasticPublic,
                onInvite: { path.append(.invite(roomId: roomId, roomName: roomName)) },
                onToggleMute: { viewModel.toggleMute(channelIndex) },
                // Leaving the room leaves its conversation too: there is nothing left to go back to.
                onLeft: { path.removeAll() },
                onMessage: { peer in
                    path.removeAll()
                    open(direct: peer)
                },
                viewModel: rooms,
                sharingViewModel: SharingViewModel(location: app.location, mesh: app.mesh)
            )
            .toolbar(.hidden, for: .tabBar)
        case .invite(let roomId, let roomName):
            InviteScreen(
                roomId: roomId,
                roomName: roomName,
                onBack: { path.removeLast() },
                viewModel: rooms,
                secureWindow: app.secureWindow
            )
            .toolbar(.hidden, for: .tabBar)
        case .join:
            JoinRoomScreen(onBack: { path.removeLast() }, viewModel: rooms, router: app.router)
                .toolbar(.hidden, for: .tabBar)
        }
    }

    /// Opening a conversation starts the stack from it. On a phone the stack is empty here anyway; with the list
    /// beside the conversation, stacking one on another would let Back show one while Send went to the other.
    private func open(channel index: Int, searching: Bool = false) {
        lastChannel = index
        viewModel.select(index)
        path = [.channel(index, searching: searching)]
    }

    private func open(direct peer: Int32) {
        viewModel.openDirect(peer)
        path = [.direct(peer)]
    }

    /// Back gestures and buttons both land here, so the bookkeeping cannot be skipped by how somebody leaves.
    private func leave(from old: [ChatRoute], to new: [ChatRoute]) {
        guard new.count < old.count else { return }
        // Android clears the rooms notice whenever one of its overlays closes.
        if old.dropFirst(new.count).contains(where: { !$0.isConversation }) {
            rooms.clearMessages()
        }
        // Read means on screen. Android keeps the last room selected after the phone goes back to the list, which
        // marks its new messages read and silences them while nobody is looking; here leaving the conversation
        // leaves it.
        if !new.contains(where: \.isConversation) {
            viewModel.select(nil)
        } else if case .channel(let index, _) = new.last, viewModel.uiState.selected != index {
            viewModel.select(index)
        } else if case .direct(let peer) = new.last, viewModel.uiState.directPeer != peer {
            viewModel.openDirect(peer)
        }
    }
}

// MARK: - The list

private struct ChannelList: View {
    let state: ChatsUiState
    let rooms: RoomsUiState
    @Binding var filter: ChannelFilter
    let canSearch: Bool
    let select: (Int) -> Void
    let openDirect: (Int32) -> Void
    let toggleMute: (Int) -> Void
    let newRoom: () -> Void
    let joinRoom: () -> Void
    let search: () -> Void
    let dismissMessage: () -> Void

    /// The radio has eight slots; leaving a room frees one.
    private var canAddRoom: Bool { state.connected && !rooms.isFull && !rooms.busy }
    private var title: String { state.person?.name ?? state.myNode?.displayName ?? "Firepit" }
    /// The primary sets the radio's frequency and carries NodeInfo. Firepit never converses there, so it is not a chat.
    private var visible: [RoomChannel] { state.channels.filter { $0.role != .disabled && $0.isRoom } }
    private var showRooms: Bool { filter != .direct }
    private var showDirect: Bool { filter != .rooms }
    private var isEmpty: Bool {
        (!showRooms || visible.isEmpty) && (!showDirect || state.directLatest.isEmpty)
    }

    var body: some View {
        List {
            VStack(alignment: .leading, spacing: FirepitSpacing.m) {
                NodeStatusLine(connected: state.connected, myNode: state.myNode)
                ChipRow {
                    ForEach(ChannelFilter.allCases, id: \.self) { option in
                        FirepitChip(verbatim: option.label, selected: filter == option) { filter = option }
                    }
                }
            }
            .plainRow()

            if let message = rooms.error ?? rooms.joinedRoomName {
                HStack {
                    Text(verbatim: message)
                        .font(FirepitFont.bodyMedium)
                        .foregroundStyle(FirepitColors.textPrimary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button("Dismiss", action: dismissMessage)
                        .foregroundStyle(FirepitColors.primary)
                }
                .plainRow()
            }

            if isEmpty {
                Text(verbatim: ChatsCopy.emptyList(filter: filter, channels: state.channels))
                    .font(FirepitFont.bodyMedium)
                    .foregroundStyle(FirepitColors.textSecondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(FirepitSpacing.xl)
                    .plainRow()
            } else {
                if showRooms {
                    ForEach(visible, id: \.index) { channel in
                        let latest = state.latest[channel.index]
                        ChatChannelRow(
                            channel: channel,
                            latest: latest,
                            senderName: latest.map { state.nameOf($0.fromNodeNum) },
                            unread: state.unread[channel.index] ?? 0,
                            muted: state.muted.contains(channel.index),
                            open: { select(channel.index) },
                            toggleMute: { toggleMute(channel.index) }
                        )
                    }
                }
                if showDirect {
                    ForEach(state.directLatest, id: \.self) { message in
                        let peer = message.peerOf(myNodeNum: state.myNode?.nodeNum)
                        DirectRow(
                            peer: peer,
                            tag: state.nodes[peer]?.shortName,
                            name: state.nameOf(peer),
                            latest: message,
                            open: { openDirect(peer) }
                        )
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
        .navigationTitle(Text(verbatim: title))
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                if let node = state.myNode {
                    IdentityAvatar(
                        nodeNum: node.nodeNum, tag: state.person?.tag ?? node.shortName, size: 32
                    )
                    .accessibilityHidden(true)
                }
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button(action: search) { Image(icon: .search) }
                    .disabled(!canSearch)
                    .accessibilityLabel(Text("Search messages"))
                LayoutMenu()
                WideScreenSettingsButton()
                Button(action: newRoom) { Image(icon: .add) }
                    .disabled(!canAddRoom)
                    .accessibilityLabel(Text("New room"))
                Menu {
                    Button(action: newRoom) {
                        Text(rooms.isFull ? "New room — no free slots" : "New room")
                    }
                    .disabled(!canAddRoom)
                    Button(action: joinRoom) {
                        Text(rooms.isFull ? "Scan invite — no free slots" : "Scan invite")
                    }
                    .disabled(!canAddRoom)
                } label: {
                    Image(icon: .more)
                }
                .accessibilityLabel(Text("More"))
            }
        }
    }
}

/// Which node we are speaking through, and how much charge it has left. The radio is a separate object that can be
/// left behind or run flat, so its name and battery belong on the first screen rather than buried in settings.
private struct NodeStatusLine: View {
    let connected: Bool
    let myNode: MeshNode?

    var body: some View {
        HStack(spacing: FirepitSpacing.xs) {
            LiveRing(size: 8, live: connected)
            Text(verbatim: ChatsCopy.nodeStatus(connected: connected, myNode: myNode))
                .font(FirepitFont.bodySmall)
                .foregroundStyle(FirepitColors.textSecondary)
                .lineLimit(1)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct ChatChannelRow: View {
    let channel: RoomChannel
    let latest: ChatMessage?
    let senderName: String?
    let unread: Int
    let muted: Bool
    let open: () -> Void
    let toggleMute: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Button(action: open) {
            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    // At the largest sizes one line cannot hold a name, a badge and a time, so each gets its own.
                    VStack(alignment: .leading, spacing: FirepitSpacing.xs) {
                        HStack(alignment: .top) {
                            RoomAvatar(icon: RoomIcon.forRoomId(channel.iconSeed))
                            Spacer(minLength: FirepitSpacing.s)
                            HStack(spacing: FirepitSpacing.xs) {
                                time
                                badges
                            }
                        }
                        name.lineLimit(3)
                        if channel.isRoom { RoomKindBadge(kind: channel.kind) }
                        preview.lineLimit(2)
                    }
                } else {
                    HStack(spacing: FirepitSpacing.m) {
                        RoomAvatar(icon: RoomIcon.forRoomId(channel.iconSeed))
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: FirepitSpacing.xs) {
                                name.lineLimit(1)
                                if channel.isRoom { RoomKindBadge(kind: channel.kind) }
                            }
                            preview.lineLimit(1)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        VStack(alignment: .trailing, spacing: FirepitSpacing.xs) {
                            time
                            badges
                        }
                    }
                }
            }
            .padding(.vertical, FirepitSpacing.xs)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .listRowBackground(FirepitColors.surface)
        .swipeActions(edge: .trailing) {
            Button(action: toggleMute) {
                Label(muted ? "Unmute" : "Mute", systemImage: (muted ? FirepitIcon.bell : .mute).systemName)
            }
            .tint(FirepitColors.textSecondary)
        }
        .contextMenu {
            Button(action: toggleMute) {
                Label(muted ? "Unmute" : "Mute", systemImage: (muted ? FirepitIcon.bell : .mute).systemName)
            }
        }
    }

    private var name: some View {
        Text(verbatim: channel.displayName)
            .font(FirepitFont.titleMedium)
            .foregroundStyle(FirepitColors.textPrimary)
    }

    private var preview: some View {
        HStack(spacing: FirepitSpacing.xs) {
            if let latest, latest.isOutgoing { StatusTick(latest.status) }
            Text(verbatim: ChatsCopy.channelPreview(channel: channel, latest: latest, senderName: senderName))
                .font(FirepitFont.bodyMedium)
                .foregroundStyle(FirepitColors.textSecondary)
        }
    }

    private var time: some View {
        Text(verbatim: latest.map { MessageTimestamp.listFormat($0.sentAt) } ?? "")
            .font(FirepitFont.bodySmall)
            .foregroundStyle(unread > 0 ? FirepitColors.primary : FirepitColors.textSecondary)
            .lineLimit(1)
            .fixedSize()
    }

    private var badges: some View {
        HStack(spacing: FirepitSpacing.xs) {
            if muted {
                Image(icon: .mute)
                    .font(.system(size: 13))
                    .foregroundStyle(FirepitColors.textSecondary)
                    .accessibilityLabel(Text("Muted"))
            }
            // A muted room still counts unread; it just does not interrupt.
            UnreadBadge(count: unread)
        }
    }
}

private struct DirectRow: View {
    let peer: Int32
    let tag: String?
    let name: String
    let latest: ChatMessage
    let open: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Button(action: open) {
            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: FirepitSpacing.xs) {
                        HStack(alignment: .top) {
                            IdentityAvatar(nodeNum: peer, tag: tag)
                            Spacer(minLength: FirepitSpacing.s)
                            time
                        }
                        title.lineLimit(3)
                        preview.lineLimit(2)
                    }
                } else {
                    HStack(spacing: FirepitSpacing.m) {
                        IdentityAvatar(nodeNum: peer, tag: tag)
                        VStack(alignment: .leading, spacing: 2) {
                            title.lineLimit(1)
                            preview.lineLimit(1)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        time
                    }
                }
            }
            .padding(.vertical, FirepitSpacing.xs)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .listRowBackground(FirepitColors.surface)
    }

    private var title: some View {
        Text(verbatim: name)
            .font(FirepitFont.titleMedium)
            .foregroundStyle(FirepitColors.textPrimary)
    }

    private var preview: some View {
        HStack(spacing: FirepitSpacing.xs) {
            if latest.isOutgoing { StatusTick(latest.status) }
            Text(verbatim: latest.text)
                .font(FirepitFont.bodyMedium)
                .foregroundStyle(FirepitColors.textSecondary)
        }
    }

    private var time: some View {
        Text(verbatim: MessageTimestamp.listFormat(latest.sentAt))
            .font(FirepitFont.bodySmall)
            .foregroundStyle(FirepitColors.textSecondary)
            .lineLimit(1)
            .fixedSize()
    }
}

/// Which protocol a room speaks, and therefore who can read it. Shown wherever a room is named: the difference between
/// a sealed Firepit room and an ordinary Meshtastic channel is the difference between private and not, and somebody
/// typing has to be able to see it without going to look.
private struct RoomKindBadge: View {
    let kind: RoomKind

    private var tint: Color {
        switch kind {
        case .firepit: FirepitColors.primary
        case .meshtasticPrivate: FirepitColors.textSecondary
        case .meshtasticPublic: FirepitColors.warn
        case .unencrypted: FirepitColors.danger
        case .firepitKeyMissing, .firepitMovedOn: FirepitColors.warn
        }
    }

    var body: some View {
        Text(verbatim: kind.label)
            .font(FirepitFont.labelSmall)
            .foregroundStyle(tint)
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, 5)
            .padding(.vertical, 1)
            .overlay { RoundedRectangle(cornerRadius: 4).strokeBorder(tint.opacity(0.5), lineWidth: 1) }
    }
}

// MARK: - Conversations

/// A room's conversation.
private struct ChannelChat: View {
    @Bindable var viewModel: ChatsViewModel
    let rooms: RoomsViewModel
    let index: Int
    let showMembers: (RoomChannel) -> Void
    /// Location sharing, while the map sits beside the chat; nil otherwise.
    var sharing: SharingUiState?

    @State private var searching: Bool
    @State private var memberCount: Int?

    init(
        viewModel: ChatsViewModel,
        rooms: RoomsViewModel,
        index: Int,
        startSearching: Bool,
        sharing: SharingUiState? = nil,
        showMembers: @escaping (RoomChannel) -> Void
    ) {
        self.viewModel = viewModel
        self.rooms = rooms
        self.index = index
        self.sharing = sharing
        self.showMembers = showMembers
        _searching = State(initialValue: startSearching)
    }

    private var channel: RoomChannel? { viewModel.uiState.channels.first { $0.index == index } }
    /// Only a Firepit room keeps a roster; a Meshtastic channel has no member list to count.
    private var firepitRoomId: Int32? { channel?.kind == .firepit ? channel?.id : nil }

    var body: some View {
        let state = viewModel.uiState
        ConversationScroll(
            conversation: AnyHashable(index),
            items: buildChatItems(state.visibleMessages),
            reactions: state.reactions,
            autoScroll: !state.isSearching
        ) { item, scrollTo in
            ChatRow(item: item) { message, isFirst, isLast in
                bubble(message, isFirst: isFirst, isLast: isLast, state: state, scrollTo: scrollTo)
            }
        } empty: {
            if state.isSearching && state.visibleMessages.isEmpty {
                EmptyState(
                    text: String(
                        localized:
                            "No messages match \"\(state.query.trimmingCharacters(in: .whitespacesAndNewlines))\"."
                    ))
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            VStack(spacing: 0) {
                if searching {
                    RoomSearchBar(query: Binding(get: { state.query }, set: viewModel.updateQuery)) {
                        searching = false
                        viewModel.updateQuery("")
                    }
                } else if let channel, channel.isRoom {
                    // Who can read this, said where somebody typing can see it.
                    Text(verbatim: channel.kind.readableBy)
                        .font(FirepitFont.bodySmall)
                        .foregroundStyle(channel.kind.isPrivate ? FirepitColors.textSecondary : FirepitColors.warn)
                        .multilineTextAlignment(.center)
                        .lineLimit(3)
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, FirepitSpacing.screenMargin)
                        .padding(.vertical, FirepitSpacing.xs)
                }
            }
            .background(FirepitColors.surface)
            // A hairline, so messages scroll under the line rather than into it.
            .overlay(alignment: .bottom) {
                Rectangle().fill(FirepitColors.outline).frame(height: 1)
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            ConversationFooter(viewModel: viewModel)
        }
        .navigationBarTitleDisplayMode(.inline)
        .navigationTitle(Text(verbatim: channel?.displayName ?? ""))
        .toolbar(.hidden, for: .tabBar)
        // The map beside it shows the pill; this says which conversation the location goes to, where the people
        // reading it are.
        .safeAreaInset(edge: .top, spacing: 0) {
            if let sharing, let channel, sharing.roomId == channel.id {
                SharingChip(state: sharing)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, FirepitSpacing.screenMargin)
                    .padding(.vertical, FirepitSpacing.xs)
            }
        }
        .toolbar {
            ToolbarItem(placement: .principal) {
                if let channel {
                    RoomHeader(channel: channel, connected: state.connected, memberCount: memberCount)
                }
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    searching = true
                } label: {
                    Image(icon: .search)
                }
                .accessibilityLabel(Text("Search messages"))
                LayoutMenu()
                // Room actions live in Room info, so the bar stays narrow enough for the room's name and status.
                if let channel, channel.isRoom {
                    Button {
                        showMembers(channel)
                    } label: {
                        Image(icon: .more)
                    }
                    .accessibilityLabel(Text("Room info"))
                }
            }
        }
        .sheet(
            item: Binding(
                get: { viewModel.uiState.inspecting },
                set: { if $0 == nil { viewModel.inspect(nil) } }
            )
        ) { message in
            MessageInfoSheet(viewModel: viewModel, opened: message)
        }
        .task(id: firepitRoomId) {
            memberCount = nil
            guard let roomId = firepitRoomId else { return }
            for await rows in rooms.members(roomId: roomId) {
                memberCount = rows.isEmpty ? nil : rows.count
            }
        }
    }

    private func bubble(
        _ message: ChatMessage,
        isFirst: Bool,
        isLast: Bool,
        state: ChatsUiState,
        scrollTo: @escaping (Int32) -> Void
    ) -> some View {
        let parent = state.repliedTo(message)
        return VStack(spacing: 0) {
            MessageBubble(
                text: message.text,
                time: MessageTimestamp.bubbleFormat(message.shownAt()),
                isOutgoing: message.isOutgoing,
                senderName: state.nameOf(message.fromNodeNum),
                senderNodeNum: message.fromNodeNum,
                status: message.isOutgoing ? message.status : nil,
                footnote: ChatsCopy.receiptFootnote(viewModel.receiptsOnScreen[message.id])
                    ?? ChatsCopy.hopsFootnote(message.hopsAway),
                quoted: parent.map {
                    QuotedMessage(senderName: state.nameOf($0.fromNodeNum), senderNodeNum: $0.fromNodeNum, text: $0.text)
                },
                onQuoteTap: parent.map { parent in { scrollTo(parent.id) } },
                highlight: state.isSearching ? highlightRanges(message.text, query: state.query) : [],
                isFirstInGroup: isFirst,
                isLastInGroup: isLast
            )
            .contentShape(.rect)
            .modifier(SwipeToReply { viewModel.startReply(message) })
            .onTapGesture { viewModel.inspect(message) }
            .contextMenu {
                ReactionMenu(
                    text: message.text,
                    onReact: { emoji in viewModel.react(message, emoji: emoji) },
                    onReply: { viewModel.startReply(message) },
                    onInfo: { viewModel.inspect(message) },
                    onSendAgain: message.isOutgoing && message.status.isFailure
                        ? { viewModel.sendAgain(message) } : nil
                )
            }
            .accessibilityAction(named: Text("Reply")) { viewModel.startReply(message) }
            if let counts = state.reactions[message.id] {
                ReactionRow(counts: counts, isOutgoing: message.isOutgoing) { emoji in
                    viewModel.react(message, emoji: emoji)
                }
            }
        }
    }
}

/// The room's name, kind, size and link state, in the navigation bar.
private struct RoomHeader: View {
    let channel: RoomChannel
    let connected: Bool
    let memberCount: Int?

    var body: some View {
        HStack(spacing: FirepitSpacing.s) {
            RoomAvatar(icon: RoomIcon.forRoomId(channel.iconSeed), size: 32)
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: FirepitSpacing.xs) {
                    Text(verbatim: channel.displayName)
                        .font(FirepitFont.titleMedium)
                        .foregroundStyle(FirepitColors.textPrimary)
                        .lineLimit(1)
                    if channel.isRoom { RoomKindBadge(kind: channel.kind) }
                }
                HStack(spacing: FirepitSpacing.xs) {
                    if let memberCount {
                        Text(memberCount == 1 ? "1 member ·" : "\(memberCount) members ·")
                    }
                    LiveRing(size: 8, live: connected)
                    Text(connected ? "connected" : "not connected")
                }
                .font(FirepitFont.bodySmall)
                .foregroundStyle(FirepitColors.textSecondary)
                .lineLimit(1)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// One-to-one conversation. Shares the bubbles and composer with a room so a private word looks like a word, not a
/// different product; the header names the person instead.
private struct DirectChat: View {
    @Bindable var viewModel: ChatsViewModel
    let peer: Int32

    var body: some View {
        let state = viewModel.uiState
        let name = state.nameOf(peer)
        ConversationScroll(
            conversation: AnyHashable(peer),
            items: buildChatItems(state.messages),
            reactions: state.reactions,
            autoScroll: true
        ) { item, _ in
            ChatRow(item: item) { message, isFirst, isLast in
                VStack(spacing: 0) {
                    MessageBubble(
                        text: message.text,
                        time: MessageTimestamp.bubbleFormat(message.shownAt()),
                        isOutgoing: message.isOutgoing,
                        senderName: nil,
                        senderNodeNum: message.fromNodeNum,
                        status: message.isOutgoing ? message.status : nil,
                        isFirstInGroup: isFirst,
                        isLastInGroup: isLast
                    )
                    .contentShape(.rect)
                    .contextMenu {
                        ReactionMenu(
                            text: message.text,
                            onReact: { emoji in viewModel.react(message, emoji: emoji) },
                            onSendAgain: message.isOutgoing && message.status.isFailure
                                ? { viewModel.sendAgain(message) } : nil
                        )
                    }
                    if let counts = state.reactions[message.id] {
                        ReactionRow(counts: counts, isOutgoing: message.isOutgoing) { emoji in
                            viewModel.react(message, emoji: emoji)
                        }
                    }
                }
            }
        } empty: {
            if viewModel.messagesLoaded && state.messages.isEmpty {
                EmptyState(text: String(localized: "No messages with \(name) yet."))
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            ConversationFooter(viewModel: viewModel)
        }
        .navigationBarTitleDisplayMode(.inline)
        .navigationTitle(Text(verbatim: name))
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .principal) {
                HStack(spacing: FirepitSpacing.s) {
                    IdentityAvatar(nodeNum: peer, tag: state.nodes[peer]?.shortName, size: 32)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(verbatim: name)
                            .font(FirepitFont.titleMedium)
                            .foregroundStyle(FirepitColors.textPrimary)
                            .lineLimit(1)
                        Text("Direct message")
                            .font(FirepitFont.bodySmall)
                            .foregroundStyle(FirepitColors.textSecondary)
                    }
                }
                .accessibilityElement(children: .combine)
            }
            ToolbarItem(placement: .topBarTrailing) {
                LayoutMenu()
            }
        }
    }
}

/// A day marker, a notice, or a message, spaced tight inside a block and open between speakers.
private struct ChatRow<Bubble: View>: View {
    let item: ChatItem
    @ViewBuilder let bubble: (ChatMessage, _ isFirstInGroup: Bool, _ isLastInGroup: Bool) -> Bubble

    var body: some View {
        switch item {
        case .day(let label, _):
            SystemChip(text: label).padding(.vertical, FirepitSpacing.m)
        case .notice(_, let text):
            SystemChip(text: text).padding(.vertical, FirepitSpacing.s)
        case .bubble(let message, let isFirstInGroup, let isLastInGroup):
            bubble(message, isFirstInGroup, isLastInGroup)
                .padding(.top, isFirstInGroup ? FirepitSpacing.s : 2)
        }
    }
}

/// The scrolling body of a conversation, opened at its newest message.
///
/// The first landing jumps; later arrivals animate. Somebody opening a room asked for the conversation, not a scroll
/// through it, but a message arriving while they read is worth seeing move. Autoscroll pauses during a search, where
/// jumping to the newest message would fight the reader. A reaction makes its message taller without adding one:
/// whoever was reading the newest message keeps it in view, and whoever was reading further up stays where they were.
private struct ConversationScroll<Row: View, Empty: View>: View {
    let conversation: AnyHashable
    let items: [ChatItem]
    let reactions: [Int32: [Reactions.Count]]
    let autoScroll: Bool
    @ViewBuilder let row: (ChatItem, @escaping (Int32) -> Void) -> Row
    @ViewBuilder let empty: () -> Empty

    @State private var landed = false
    /// Whether the newest message is on screen.
    @State private var atNewest = false
    /// The latest jump to the newest message, which the settling landing follows.
    @State private var landing = Landing()

    private struct Landing {
        var count = 0
        var animated = false
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(items) { item in
                        row(item) { id in
                            withAnimation { proxy.scrollTo(ChatItem.Key.message(id), anchor: .center) }
                        }
                        .id(item.id)
                        .onAppear { if item.id == items.last?.id { atNewest = true } }
                        .onDisappear { if item.id == items.last?.id { atNewest = false } }
                    }
                }
                .padding(.horizontal, FirepitSpacing.screenMargin)
                .padding(.vertical, FirepitSpacing.s)
            }
            .overlay { empty() }
            .scrollDismissesKeyboard(.interactively)
            .background(FirepitColors.surface)
            .onAppear { land(proxy, animated: false) }
            .onChange(of: items.last?.id) { land(proxy, animated: landed) }
            .onChange(of: autoScroll) { land(proxy, animated: true) }
            .onChange(of: reactions) { if landed && atNewest { land(proxy, animated: true) } }
            .task(id: autoScroll ? landing.count : -1) {
                // A jump aims with estimated heights for the rows the lazy stack has not drawn, and rows drawn on the
                // way can turn out taller. Once they are drawn, land exactly, without animation. A search starting
                // in the meantime cancels it.
                guard autoScroll, landing.count > 0 else { return }
                try? await Task.sleep(for: landing.animated ? .milliseconds(450) : .milliseconds(60))
                guard !Task.isCancelled, let last = items.last?.id else { return }
                proxy.scrollTo(last, anchor: .bottom)
            }
        }
    }

    private func land(_ proxy: ScrollViewProxy, animated: Bool) {
        guard autoScroll, let last = items.last?.id else { return }
        if animated {
            withAnimation { proxy.scrollTo(last, anchor: .bottom) }
        } else {
            proxy.scrollTo(last, anchor: .bottom)
        }
        landed = true
        landing = Landing(count: landing.count + 1, animated: animated)
    }
}

/// Everything under the messages: the send error, the congestion notice, the reply being written, and the composer.
private struct ConversationFooter: View {
    @Bindable var viewModel: ChatsViewModel

    var body: some View {
        let state = viewModel.uiState
        VStack(spacing: 0) {
            if let error = state.error {
                Text(verbatim: error)
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.danger)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, FirepitSpacing.screenMargin)
            }
            Composer(viewModel: viewModel)
        }
        .background(FirepitColors.surface)
    }
}

/// Warns when the air is too busy to speak into. Shown above the composer rather than after sending, because the
/// useful moment is before somebody relies on a message arriving.
private struct CongestionNotice: View {
    let load: ChannelLoad?

    var body: some View {
        if let load, load != .clear {
            let congested = load == .congested
            Text(
                congested
                    ? "Channel is congested — messages may not get through"
                    : "Channel is busy — messages may take longer"
            )
            .font(FirepitFont.labelMedium)
            .foregroundStyle(congested ? FirepitColors.warn : FirepitColors.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, FirepitSpacing.m)
            .padding(.vertical, FirepitSpacing.xs)
        }
    }
}

/// The message being replied to, matching the quote it will become inside the bubble.
private struct ReplyBanner: View {
    let parent: ChatMessage
    let name: String
    let cancel: () -> Void

    @Environment(\.identitySlots) private var identitySlots
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let accent = IdentityColors.color(for: parent.fromNodeNum, slot: identitySlots[parent.fromNodeNum])
        HStack(spacing: 0) {
            accent.frame(width: 3)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: name)
                    .font(FirepitFont.labelMedium)
                    .foregroundStyle(accent)
                    .lineLimit(1)
                Text(verbatim: parent.text)
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.textSecondary)
                    .lineLimit(1)
            }
            .padding(.horizontal, FirepitSpacing.s)
            .padding(.vertical, FirepitSpacing.xs)
            .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: cancel) {
                Image(icon: .close)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(FirepitColors.textSecondary)
                    .frame(width: FirepitSpacing.minTouchTarget, height: FirepitSpacing.minTouchTarget)
            }
            .accessibilityLabel(Text("Cancel reply"))
        }
        .fixedSize(horizontal: false, vertical: true)
        .background(accent.opacity(colorScheme == .dark ? 0.18 : 0.12))
        .clipShape(.rect(cornerRadius: 6))
        .padding(.horizontal, FirepitSpacing.s)
        .padding(.vertical, FirepitSpacing.xs)
    }
}

private struct Composer: View {
    @Bindable var viewModel: ChatsViewModel

    /// The field's own copy of the draft. The view model truncates at the radio's byte limit, and a TextField bound
    /// straight to a value that refuses a keystroke keeps showing the keystroke; routing through local state makes
    /// the refusal visible.
    @State private var text = ""
    /// ⚡: ready-made replies, one tap each (UX §5.4), sent through the same paced queue as anything typed and never
    /// touching the draft.
    @State private var showingQuick = false

    var body: some View {
        let state = viewModel.uiState
        VStack(spacing: 0) {
            CongestionNotice(load: state.channelLoad)
            if let parent = state.replyingTo {
                ReplyBanner(parent: parent, name: state.nameOf(parent.fromNodeNum), cancel: viewModel.cancelReply)
            }
            if showingQuick && !viewModel.quickReplies.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: FirepitSpacing.xs) {
                        ForEach(viewModel.quickReplies, id: \.self) { reply in
                            FirepitChip(
                                verbatim: reply, selected: false, enabled: state.connected && state.hasPrivateTarget
                            ) {
                                viewModel.sendQuickReply(reply)
                                showingQuick = false
                            }
                        }
                    }
                    .padding(.horizontal, FirepitSpacing.s)
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            HStack(alignment: .bottom, spacing: FirepitSpacing.s) {
                TextField("Message", text: $text, axis: .vertical)
                    .lineLimit(1...4)
                    .font(FirepitFont.bodyLarge)
                    .padding(.horizontal, FirepitSpacing.l)
                    .padding(.vertical, 12)
                    .background(FirepitColors.surface2, in: .rect(cornerRadius: 24))
                    .overlay { RoundedRectangle(cornerRadius: 24).strokeBorder(FirepitColors.outline) }
                Button {
                    withAnimation(.snappy) { showingQuick.toggle() }
                } label: {
                    Image(systemName: "bolt")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(showingQuick ? FirepitColors.primary : FirepitColors.textSecondary)
                        .frame(width: FirepitSpacing.minTouchTarget, height: FirepitSpacing.minTouchTarget)
                }
                .accessibilityLabel(Text(showingQuick ? "Hide quick replies" : "Quick replies"))
                Button(action: viewModel.send) {
                    Image(icon: .send)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(FirepitColors.onPrimary)
                        .frame(width: FirepitSpacing.minTouchTarget, height: FirepitSpacing.minTouchTarget)
                        // Dimmed rather than greyed: the send button should still look like itself while empty.
                        .background(FirepitColors.primary.opacity(state.canSend ? 1 : 0.55), in: .circle)
                }
                .disabled(!state.canSend)
                .accessibilityLabel(Text("Send"))
            }
            .padding(FirepitSpacing.s)
            if let hint = state.composerHint {
                Text(verbatim: hint.text)
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(color(hint.tone))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, FirepitSpacing.s + FirepitSpacing.l)
                    .padding(.trailing, FirepitSpacing.s + FirepitSpacing.minTouchTarget)
                    .padding(.bottom, FirepitSpacing.s)
            }
        }
        .onAppear { text = state.draft }
        .onChange(of: text) { _, typed in
            viewModel.updateDraft(typed)
            let kept = viewModel.uiState.draft
            if kept != typed { text = kept }
        }
        .onChange(of: state.draft) { _, draft in
            if draft != text { text = draft }
        }
    }

    private func color(_ tone: ComposerHint.Tone) -> Color {
        switch tone {
        case .quiet: FirepitColors.textSecondary
        case .warn: FirepitColors.warn
        case .danger: FirepitColors.danger
        }
    }
}

/// The room header while searching: one short phrase, focused as soon as it opens.
private struct RoomSearchBar: View {
    @Binding var query: String
    let close: () -> Void

    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: FirepitSpacing.s) {
            HStack(spacing: FirepitSpacing.s) {
                Image(icon: .search)
                    .foregroundStyle(FirepitColors.textSecondary)
                    .accessibilityHidden(true)
                TextField("Search this room", text: $query)
                    .focused($focused)
                    .submitLabel(.search)
                    .autocorrectionDisabled()
                if !query.isEmpty {
                    Button {
                        query = ""
                    } label: {
                        Image(icon: .close).foregroundStyle(FirepitColors.textSecondary)
                    }
                    // A 44-point target round a small glyph, without making the bar taller.
                    .contentShape(.rect.inset(by: -13))
                    .accessibilityLabel(Text("Clear search"))
                }
            }
            .padding(.horizontal, FirepitSpacing.m)
            .padding(.vertical, 10)
            .background(FirepitColors.surface2, in: .capsule)
            Button(action: close) { Image(icon: .close) }
                .foregroundStyle(FirepitColors.textPrimary)
                .frame(width: FirepitSpacing.minTouchTarget, height: FirepitSpacing.minTouchTarget)
                .accessibilityLabel(Text("Close search"))
        }
        .padding(.horizontal, FirepitSpacing.s)
        .padding(.vertical, FirepitSpacing.xs)
        .onAppear { focused = true }
    }
}

// MARK: - Message info

/// Delivery detail for one message. Only shows what the radio actually reported: absent values are omitted rather
/// than rendered as zero, because "0 dB SNR" and "not measured" are different claims.
private struct MessageInfoSheet: View {
    let viewModel: ChatsViewModel
    /// The message as it was when the sheet opened; the live copy replaces it while it is still in the conversation.
    let opened: ChatMessage

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let state = viewModel.uiState
        let message = state.inspecting ?? opened
        let receipts = viewModel.inspectedReceipts
        let nameOf = { (nodeNum: Int32) in state.nameOf(nodeNum) }
        let senderName = state.nameOf(message.fromNodeNum)
        NavigationStack {
            List {
                Section {
                    InfoRow(label: "Sent", value: MessageTimestamp.format(message.sentAt))
                    if let rxTime = message.rxTime {
                        InfoRow(label: "Radio clock", value: MessageTimestamp.format(rxTime))
                    }
                    if message.isOutgoing {
                        InfoRow(label: "Status", value: ChatsCopy.statusLabel(message.status))
                        if let reason = message.failureReason { InfoRow(label: "Reason", value: reason) }
                    }
                    if let hops = message.hopsAway {
                        InfoRow(label: "Hops", value: hops == 0 ? String(localized: "Direct, no relay") : "\(hops)")
                    }
                    if let snr = message.rxSnr {
                        InfoRow(
                            label: "Signal to noise",
                            value: "\(Double(snr).formatted(.number.precision(.fractionLength(2)))) dB"
                        )
                    }
                    if let rssi = message.rxRssi { InfoRow(label: "Signal strength", value: "\(rssi) dBm") }
                    if message.signed { InfoRow(label: "Signature", value: String(localized: "Verified")) }
                }
                ReceiptList(label: "Read by", receipts: receipts.filter { $0.state == .read }, nameOf: nameOf)
                ReceiptList(label: "Received by", receipts: receipts.filter { $0.state == .received }, nameOf: nameOf)
                if message.isOutgoing && message.status.isFailure {
                    Section {
                        Button("Send again") { viewModel.sendAgain(message) }
                    }
                }
                Section {
                } footer: {
                    Text(
                        """
                        Delivery is only ever confirmed as far as the first node that heard it. \
                        The mesh cannot tell you who read it.
                        """
                    )
                }
            }
            .scrollContentBackground(.hidden)
            .background(FirepitColors.surface)
            .navigationTitle(
                message.isOutgoing ? Text("Sent message") : Text("From \(senderName)")
            )
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct InfoRow: View {
    let label: LocalizedStringKey
    let value: String

    var body: some View {
        HStack {
            Text(label).foregroundStyle(FirepitColors.textSecondary)
            Spacer()
            Text(verbatim: value).foregroundStyle(FirepitColors.textPrimary)
        }
        .font(FirepitFont.bodyMedium)
        .listRowBackground(FirepitColors.surface2)
    }
}

/// Who told us they have this message, and when. Only the phones that reported appear: silence is not evidence of
/// anything, so nobody is ever listed as not having read something.
private struct ReceiptList: View {
    let label: LocalizedStringKey
    let receipts: [Receipt]
    let nameOf: (Int32) -> String

    var body: some View {
        if !receipts.isEmpty {
            Section(label) {
                ForEach(receipts.sorted { $0.at < $1.at }, id: \.self) { receipt in
                    HStack {
                        Text(verbatim: nameOf(receipt.nodeNum)).foregroundStyle(FirepitColors.textSecondary)
                        Spacer()
                        Text(verbatim: MessageTimestamp.format(receipt.at)).foregroundStyle(FirepitColors.textPrimary)
                    }
                    .font(FirepitFont.bodyMedium)
                    .listRowBackground(FirepitColors.surface2)
                }
            }
        }
    }
}

private struct EmptyState: View {
    let text: String

    var body: some View {
        Text(verbatim: text)
            .font(FirepitFont.bodyMedium)
            .foregroundStyle(FirepitColors.textSecondary)
            .multilineTextAlignment(.center)
            .padding(FirepitSpacing.xl)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

extension View {
    /// A list row that is part of the screen rather than an item in it: no separator, no inset background.
    fileprivate func plainRow() -> some View {
        listRowSeparator(.hidden)
            .listRowBackground(FirepitColors.surface)
    }
}

#if DEBUG
    /// Screens `-route chat.*` can open straight into.
    enum ChatsDebugStart {
        case room(Int)
        case search(Int)
        case direct
        case info(messageId: Int32)
        case reply(messageId: Int32)
        case newRoom
    }
#endif
