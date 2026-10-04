import FirepitModel
import SwiftUI

/// Who we have seen in a room.
struct RoomMembersScreen: View {
    let roomId: Int32
    /// The slot it sits in, which is what names a Meshtastic channel: those have no room id.
    let channelIndex: Int
    let roomName: String
    let onBack: () -> Void
    var muted = false
    var kind: RoomKind = .firepit
    var onInvite: () -> Void = {}
    var onToggleMute: () -> Void = {}
    var onLeft: () -> Void = {}
    /// Opens a conversation with one member: tapping a person is how one starts.
    var onMessage: (Int32) -> Void = { _ in }

    @State private var viewModel: RoomsViewModel
    @State private var sharingViewModel: SharingViewModel
    @State private var members: [MemberRow] = []
    @State private var removing: MemberRow?
    @State private var confirmingLeave = false
    @State private var pickingRoom = false

    init(
        roomId: Int32,
        channelIndex: Int,
        roomName: String,
        onBack: @escaping () -> Void,
        muted: Bool = false,
        kind: RoomKind = .firepit,
        onInvite: @escaping () -> Void = {},
        onToggleMute: @escaping () -> Void = {},
        onLeft: @escaping () -> Void = {},
        onMessage: @escaping (Int32) -> Void = { _ in },
        viewModel: RoomsViewModel,
        sharingViewModel: SharingViewModel
    ) {
        self.roomId = roomId
        self.channelIndex = channelIndex
        self.roomName = roomName
        self.onBack = onBack
        self.muted = muted
        self.kind = kind
        self.onInvite = onInvite
        self.onToggleMute = onToggleMute
        self.onLeft = onLeft
        self.onMessage = onMessage
        _viewModel = State(initialValue: viewModel)
        _sharingViewModel = State(initialValue: sharingViewModel)
    }

    init(
        roomId: Int32,
        channelIndex: Int,
        roomName: String,
        app: AppContainer,
        onBack: @escaping () -> Void,
        muted: Bool = false,
        kind: RoomKind = .firepit,
        onInvite: @escaping () -> Void = {},
        onToggleMute: @escaping () -> Void = {},
        onLeft: @escaping () -> Void = {},
        onMessage: @escaping (Int32) -> Void = { _ in }
    ) {
        self.init(
            roomId: roomId,
            channelIndex: channelIndex,
            roomName: roomName,
            onBack: onBack,
            muted: muted,
            kind: kind,
            onInvite: onInvite,
            onToggleMute: onToggleMute,
            onLeft: onLeft,
            onMessage: onMessage,
            viewModel: RoomsViewModel(app: app),
            sharingViewModel: SharingViewModel(location: app.location, mesh: app.mesh)
        )
    }

    var body: some View {
        List {
            Section {
                hero
                actions
            }
            // Membership is a Firepit idea: on a Meshtastic channel there is no roster, and anyone with the key can be
            // on it unannounced.
            if kind.isPrivate {
                Section {
                    SharingRoomRow(state: sharingViewModel.state, roomId: roomId) { pickingRoom = true }
                        .listRowBackground(FirepitColors.surface2)
                }
                Section {
                    if members.isEmpty {
                        Text("Nobody heard yet.")
                            .foregroundStyle(FirepitColors.textSecondary)
                            .listRowBackground(FirepitColors.surface2)
                    }
                    ForEach(members) { row in
                        MemberRowView(row: row, trace: viewModel.trace) {
                            onMessage(row.member.nodeNum)
                        } onPath: {
                            viewModel.checkPath(nodeNum: row.member.nodeNum, name: row.displayName)
                        } onRemove: {
                            removing = row
                        }
                        .listRowBackground(FirepitColors.surface2)
                    }
                } header: {
                    Text("Members")
                } footer: {
                    Text(
                        """
                        Tap someone to message them on their own. Anyone with the room's key can read and post. \
                        Members appear as Firepit hears them, or when another member reports them, so this list may \
                        be incomplete.
                        """
                    )
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
        .navigationTitle("Room info")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await withTaskGroup(of: Void.self) { group in
                group.addTask { await viewModel.observe() }
                group.addTask { await sharingViewModel.observe() }
                group.addTask {
                    for await rows in await viewModel.members(roomId: roomId) {
                        await MainActor.run { members = rows }
                    }
                }
            }
        }
        .sheet(isPresented: $pickingRoom) {
            ShareLocationSheet(
                state: sharingViewModel.state,
                onDismiss: { pickingRoom = false },
                onShare: { chosen, choice in
                    pickingRoom = false
                    sharingViewModel.share(roomId: chosen, choice: choice)
                },
                onStop: {
                    pickingRoom = false
                    sharingViewModel.stop()
                }
            )
            .fittedSheet()
        }
        .confirmationDialog("Leave this room?", isPresented: $confirmingLeave, titleVisibility: .visible) {
            Button("Leave", role: .destructive) {
                viewModel.leaveRoom(roomId: roomId, slot: channelIndex)
                onLeft()
            }
            Button("Stay", role: .cancel) {}
        } message: {
            Text(
                """
                The key goes with it, so you will need a new invitation to come back, and this room's messages are \
                deleted from this phone.
                """
            )
        }
        .confirmationDialog(removeTitle, isPresented: removeBinding, titleVisibility: .visible) {
            Button("Remove and change the key", role: .destructive) {
                if let removing {
                    viewModel.removeMember(roomId: roomId, nodeNum: removing.member.nodeNum)
                }
                removing = nil
            }
            Button("Cancel", role: .cancel) { removing = nil }
        } message: {
            Text(removeMessage)
        }
        .alert("Path check", isPresented: traceDoneBinding) {
            Button("Close", action: viewModel.clearTrace)
        } message: {
            Text(traceMessage)
        }
        .alert("The room has a new key", isPresented: rotationBinding) {
            Button("Done", action: viewModel.clearRotation)
        } message: {
            Text(viewModel.rotation ?? "")
        }
    }

    private var hero: some View {
        VStack(spacing: FirepitSpacing.s) {
            RoomAvatar(icon: RoomIcon.forRoomId(roomId), size: 88)
            Text(verbatim: roomName)
                .font(FirepitFont.headlineLarge)
            Text(kind.readableBy)
                .font(FirepitFont.bodyMedium)
                .foregroundStyle(kind.isPrivate ? FirepitColors.textSecondary : FirepitColors.warn)
            Text(kind.summary)
                .font(FirepitFont.bodySmall)
                .foregroundStyle(FirepitColors.textSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, FirepitSpacing.l)
        .listRowBackground(FirepitColors.surface)
    }

    private var actions: some View {
        HStack(spacing: FirepitSpacing.m) {
            if kind.isPrivate {
                RoomAction(icon: .qr, label: "Invite", action: onInvite)
            }
            RoomAction(icon: muted ? .bell : .mute, label: muted ? "Unmute" : "Mute", action: onToggleMute)
            RoomAction(icon: .close, label: "Leave") { confirmingLeave = true }
        }
        .listRowInsets(EdgeInsets())
        .padding(.horizontal, FirepitSpacing.screenMargin)
        .padding(.vertical, FirepitSpacing.s)
        .listRowBackground(FirepitColors.surface)
    }

    private var removeBinding: Binding<Bool> {
        Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })
    }

    private var removeTitle: String {
        removing.map { "Remove \($0.displayName)?" } ?? "Remove member?"
    }

    private var removeMessage: String {
        let name = removing?.displayName ?? "They"
        return "Nothing can take the old key back from them, so the room moves to a new one instead. Everyone "
            + "still here is sent it privately and asked to confirm, which can take a minute; \(name) is not, "
            + "and can read nothing from now on.\n\nThey keep whatever they already received."
    }

    private var traceDoneBinding: Binding<Bool> {
        Binding(
            get: { if case .done = viewModel.trace { true } else { false } },
            set: { if !$0 { viewModel.clearTrace() } }
        )
    }

    private var traceMessage: String {
        if case .done(_, let summary) = viewModel.trace {
            return summary + "\n\nA path is not a delivery receipt. It shows the mesh could reach them just now, "
                + "not that they read anything."
        }
        return ""
    }

    private var rotationBinding: Binding<Bool> {
        Binding(get: { viewModel.rotation != nil }, set: { if !$0 { viewModel.clearRotation() } })
    }
}

private struct MemberRowView: View {
    let row: MemberRow
    let trace: TraceState
    let onMessage: () -> Void
    let onPath: () -> Void
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: FirepitSpacing.s) {
            if row.isSelf {
                person
                Text("You")
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.textSecondary)
            } else {
                // The person is the button: tapping them opens a conversation with them, as on Android.
                Button(action: onMessage) { person.contentShape(.rect) }
                    .buttonStyle(.plain)
                    .accessibilityHint(Text("Opens a conversation with them"))
                Button(action: onPath) { Text(verbatim: pathLabel) }
                    .buttonStyle(.borderless)
                    .foregroundStyle(FirepitColors.primary)
                    .disabled(isRunning)
                Button("Remove", role: .destructive, action: onRemove)
                    .buttonStyle(.borderless)
                    .foregroundStyle(FirepitColors.danger)
            }
        }
    }

    private var person: some View {
        HStack(spacing: FirepitSpacing.m) {
            IdentityAvatar(nodeNum: row.member.nodeNum, tag: row.shortName)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: headline)
                    .font(FirepitFont.titleMedium)
                    .foregroundStyle(FirepitColors.textPrimary)
                    .lineLimit(1)
                Text(verbatim: memberDetail(row))
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.textSecondary)
                if !row.member.isVouched {
                    Text("Invite unknown")
                        .font(FirepitFont.bodySmall)
                        .foregroundStyle(FirepitColors.textSecondary)
                }
            }
            Spacer(minLength: 0)
        }
    }

    private var headline: String {
        if let invitedByName = row.invitedByName {
            return "\(row.displayName) · invited by \(invitedByName)"
        }
        return row.displayName
    }

    private var isRunning: Bool {
        if case .running = trace { return true }
        return false
    }

    private var pathLabel: String {
        if case .running(let nodeNum) = trace, nodeNum == row.member.nodeNum {
            return "…"
        }
        return String(localized: "Path")
    }
}

private struct RoomAction: View {
    let icon: FirepitIcon
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: FirepitSpacing.s) {
                Image(icon: icon)
                    .font(.title3)
                    .accessibilityHidden(true)
                Text(label)
                    .font(FirepitFont.bodyMedium)
            }
            .foregroundStyle(FirepitColors.primary)
            .frame(maxWidth: .infinity, minHeight: 88)
            .background(FirepitColors.surface2, in: RoundedRectangle(cornerRadius: FirepitSpacing.cardCorner))
            .overlay {
                RoundedRectangle(cornerRadius: FirepitSpacing.cardCorner).strokeBorder(FirepitColors.outline)
            }
        }
        .buttonStyle(.plain)
    }
}
