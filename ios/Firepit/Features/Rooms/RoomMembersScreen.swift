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
        onLeft: @escaping () -> Void = {}
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
            viewModel: RoomsViewModel(app: app),
            sharingViewModel: SharingViewModel(location: app.location, mesh: app.mesh)
        )
    }

    var body: some View {
        List {
            hero
            actions
            if kind.isPrivate {
                SharingRoomRow(state: sharingViewModel.state, roomId: roomId) { pickingRoom = true }
                    .listRowBackground(FirepitColors.surface2)
                SectionLabel("Members")
                    .listRowBackground(FirepitColors.surface)
            }
            if members.isEmpty && kind.isPrivate {
                Text("Nobody heard yet.")
                    .foregroundStyle(FirepitColors.textSecondary)
                    .listRowBackground(FirepitColors.surface)
            }
            ForEach(kind.isPrivate ? members : []) { row in
                MemberRowView(row: row, trace: viewModel.trace) {
                    viewModel.checkPath(nodeNum: row.member.nodeNum, name: row.displayName)
                } onRemove: {
                    removing = row
                }
                .listRowBackground(FirepitColors.surface2)
            }
            Text(
                "Anyone with the room's key can read and post. Members appear as Firepit hears them, or when "
                    + "another member reports them, so this list may be incomplete."
            )
            .font(FirepitFont.bodySmall)
            .foregroundStyle(FirepitColors.textSecondary)
            .listRowBackground(FirepitColors.surface)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
        .navigationTitle("Room info")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .topBarLeading) { Button("Back", action: onBack) } }
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
                "The key goes with it, so you will need a new invitation to come back, and this room's messages "
                    + "are deleted from this phone."
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
    let onPath: () -> Void
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: FirepitSpacing.m) {
            IdentityAvatar(nodeNum: row.member.nodeNum, tag: row.shortName)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: headline)
                    .font(FirepitFont.titleMedium)
                    .lineLimit(1)
                Text(verbatim: memberDetail(row))
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(row.member.isFirstHand ? FirepitColors.textSecondary : FirepitColors.stale)
                if !row.member.isVouched {
                    Text("Invite unknown")
                        .font(FirepitFont.bodySmall)
                        .foregroundStyle(FirepitColors.stale)
                }
            }
            Spacer()
            if row.isSelf {
                Text("You")
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.textSecondary)
            } else {
                Menu {
                    Button(pathLabel, action: onPath)
                        .disabled(isRunning)
                    Button("Remove", role: .destructive, action: onRemove)
                } label: {
                    Image(icon: .more)
                        .frame(minWidth: FirepitSpacing.minTouchTarget, minHeight: FirepitSpacing.minTouchTarget)
                }
                .accessibilityLabel(Text("Member actions"))
            }
        }
        .accessibilityElement(children: .combine)
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
        return "Path"
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
