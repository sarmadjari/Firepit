import FirepitModel
import FirepitProtocol
import SwiftUI

/// Sharing UI, ported from android/app/…/location/ShareLocationUi.kt: the one-line summary, the rows for settings and
/// for a room's page, the map banner and the sheet that chooses a room and a length of time.

/// How often the countdown is redrawn, so "12m left" does not sit there saying 12 all evening.
private let tick: TimeInterval = 30

/// One sentence describing where your position is going.
func sharingSummary(_ state: SharingUiState, now: Date = .now) -> String {
    if !state.isSharing {
        return String(localized: "Not shared with anyone")
    }
    // Said rather than hidden: somebody who thinks they are being followed on the map by their group should know
    // when they are not.
    if state.paused {
        let room = state.roomName ?? String(localized: "that room")
        return String(
            localized: """
                Paused: your phone is away from your radio, or it doesn't carry \(room). Resumes when it's back
                """)
    }
    return sharedWith(state.roomName ?? String(localized: "a room"), endsAt: state.endsAt, now: now)
}

/// "Shared with <label> · 3h 12m left".
private func sharedWith(_ label: String, endsAt: Int64?, now: Date) -> String {
    guard let endsAt else {
        return String(localized: "Shared with \(label) until you turn it off")
    }
    return String(localized: "Shared with \(label) · \(timeLeft(endsAt, now: now))")
}

func timeLeft(_ endsAt: Int64, now: Date = .now) -> String {
    let remaining = endsAt - Int64(now.timeIntervalSince1970 * 1000)
    if remaining <= 0 {
        return String(localized: "stopping")
    }
    let minutes = remaining / 60_000
    let hours = minutes / 60
    if hours >= 1 {
        return String(localized: "\(hours)h \(minutes % 60)m left")
    }
    if minutes >= 1 {
        return String(localized: "\(minutes)m left")
    }
    return String(localized: "less than a minute left")
}

/// The summary, redrawn as the clock runs down.
private struct SharingSummaryText: View {
    let state: SharingUiState
    var summary: (SharingUiState, Date) -> String = { state, now in sharingSummary(state, now: now) }

    var body: some View {
        TimelineView(.periodic(from: .now, by: tick)) { context in
            Text(verbatim: summary(state, context.date))
        }
    }
}

/// A row for a settings list: says what is happening now, opens the picker.
///
/// Deliberately states the negative case too. "Not shared with anyone" is the answer to a question people actually
/// have, and a row that only appears when sharing is on cannot answer it. The colour carries the state.
struct SharingRow: View {
    let state: SharingUiState
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Your location")
                    .font(FirepitFont.bodyLarge)
                    .foregroundStyle(FirepitColors.textPrimary)
                SharingSummaryText(state: state)
                    .font(FirepitFont.bodyMedium)
                    .foregroundStyle(state.isSharing ? FirepitColors.live : FirepitColors.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}

/// The same control, answering the question a single room's page raises: "is my location going to *this* room?"
///
/// Naming the other room when one is chosen matters. "Not shared" next to a room you are in reads as "not sharing at
/// all", which would be a lie.
struct SharingRoomRow: View {
    let state: SharingUiState
    let roomId: Int32
    let onTap: () -> Void

    var body: some View {
        let isThisRoom = state.roomId == roomId
        let elsewhere = isThisRoom ? nil : state.roomName
        Button(action: onTap) {
            HStack(spacing: FirepitSpacing.m) {
                Image(icon: .locate)
                    .foregroundStyle(isThisRoom ? FirepitColors.live : FirepitColors.textSecondary)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Your location")
                        .font(FirepitFont.bodyLarge)
                        .foregroundStyle(FirepitColors.textPrimary)
                    SharingSummaryText(state: state) { state, now in
                        if isThisRoom {
                            return sharedWith(String(localized: "this room"), endsAt: state.endsAt, now: now)
                        }
                        if let elsewhere {
                            return String(localized: "Shared with \(elsewhere) instead")
                        }
                        return String(localized: "Not shared")
                    }
                    .font(FirepitFont.bodyMedium)
                    .foregroundStyle(
                        isThisRoom
                            ? FirepitColors.live : elsewhere != nil ? FirepitColors.warn : FirepitColors.textSecondary
                    )
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(icon: .chevron)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(FirepitColors.textSecondary)
                    .accessibilityHidden(true)
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}

/// The banner the map carries while sharing is on.
///
/// Present for as long as sharing is, because the failure this guards against is forgetting — and something you have
/// forgotten is not something you will go looking for in a menu.
struct SharingBanner: View {
    let state: SharingUiState
    let onChange: () -> Void
    let onStop: () -> Void

    var body: some View {
        if state.isSharing {
            let shape = RoundedRectangle(cornerRadius: FirepitSpacing.cardCorner, style: .continuous)
            HStack(spacing: FirepitSpacing.s) {
                Button(action: onChange) {
                    HStack(spacing: FirepitSpacing.s) {
                        Image(icon: .locate)
                            .foregroundStyle(FirepitColors.live)
                            .accessibilityHidden(true)
                        SharingSummaryText(state: state)
                            .font(FirepitFont.bodyMedium)
                            .foregroundStyle(FirepitColors.textPrimary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityHint(Text("Changes where your location goes"))
                Button("Stop", action: onStop)
                    .font(FirepitFont.bodyMedium.weight(.semibold))
                    .foregroundStyle(FirepitColors.primary)
                    .frame(minWidth: FirepitSpacing.minTouchTarget, minHeight: FirepitSpacing.minTouchTarget)
            }
            .padding(.leading, FirepitSpacing.m)
            .padding(.vertical, FirepitSpacing.xs)
            .padding(.trailing, FirepitSpacing.xs)
            .background(FirepitColors.surface2, in: shape)
            .overlay { shape.strokeBorder(FirepitColors.live) }
        }
    }
}

/// "Sharing · 43m left", under the header of the conversation whose room gets your location, while the map sits
/// beside it (UX §6.11.6). Ported from android/app/…/location/ShareLocationUi.kt.
struct SharingChip: View {
    let state: SharingUiState

    var body: some View {
        TimelineView(.periodic(from: .now, by: 30)) { context in
            HStack(spacing: FirepitSpacing.xs) {
                Circle()
                    .fill(state.paused ? FirepitColors.stale : FirepitColors.live)
                    .frame(width: 8, height: 8)
                    .accessibilityHidden(true)
                Text(verbatim: text(now: context.date))
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.textSecondary)
            }
            .padding(.horizontal, FirepitSpacing.s)
            .padding(.vertical, FirepitSpacing.xs)
            .background(FirepitColors.surface2, in: Capsule())
        }
    }

    private func text(now: Date) -> String {
        if state.paused { return String(localized: "Sharing paused") }
        guard let endsAt = state.endsAt else { return String(localized: "Sharing until you turn it off") }
        return String(localized: "Sharing · \(timeLeft(endsAt, now: now))")
    }
}

/// Choose a room and a length of time, or stop.
///
/// Duration is asked at the same moment as the room rather than hidden behind a later setting: the two answers
/// together are the decision, and a picker that only asks "who" has already decided "forever" on the person's behalf.
struct ShareLocationSheet: View {
    let state: SharingUiState
    let onDismiss: () -> Void
    let onShare: (Int32, ShareDuration) -> Void
    let onStop: () -> Void

    @State private var room: Int32?
    @State private var choice: ShareDuration

    /// `preferredRoomId`: the room open beside the map, offered first when nothing is being shared yet (UX §6.11.6).
    init(
        state: SharingUiState,
        preferredRoomId: Int32? = nil,
        onDismiss: @escaping () -> Void,
        onShare: @escaping (Int32, ShareDuration) -> Void,
        onStop: @escaping () -> Void
    ) {
        self.state = state
        self.onDismiss = onDismiss
        self.onShare = onShare
        self.onStop = onStop
        let preferred = preferredRoomId.flatMap { id in state.rooms.contains { $0.id == id } ? id : nil }
        _room = State(initialValue: state.roomId ?? preferred ?? state.rooms.first?.id)
        _choice = State(initialValue: state.choice)
    }

    var body: some View {
        NavigationStack {
            Group {
                if !state.connected {
                    dead(Text("Connect your radio first — Settings, then Nodes."))
                } else if !state.hasRooms {
                    // Slot 0 is deliberately never used for position: it would reach every Meshtastic radio in range.
                    dead(
                        Text(
                            """
                            You need a private room first. Your location only ever goes to one private room — never \
                            the public channel, and never a Meshtastic channel, because those reach people you did not \
                            choose.

                            Create or join a room from Chats, then come back.
                            """))
                } else {
                    picker
                }
            }
            .navigationTitle("Share your location")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(state.connected && state.hasRooms ? "Cancel" : "Close", action: onDismiss)
                }
                if state.connected && state.hasRooms {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(state.isSharing ? "Update" : "Share") {
                            if let room {
                                onShare(room, choice)
                            }
                        }
                        .disabled(room == nil)
                    }
                }
            }
        }
        .presentationBackground(FirepitColors.surface)
    }

    private var picker: some View {
        Form {
            Section {
                Picker("Room", selection: $room) {
                    ForEach(state.rooms, id: \.index) { candidate in
                        Text(verbatim: candidate.displayName)
                            .tag(Optional(candidate.id))
                    }
                }
                .pickerStyle(.inline)
                .labelsHidden()
            } header: {
                Text("Room")
            } footer: {
                Text(
                    """
                    Your phone seals where you are with the room's key and sends it through your radio, so only \
                    people in the room can see it — including people invited later. It pauses while your phone is \
                    away from your radio.
                    """)
            }
            .listRowBackground(FirepitColors.surface2)

            Section {
                Picker("For how long", selection: $choice) {
                    ForEach(ShareDuration.allCases, id: \.self) { entry in
                        Text(verbatim: entry.label)
                            .tag(entry)
                    }
                }
                .pickerStyle(.inline)
                .labelsHidden()
            } header: {
                Text("For how long")
            } footer: {
                if choice == .untilOff {
                    Text("Nothing will turn this off for you.")
                        .foregroundStyle(FirepitColors.warn)
                }
            }
            .listRowBackground(FirepitColors.surface2)

            if state.isSharing {
                Section {
                    Button("Stop sharing", role: .destructive, action: onStop)
                }
                .listRowBackground(FirepitColors.surface2)
            }
        }
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
    }

    /// A state the sheet cannot act on, with the way out it would otherwise lack (the toolbar's Close).
    private func dead(_ text: Text) -> some View {
        ScrollView {
            text
                .font(FirepitFont.bodyLarge)
                .foregroundStyle(FirepitColors.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(FirepitSpacing.screenMargin)
        }
        .background(FirepitColors.surface)
    }
}
