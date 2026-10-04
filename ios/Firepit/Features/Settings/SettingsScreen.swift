import FirepitData
import FirepitModel
import FirepitProtocol
import FirepitTransport
import SwiftUI
import UserNotifications

/// The screens Settings pushes, as on Android. Everything else is on the page itself.
enum SettingsSection: String, Hashable, CaseIterable {
    case devices
    case nodes
    case offlineMaps
    case pins
    case quickReplies
}

/// The page's sections, in order, so a debug route can open the page at one of them.
enum SettingsPageSection: String, Hashable, CaseIterable {
    case you, radio, notifications, messages, privacy, map, appearance, about
}

/// Settings, ported from android/app/…/settings/SettingsScreen.kt: one page with the same sections, items, order and
/// wording as Android, each answered where it is asked, drawn with iOS controls — fields, switches, and menus that
/// show the current choice. Only Devices, Nodes, Offline areas and Dropped pins open screens of their own.
struct SettingsScreen: View {
    let app: AppContainer

    @State private var path: [SettingsSection]
    @State private var model: SettingsViewModel
    @State private var radioModel: RadioViewModel
    @State private var sharingModel: SharingViewModel
    private let scrollTo: SettingsPageSection?
    /// Set when Settings is a sheet over two panes: it gets a Done button (UX §6.11.7).
    private let onClose: (() -> Void)?

    init(app: AppContainer, onClose: (() -> Void)? = nil) {
        self.init(app: app, initialRoute: nil, onClose: onClose)
    }

    init(
        app: AppContainer,
        initialRoute: SettingsSection?,
        scrollTo: SettingsPageSection? = nil,
        onClose: (() -> Void)? = nil
    ) {
        self.app = app
        self.scrollTo = scrollTo
        self.onClose = onClose
        _path = State(initialValue: initialRoute.map { [$0] } ?? [])
        _model = State(initialValue: SettingsViewModel(app: app))
        _radioModel = State(initialValue: RadioViewModel(app: app))
        _sharingModel = State(initialValue: SharingViewModel(location: app.location, mesh: app.mesh))
    }

    var body: some View {
        NavigationStack(path: $path) {
            SettingsPage(
                model: model,
                radio: radioModel.uiState,
                sharingModel: sharingModel,
                scrollTo: scrollTo,
                quickReplyCount: app.quickReplies.replies.count,
                onOpen: { path.append($0) }
            )
            .navigationDestination(for: SettingsSection.self, destination: destination)
            .toolbar {
                if let onClose {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done", action: onClose)
                    }
                }
            }
        }
        .task { await model.observe() }
        .task { await radioModel.observe() }
        .task { await sharingModel.observe() }
        .alert(
            "Could not save your name",
            isPresented: Binding(get: { model.state.renameError != nil }, set: { if !$0 { model.clearRenameError() } })
        ) {
            Button("Close", action: model.clearRenameError)
        } message: {
            Text(verbatim: model.state.renameError ?? "")
        }
    }

    @ViewBuilder
    private func destination(_ route: SettingsSection) -> some View {
        switch route {
        case .devices:
            DevicesScreen(viewModel: radioModel)
                .toolbar(.hidden, for: .tabBar)
        case .nodes:
            NodesScreen(viewModel: radioModel) { peer in
                // The conversation opens in Chats; coming back to Settings lands on the page, not here.
                path.removeAll()
                app.router.openDirect(peer)
            }
            .toolbar(.hidden, for: .tabBar)
        case .offlineMaps:
            OfflineMapsScreen(
                model: OfflineMapsViewModel(
                    repository: app.offlineMaps,
                    mapPreferences: app.mapPreferences,
                    mesh: app.mesh
                )
            )
            .toolbar(.hidden, for: .tabBar)
        case .pins:
            PinsScreen(app: app)
                .toolbar(.hidden, for: .tabBar)
        case .quickReplies:
            QuickRepliesScreen(store: app.quickReplies)
                .toolbar(.hidden, for: .tabBar)
        }
    }
}

private struct SettingsPage: View {
    let model: SettingsViewModel
    let radio: RadioUiState
    let sharingModel: SharingViewModel
    let scrollTo: SettingsPageSection?
    let quickReplyCount: Int
    let onOpen: (SettingsSection) -> Void

    @State private var confirmingErase = false

    private var quickReplySummary: String {
        quickReplyCount == 1
            ? String(localized: "1 ready to send with ⚡")
            : String(localized: "\(quickReplyCount) ready to send with ⚡")
    }
    @State private var pickingRoom = false

    var body: some View {
        let state = model.state
        ScrollViewReader { proxy in
            Form {
                PersonSection(state: state, model: model)
                    .id(SettingsPageSection.you)

                Section {
                    NavigationRow(title: "Devices", subtitle: SettingsViewModel.deviceSummary(radio.link)) {
                        onOpen(.devices)
                    }
                    NavigationRow(title: "Nodes", subtitle: SettingsViewModel.nodeSummary(count: radio.nodes.count)) {
                        onOpen(.nodes)
                    }
                } header: {
                    Text("Radio")
                }
                .id(SettingsPageSection.radio)
                RangeSections(
                    state: state,
                    onChooseRange: model.chooseRange,
                    onMakePrivate: model.makeRadioPrivate,
                    onKeepPublic: model.keepRadioPublic
                )

                NotificationSections(
                    state: state,
                    onShowMessageText: model.setShowMessageText,
                    onChooseMessageAlerts: model.chooseMessageAlerts
                )
                .id(SettingsPageSection.notifications)

                Section {
                    choicePicker(
                        "Keep messages for", selection: state.retention, options: MessageRetention.allCases,
                        label: \.label, onChoose: model.chooseRetention)
                } header: {
                    Text("Messages")
                } footer: {
                    Text(
                        """
                        Older messages are always deleted from this phone — there is no keeping them. Everyone else \
                        holds their own copy, and nothing on a mesh can delete theirs.
                        """
                    )
                }
                .id(SettingsPageSection.messages)
                Section {
                    choicePicker(
                        "Leave quiet rooms", selection: state.roomLifetime, options: RoomLifetime.allCases,
                        label: \.label, onChoose: model.chooseRoomLifetime)
                } footer: {
                    Text("Leaving takes the room's messages and its key with it, and cannot be undone.")
                }
                Section {
                    NavigationRow(title: "Quick replies", subtitle: quickReplySummary) {
                        onOpen(.quickReplies)
                    }
                }

                Section {
                    Toggle(
                        "Allow screenshots",
                        isOn: Binding(get: { state.allowScreenCapture }, set: { model.setAllowScreenCapture($0) })
                    )
                } header: {
                    Text("Privacy")
                } footer: {
                    Text(
                        """
                        Off hides Firepit in the app switcher and while the screen is recorded or mirrored. iOS does \
                        not let apps block screenshots. Invite codes are always hidden from recordings and the app \
                        switcher.
                        """
                    )
                }
                .id(SettingsPageSection.privacy)
                Section {
                    Button(role: .destructive) {
                        confirmingErase = true
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Erase history on this phone")
                                .foregroundStyle(FirepitColors.danger)
                            Text("Messages, pins, where people were, their names, and the map tiles you looked at")
                                .font(FirepitFont.bodySmall)
                                .foregroundStyle(FirepitColors.textSecondary)
                        }
                    }
                }

                Section {
                    SharingRow(state: sharingModel.state) { pickingRoom = true }
                    NavigationRow(
                        title: "Offline areas", subtitle: "Download map tiles so the map works with no signal"
                    ) {
                        onOpen(.offlineMaps)
                    }
                    NavigationRow(title: "Dropped pins", subtitle: "Rename or delete the pins on your map") {
                        onOpen(.pins)
                    }
                } header: {
                    Text("Map")
                }
                .id(SettingsPageSection.map)

                Section {
                    choicePicker(
                        "Theme", selection: state.theme, options: ThemeChoice.allCases, label: \.label,
                        onChoose: model.chooseTheme)
                } header: {
                    Text("Appearance")
                } footer: {
                    Text("Dark keeps a torch-lit camp readable and does not flare in your eyes at night.")
                }
                .id(SettingsPageSection.appearance)

                WideScreenSection()

                Section {
                    LabeledRow(
                        title: "Firepit",
                        detail: String(localized: "Meshtastic chat, rooms and maps that work off-grid"))
                    LabeledRow(
                        title: "Works with",
                        detail: String(
                            localized: """
                                Meshtastic radios running firmware \(RadioCapabilities.minimumFirmware.raw) or newer. \
                                An older node is refused rather than half-supported, because the privacy Firepit \
                                describes would not hold on it.
                                """
                        )
                    )
                } header: {
                    Text("About")
                }
                .id(SettingsPageSection.about)
            }
            .listRowBackground(FirepitColors.surface2)
            .scrollContentBackground(.hidden)
            .background(FirepitColors.surface)
            .navigationTitle("Settings")
            .onAppear {
                if let scrollTo { proxy.scrollTo(scrollTo, anchor: .top) }
            }
        }
        .confirmationDialog("Erase history?", isPresented: $confirmingErase, titleVisibility: .visible) {
            Button("Erase", role: .destructive) { model.eraseHistory() }
            Button("Keep", role: .cancel) {}
        } message: {
            Text(
                """
                Deletes every message, pin, last-known position, name card and browsed map tile on this phone. \
                Your rooms and downloaded areas stay. Everyone else keeps their own copy.
                """
            )
        }
        .sheet(isPresented: $pickingRoom) {
            ShareLocationSheet(
                state: sharingModel.state,
                onDismiss: { pickingRoom = false },
                onShare: { roomId, choice in
                    pickingRoom = false
                    sharingModel.share(roomId: roomId, choice: choice)
                },
                onStop: {
                    pickingRoom = false
                    sharingModel.stop()
                }
            )
        }
    }

    /// One question and its answers, as Android's chip rows: a menu that shows the current answer.
    private func choicePicker<Option: Hashable>(
        _ title: LocalizedStringKey,
        selection: Option,
        options: [Option],
        label: KeyPath<Option, String>,
        enabled: Bool = true,
        onChoose: @escaping @MainActor (Option) -> Void
    ) -> some View {
        Picker(title, selection: Binding(get: { selection }, set: onChoose)) {
            ForEach(options, id: \.self) { option in
                Text(verbatim: option[keyPath: label]).tag(option)
            }
        }
        .pickerStyle(.menu)
        .tint(FirepitColors.primary)
        .disabled(!enabled)
    }
}

/// Settings › Appearance › Wide screens (UX §6.11.4), shown while the window is wide enough for two panes. Ported
/// from android/app/…/settings/SettingsScreen.kt.
private struct WideScreenSection: View {
    @Environment(\.wideScreen) private var controls
    @Environment(\.layoutDirection) private var direction
    private var preferences = LayoutPreferences()

    /// The side as the setting names it, whatever the reading direction.
    private enum PhysicalSide: String, CaseIterable {
        case right
        case left

        var label: String {
            switch self {
            case .right: String(localized: "Right")
            case .left: String(localized: "Left")
            }
        }
    }

    var body: some View {
        if controls?.canSplit == true {
            let choice = preferences.choice
            let rtl = direction == .rightToLeft
            let mapOnRight = (choice.mapSide == .end) != rtl
            Section {
                Picker(
                    "Layout",
                    selection: Binding(get: { choice.arrangement }, set: { controls?.onArrange($0) ?? preferences.setArrangement($0) })
                ) {
                    ForEach(PaneArrangement.allCases, id: \.self) { option in
                        Text(verbatim: option.label).tag(option)
                    }
                }
                .pickerStyle(.menu)
                .tint(FirepitColors.primary)
                Picker(
                    "Map on the",
                    selection: Binding(
                        get: { mapOnRight ? PhysicalSide.right : .left },
                        set: { side in preferences.setMapSide((side == .right) != rtl ? .end : .start) }
                    )
                ) {
                    ForEach(PhysicalSide.allCases, id: \.self) { side in
                        Text(verbatim: side.label).tag(side)
                    }
                }
                .pickerStyle(.menu)
                .tint(FirepitColors.primary)
                .disabled(choice.arrangement != .chatAndMap)
                Button("Reset the divider", action: preferences.resetDivider)
                    .tint(FirepitColors.primary)
            } header: {
                Text("Wide screens")
            } footer: {
                Text("When the screen is wide enough for two: unfolded, a tablet, or a wide window.")
            }
        }
    }
}

/// You: your name and initials, your colour, and putting the name on your radio. Only on this phone, and
/// deliberately so: a name here costs nothing, needs no radio, and cannot be truncated by one.
private struct PersonSection: View {
    let state: SettingsUiState
    let model: SettingsViewModel

    @State private var name = ""
    @State private var tag = ""
    @State private var tagChosen = false
    @State private var confirmingRadioName = false
    @State private var pickingColour = false

    /// An emptied field falls back to the name rather than being rewritten as you delete.
    private var effectiveTag: String {
        tag.trimmingCharacters(in: .whitespaces).isEmpty ? Person.initialsFor(name: name) : tag
    }
    private var changed: Bool {
        name.trimmingCharacters(in: .whitespaces) != (state.person?.name ?? "")
            || effectiveTag != (state.person?.tag ?? "")
    }
    private var nameBytes: Int { name.utf8.count }
    private var tagBytes: Int { tag.utf8.count }
    private var tooLong: Bool { nameBytes > OwnerName.maxLongBytes || tagBytes > OwnerName.maxShortBytes }

    var body: some View {
        Section {
            HStack(alignment: .center, spacing: FirepitSpacing.m) {
                VStack(alignment: .leading, spacing: FirepitSpacing.xs) {
                    // Typing is what decides whether the initials were chosen; loading a saved person is not.
                    TextField(
                        "Your name",
                        text: Binding(
                            get: { name },
                            set: { next in
                                name = next
                                if !tagChosen { tag = Person.initialsFor(name: next) }
                            })
                    )
                    .textInputAutocapitalization(.words)
                    .textContentType(.name)
                    Text(
                        nameBytes > OwnerName.maxLongBytes
                            ? String(
                                localized:
                                    "Longer than a mesh packet can carry by \(nameBytes - OwnerName.maxLongBytes) bytes"
                            )
                            : String(localized: "How this phone refers to you. The mesh sees your device's name")
                    )
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(
                        nameBytes > OwnerName.maxLongBytes ? FirepitColors.danger : FirepitColors.textSecondary)
                }
                // Beside the fields because the two make one thing: the dot.
                Button {
                    pickingColour = true
                } label: {
                    IdentityAvatar(
                        nodeNum: state.person?.id ?? 0, tag: effectiveTag, size: 56, slot: state.person?.colourSlot)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Change your colour"))
            }
            VStack(alignment: .leading, spacing: FirepitSpacing.xs) {
                TextField(
                    "Initials",
                    text: Binding(
                        get: { tag },
                        set: { next in
                            tag = next
                            tagChosen = !next.trimmingCharacters(in: .whitespaces).isEmpty
                        })
                )
                .textInputAutocapitalization(.characters)
                Text(verbatim: initialsCaption)
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(
                        tagBytes > OwnerName.maxShortBytes ? FirepitColors.danger : FirepitColors.textSecondary)
            }
            HStack(spacing: FirepitSpacing.m) {
                Button("Save") { model.savePerson(name: name, tag: effectiveTag) }
                    .buttonStyle(.borderedProminent)
                    .tint(FirepitColors.primary)
                    .disabled(!changed || tooLong || name.trimmingCharacters(in: .whitespaces).isEmpty)
                // Offered rather than done: this is the one action here that leaves the phone and reconfigures
                // hardware.
                Button("Use on my radio") { confirmingRadioName = true }
                    .buttonStyle(.borderless)
                    .tint(FirepitColors.primary)
                    .disabled(!state.connected || state.person == nil || changed)
            }
        } header: {
            Text("You")
        } footer: {
            Text(
                """
                People in your rooms see this name, sealed. Everyone else nearby sees your radio's own name, which \
                it broadcasts in the open.
                """
            )
        }
        .onChange(of: state.person, initial: true) { _, person in
            name = person?.name ?? ""
            tag = person?.tag ?? ""
            // Nothing records whether a stored tag was typed or derived, so ask the rule: initials it would not have
            // produced were chosen deliberately.
            tagChosen = person != nil && person?.tag != Person.initialsFor(name: person?.name ?? "")
        }
        .alert("Put your name on your radio?", isPresented: $confirmingRadioName) {
            Button("Use it") { model.useAsNodeName() }
            Button("Keep the radio's name", role: .cancel) {}
        } message: {
            Text(
                """
                Your radio announces its name every few hours to every radio in range. In private mode that is under \
                a key built into Firepit, so anyone with the app can read it; otherwise every Meshtastic radio can. \
                Only do this if you are happy for strangers nearby to see it.
                """
            )
        }
        .sheet(isPresented: $pickingColour) {
            ColourSheet(person: state.person, tag: effectiveTag, name: name) { slot in
                model.chooseIdentitySlot(slot)
                pickingColour = false
            }
        }
    }

    private var initialsCaption: String {
        if tagBytes > OwnerName.maxShortBytes {
            return String(localized: "Up to \(OwnerName.maxShortBytes) characters")
        }
        if tag.trimmingCharacters(in: .whitespaces).isEmpty {
            return String(localized: "Empty follows your name: \(effectiveTag)")
        }
        return tagChosen
            ? String(localized: "Yours. Clear it to follow your name again")
            : String(localized: "Follows your name. Type your own if you prefer")
    }
}

/// Your colour, opened from your avatar as Android's dialog is. Only on this phone: the hue everyone else draws you in
/// comes from your node number, which is how every device agrees without asking each other.
private struct ColourSheet: View {
    let person: Person?
    let tag: String
    let name: String
    let onChoose: (Int?) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: FirepitSpacing.l) {
                    IdentityColourGrid(person: person, tag: tag, name: name, onChoose: onChoose)
                    Text(
                        """
                        Shared with your Firepit rooms, so the people you invited see you in this colour too. \
                        Everyone else draws you from your node number.
                        """
                    )
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.textSecondary)
                }
                .padding(FirepitSpacing.screenMargin)
            }
            .background(FirepitColors.surface)
            .navigationTitle("Your colour")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

/// How private the radio itself is, and how far its traffic travels. No heading of its own: it sits inside Radio, and a
/// second one under it read as a different piece of hardware.
private struct RangeSections: View {
    let state: SettingsUiState
    let onChooseRange: @MainActor (RangeMode) -> Void
    let onMakePrivate: @MainActor () -> Void
    let onKeepPublic: @MainActor () -> Void

    var body: some View {
        Section {
            // Not a plain choice: each answer runs a different action, and undecided is a state rather than something
            // to offer, so it selects neither and either answer can still be given.
            Menu {
                privacyOption(.open, action: onKeepPublic)
                privacyOption(.firepit, action: onMakePrivate)
            } label: {
                LabeledContent("This radio's own identity") {
                    Text(
                        verbatim: state.radioPrivacy == .undecided
                            ? String(localized: "Not chosen") : state.radioPrivacy.label
                    )
                    .foregroundStyle(FirepitColors.primary)
                }
                .foregroundStyle(FirepitColors.textPrimary)
            }
        } footer: {
            VStack(alignment: .leading, spacing: FirepitSpacing.s) {
                Text(
                    """
                    Your rooms, messages, pins and locations are always sealed. This only decides who can see the \
                    radio's own name and battery level: every Meshtastic device, or only devices running Firepit.
                    """
                )
                // Undecided means nothing has been written, so it is whatever it came as.
                Text(
                    verbatim: state.radioPrivacy == .firepit ? RadioPrivacy.firepit.summary : RadioPrivacy.open.summary
                )
                .foregroundStyle(state.radioPrivacy == .firepit ? FirepitColors.textSecondary : FirepitColors.warn)
                if state.radioPrivacy == .undecided {
                    Text("You haven't chosen yet, so this radio is still set up the way you found it.")
                }
                // Whether going back is a real offer depends on having the old channel to go back to.
                if state.radioPrivacy == .firepit {
                    Text(verbatim: restoreCopy)
                        .foregroundStyle(state.canRestoreRadio ? FirepitColors.textSecondary : FirepitColors.warn)
                }
            }
        }
        // Only meaningful once the primary is ours: the mode works by changing that channel's name, which is what the
        // firmware turns into a frequency.
        if state.radioPrivacy == .firepit {
            Section {
                Picker(
                    "How far messages travel",
                    selection: Binding(get: { state.rangeMode }, set: { onChooseRange($0) })
                ) {
                    ForEach(RangeMode.allCases, id: \.self) { mode in
                        Text(verbatim: mode.label).tag(mode)
                    }
                }
                .pickerStyle(.menu)
                .tint(FirepitColors.primary)
            } footer: {
                Text(
                    """
                    \(state.rangeMode.summary) Everyone in a group has to use the same setting to hear each other; \
                    joining by QR code sets it for you.
                    """
                )
            }
        }
    }

    private func privacyOption(_ privacy: RadioPrivacy, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            if state.radioPrivacy == privacy {
                Label(privacy.label, systemImage: "checkmark")
            } else {
                Text(verbatim: privacy.label)
            }
        }
    }

    private var restoreCopy: String {
        let open = RadioPrivacy.open.label
        if state.canRestoreRadio {
            return String(localized: "Firepit kept this radio's original channel. Choosing \"\(open)\" puts it back.")
        }
        return String(
            localized: """
                Firepit has no earlier channel for this radio, so "\(open)" would leave it on Firepit's and only stop \
                managing it.
                """
        )
    }
}

/// How you hear about a message: grouped by the question being asked rather than by which device the answer is
/// written to.
private struct NotificationSections: View {
    let state: SettingsUiState
    let onShowMessageText: @MainActor (Bool) -> Void
    let onChooseMessageAlerts: @MainActor (MessageAlerts) -> Void

    @Environment(\.openURL) private var openURL
    @State private var authorization: UNAuthorizationStatus = .notDetermined

    var body: some View {
        Section {
            Toggle("Show who and what", isOn: Binding(get: { state.showMessageText }, set: onShowMessageText))
            // iOS keeps its own switch for notifications; when it is off, say so where the question is asked.
            if authorization == .denied {
                Button("Open iOS Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
                .foregroundStyle(FirepitColors.primary)
            }
        } header: {
            Text("Notifications")
        } footer: {
            VStack(alignment: .leading, spacing: FirepitSpacing.s) {
                Text(
                    """
                    Off shows only that a message arrived, not who sent it, where or what it says. A notification is \
                    read by whoever is looking at the phone, which is not always you.
                    """
                )
                if authorization == .denied {
                    Text("Notifications are off in iOS Settings, so Firepit cannot show them until you allow it.")
                        .foregroundStyle(FirepitColors.warn)
                }
            }
        }
        .task {
            authorization = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        }
        Section {
            // A radio setting, so there must be a radio to write it to.
            Picker(
                "Announce a message on",
                selection: Binding(get: { state.messageAlerts }, set: { onChooseMessageAlerts($0) })
            ) {
                ForEach(MessageAlerts.allCases, id: \.self) { alert in
                    Text(verbatim: alert.label).tag(alert)
                }
            }
            .pickerStyle(.menu)
            .tint(FirepitColors.primary)
            .disabled(!state.connected)
        } footer: {
            Text(
                state.connected ? state.messageAlerts.summary : String(localized: "Connect your radio to change this."))
        }
    }
}

/// A row that opens another screen, with what it currently says underneath.
private struct NavigationRow: View {
    let title: LocalizedStringKey
    let subtitle: String
    let action: () -> Void

    init(title: LocalizedStringKey, subtitle: String, action: @escaping () -> Void) {
        self.title = title
        self.subtitle = subtitle
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .foregroundStyle(FirepitColors.textPrimary)
                    Text(verbatim: subtitle)
                        .font(FirepitFont.bodySmall)
                        .foregroundStyle(FirepitColors.textSecondary)
                }
                Spacer()
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

/// A fact with its explanation underneath, as Android's `ListItem` rows in About.
private struct LabeledRow: View {
    let title: LocalizedStringKey
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .foregroundStyle(FirepitColors.textPrimary)
            Text(verbatim: detail)
                .font(FirepitFont.bodySmall)
                .foregroundStyle(FirepitColors.textSecondary)
        }
    }
}

private struct IdentityColourGrid: View {
    let person: Person?
    let tag: String
    let name: String
    let onChoose: (Int?) -> Void

    private let columns = [GridItem(.adaptive(minimum: 52), spacing: FirepitSpacing.m)]

    var body: some View {
        LazyVGrid(columns: columns, spacing: FirepitSpacing.m) {
            ForEach(IdentityColors.choices, id: \.self) { slot in
                Button {
                    onChoose(slot)
                } label: {
                    ZStack(alignment: .bottomTrailing) {
                        IdentityAvatar(nodeNum: person?.id ?? 0, tag: tag, size: 52, slot: slot)
                        if person?.colourSlot == slot {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(FirepitColors.primary)
                                .background(FirepitColors.surface2, in: .circle)
                        }
                    }
                }
                .accessibilityLabel("Colour \((IdentityColors.choices.firstIndex(of: slot) ?? 0) + 1)")
                .accessibilityAddTraits(person?.colourSlot == slot ? .isSelected : [])
            }
        }
        if person?.colourSlot != nil {
            Button("Use my node's colour") { onChoose(nil) }
        }
    }
}
