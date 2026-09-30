import FirepitData
import FirepitModel
import FirepitProtocol
import FirepitTransport
import SwiftUI
import UserNotifications

enum SettingsSection: String, Hashable, CaseIterable {
    case devices
    case nodes
    case offlineMaps
    case pins
}

enum SettingsRoute: String, Hashable, CaseIterable {
    case you
    case radio
    case devices
    case nodes
    case notifications
    case retention
    case privacy
    case location
    case offlineMaps
    case pins
    case appearance
    case about
}

/// Settings. Ported from android/app/…/settings/SettingsScreen.kt.
struct SettingsScreen: View {
    let app: AppContainer

    @State private var path: [SettingsRoute]
    @State private var model: SettingsViewModel
    @State private var radioModel: RadioViewModel
    @State private var sharingModel: SharingViewModel

    init(app: AppContainer) {
        self.init(app: app, initialRoute: nil)
    }

    init(app: AppContainer, initialRoute: SettingsRoute?) {
        self.app = app
        _path = State(initialValue: initialRoute.map { [$0] } ?? [])
        _model = State(initialValue: SettingsViewModel(app: app))
        _radioModel = State(initialValue: RadioViewModel(app: app))
        _sharingModel = State(initialValue: SharingViewModel(location: app.location, mesh: app.mesh))
    }

    var body: some View {
        NavigationStack(path: $path) {
            SettingsMainPage(
                state: model.state,
                radio: radioModel.uiState,
                sharing: sharingModel.state,
                onOpen: { path.append($0) }
            )
            .navigationDestination(for: SettingsRoute.self) { route in
                destination(route)
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
    private func destination(_ route: SettingsRoute) -> some View {
        switch route {
        case .you:
            PersonSettingsPage(state: model.state, onSave: model.savePerson, onUseAsNodeName: model.useAsNodeName) {
                model.chooseIdentitySlot($0)
            }
            .toolbar(.hidden, for: .tabBar)
        case .radio:
            RadioSettingsPage(
                state: model.state,
                onChooseRange: model.chooseRange,
                onMakePrivate: model.makeRadioPrivate,
                onKeepPublic: model.keepRadioPublic
            )
            .toolbar(.hidden, for: .tabBar)
        case .devices:
            DevicesScreen(viewModel: radioModel)
                .toolbar(.hidden, for: .tabBar)
        case .nodes:
            NodesScreen(viewModel: radioModel)
                .toolbar(.hidden, for: .tabBar)
        case .notifications:
            NotificationSettingsPage(
                state: model.state,
                onShowMessageText: model.setShowMessageText,
                onChooseMessageAlerts: model.chooseMessageAlerts
            )
            .toolbar(.hidden, for: .tabBar)
        case .retention:
            RetentionSettingsPage(
                state: model.state,
                onChooseRetention: model.chooseRetention,
                onChooseRoomLifetime: model.chooseRoomLifetime,
                onEraseHistory: model.eraseHistory
            )
            .toolbar(.hidden, for: .tabBar)
        case .privacy:
            PrivacySettingsPage(state: model.state, onAllowScreenCapture: model.setAllowScreenCapture)
                .toolbar(.hidden, for: .tabBar)
        case .location:
            LocationSettingsPage(state: sharingModel.state, model: sharingModel)
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
        case .appearance:
            AppearanceSettingsPage(state: model.state, onChooseTheme: model.chooseTheme)
                .toolbar(.hidden, for: .tabBar)
        case .about:
            AboutSettingsPage()
                .toolbar(.hidden, for: .tabBar)
        }
    }
}

private struct SettingsMainPage: View {
    let state: SettingsUiState
    let radio: RadioUiState
    let sharing: SharingUiState
    let onOpen: (SettingsRoute) -> Void

    var body: some View {
        Form {
            Section("You") {
                SettingsNavRow(
                    title: "Your identity",
                    subtitle: state.person?.name ?? "Set your name and initials",
                    route: .you,
                    onOpen: onOpen
                )
            }
            .listRowBackground(FirepitColors.surface2)

            Section("Radio") {
                SettingsNavRow(
                    title: "Devices",
                    subtitle: SettingsViewModel.deviceSummary(radio.link),
                    route: .devices,
                    onOpen: onOpen
                )
                SettingsNavRow(
                    title: "Nodes",
                    subtitle: SettingsViewModel.nodeSummary(count: radio.nodes.count),
                    route: .nodes,
                    onOpen: onOpen
                )
                SettingsNavRow(
                    title: "Radio privacy and range",
                    subtitle: state.radioPrivacy.label,
                    route: .radio,
                    onOpen: onOpen
                )
            }
            .listRowBackground(FirepitColors.surface2)

            Section("Notifications") {
                SettingsNavRow(
                    title: "Message notifications",
                    subtitle: state.showMessageText ? "Show who and what" : "Hide message text",
                    route: .notifications,
                    onOpen: onOpen
                )
            }
            .listRowBackground(FirepitColors.surface2)

            Section("Messages") {
                SettingsNavRow(
                    title: "History and room lifetime",
                    subtitle: "Keep messages for \(state.retention.label)",
                    route: .retention,
                    onOpen: onOpen
                )
            }
            .listRowBackground(FirepitColors.surface2)

            Section("Privacy") {
                SettingsNavRow(
                    title: "Screenshots and recording",
                    subtitle: state.allowScreenCapture ? "Allowed" : "Hidden in previews and recordings",
                    route: .privacy,
                    onOpen: onOpen
                )
            }
            .listRowBackground(FirepitColors.surface2)

            Section("Map") {
                Button {
                    onOpen(.location)
                } label: {
                    SharingRow(state: sharing) { onOpen(.location) }
                }
                .buttonStyle(.plain)
                SettingsNavRow(
                    title: "Offline areas",
                    subtitle: "Download map tiles so the map works with no signal",
                    route: .offlineMaps,
                    onOpen: onOpen
                )
                SettingsNavRow(
                    title: "Dropped pins",
                    subtitle: "Rename or delete the pins on your map",
                    route: .pins,
                    onOpen: onOpen
                )
            }
            .listRowBackground(FirepitColors.surface2)

            Section("Appearance") {
                SettingsNavRow(
                    title: "Theme",
                    subtitle: state.theme.label,
                    route: .appearance,
                    onOpen: onOpen
                )
            }
            .listRowBackground(FirepitColors.surface2)

            Section("About") {
                SettingsNavRow(
                    title: "Firepit",
                    subtitle: "Meshtastic chat, rooms and maps that work off-grid",
                    route: .about,
                    onOpen: onOpen
                )
            }
            .listRowBackground(FirepitColors.surface2)
        }
        .navigationTitle("Settings")
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
    }
}

private struct SettingsNavRow: View {
    let title: LocalizedStringKey
    let subtitle: String
    let route: SettingsRoute
    let onOpen: (SettingsRoute) -> Void

    var body: some View {
        Button {
            onOpen(route)
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(FirepitFont.bodyLarge)
                        .foregroundStyle(FirepitColors.textPrimary)
                    Text(verbatim: subtitle)
                        .font(FirepitFont.bodyMedium)
                        .foregroundStyle(FirepitColors.textSecondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(FirepitColors.textSecondary)
                    .accessibilityHidden(true)
            }
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}

private struct PersonSettingsPage: View {
    let state: SettingsUiState
    let onSave: (String, String) -> Void
    let onUseAsNodeName: () -> Void
    let onChooseIdentity: (Int?) -> Void

    @State private var name: String
    @State private var tag: String
    @State private var tagChosen: Bool
    @State private var confirmingRadioName = false

    init(
        state: SettingsUiState,
        onSave: @escaping (String, String) -> Void,
        onUseAsNodeName: @escaping () -> Void,
        onChooseIdentity: @escaping (Int?) -> Void
    ) {
        self.state = state
        self.onSave = onSave
        self.onUseAsNodeName = onUseAsNodeName
        self.onChooseIdentity = onChooseIdentity
        let person = state.person
        _name = State(initialValue: person?.name ?? "")
        _tag = State(initialValue: person?.tag ?? "")
        _tagChosen = State(initialValue: person != nil && person?.tag != Person.initialsFor(name: person?.name ?? ""))
    }

    private func load(_ person: Person?) {
        name = person?.name ?? ""
        tag = person?.tag ?? ""
        tagChosen = person != nil && person?.tag != Person.initialsFor(name: person?.name ?? "")
    }

    /// An emptied field falls back to the name rather than being rewritten as you delete.
    private var effectiveTag: String {
        tag.trimmingCharacters(in: .whitespaces).isEmpty ? Person.initialsFor(name: name) : tag
    }
    private var changed: Bool {
        name.trimmingCharacters(in: .whitespaces) != (state.person?.name ?? "")
            || effectiveTag != (state.person?.tag ?? "")
    }
    private var tooLong: Bool { name.utf8.count > OwnerName.maxLongBytes || tag.utf8.count > OwnerName.maxShortBytes }

    var body: some View {
        Form {
            Section {
                HStack(spacing: FirepitSpacing.m) {
                    IdentityAvatar(
                        nodeNum: state.person?.id ?? 0, tag: effectiveTag, size: 64, slot: state.person?.colourSlot)
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
                    }
                }
                if name.utf8.count > OwnerName.maxLongBytes {
                    Text("Longer than a mesh packet can carry by \(name.utf8.count - OwnerName.maxLongBytes) bytes")
                        .foregroundStyle(FirepitColors.danger)
                } else {
                    Text("How this phone refers to you. The mesh sees your device's name")
                        .foregroundStyle(FirepitColors.textSecondary)
                }
                if tag.utf8.count > OwnerName.maxShortBytes {
                    Text("Up to \(OwnerName.maxShortBytes) characters")
                        .foregroundStyle(FirepitColors.danger)
                } else if tag.trimmingCharacters(in: .whitespaces).isEmpty {
                    Text("Empty follows your name: \(effectiveTag)")
                        .foregroundStyle(FirepitColors.textSecondary)
                } else if tagChosen {
                    Text("Yours. Clear it to follow your name again")
                        .foregroundStyle(FirepitColors.textSecondary)
                } else {
                    Text("Follows your name. Type your own if you prefer")
                        .foregroundStyle(FirepitColors.textSecondary)
                }
            } footer: {
                Text(
                    """
                    People in your rooms see this name, sealed. Everyone else nearby sees your radio's own name, \
                    which it broadcasts in the open.
                    """
                )
            }
            .listRowBackground(FirepitColors.surface2)

            Section {
                IdentityColourGrid(person: state.person, tag: effectiveTag, name: name, onChoose: onChooseIdentity)
            } header: {
                Text("Your colour")
            } footer: {
                Text(
                    """
                    Shared with your Firepit rooms, so the people you invited see you in this colour too. \
                    Everyone else draws you from your node number.
                    """
                )
            }
            .listRowBackground(FirepitColors.surface2)

            Section {
                Button("Save") { onSave(name, effectiveTag) }
                    .disabled(!changed || tooLong || name.trimmingCharacters(in: .whitespaces).isEmpty)
                Button("Use on my radio") { confirmingRadioName = true }
                    .disabled(!state.connected || state.person == nil || changed)
            }
            .listRowBackground(FirepitColors.surface2)
        }
        .navigationTitle("You")
        .navigationBarTitleDisplayMode(.inline)
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
        .onChange(of: state.person, initial: true) { _, person in
            load(person)
        }
        .confirmationDialog("Put your name on your radio?", isPresented: $confirmingRadioName) {
            Button("Use it") { onUseAsNodeName() }
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

private struct RadioSettingsPage: View {
    let state: SettingsUiState
    let onChooseRange: (RangeMode) -> Void
    let onMakePrivate: () -> Void
    let onKeepPublic: () -> Void

    var body: some View {
        Form {
            Section {
                Text(
                    """
                    Your rooms, messages, pins and locations are always sealed. This only decides who can see the \
                    radio's own name and battery level: every Meshtastic device, or only devices running Firepit.
                    """
                )
                .font(FirepitFont.bodyMedium)
                .foregroundStyle(FirepitColors.textSecondary)
            }
            .listRowBackground(FirepitColors.surface2)

            Section("This radio's own identity") {
                Picker("This radio's own identity", selection: privacyBinding) {
                    Text(verbatim: RadioPrivacy.open.label).tag(RadioPrivacy?.some(.open))
                    Text(verbatim: RadioPrivacy.firepit.label).tag(RadioPrivacy?.some(.firepit))
                }
                .pickerStyle(.inline)
                .labelsHidden()
                Text(state.radioPrivacy == .firepit ? RadioPrivacy.firepit.summary : RadioPrivacy.open.summary)
                    .foregroundStyle(state.radioPrivacy == .firepit ? FirepitColors.textSecondary : FirepitColors.warn)
                if state.radioPrivacy == .undecided {
                    Text("You haven't chosen yet, so this radio is still set up the way you found it.")
                        .foregroundStyle(FirepitColors.textSecondary)
                }
                if state.radioPrivacy == .firepit {
                    Text(restoreCopy)
                        .foregroundStyle(state.canRestoreRadio ? FirepitColors.textSecondary : FirepitColors.warn)
                }
            }
            .listRowBackground(FirepitColors.surface2)

            if state.radioPrivacy == .firepit {
                Section("How far messages travel") {
                    Picker("How far messages travel", selection: rangeBinding) {
                        ForEach(RangeMode.allCases, id: \.self) { entry in
                            Text(verbatim: entry.label).tag(entry)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                    Text(
                        """
                        \(state.rangeMode.summary) Everyone in a group has to use the same setting to hear each other; \
                        joining by QR code sets it for you.
                        """
                    )
                    .foregroundStyle(FirepitColors.textSecondary)
                }
                .listRowBackground(FirepitColors.surface2)
            }
        }
        .navigationTitle("Radio")
        .navigationBarTitleDisplayMode(.inline)
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
    }

    /// Not a plain enum choice: each answer runs a different action, and undecided is a state rather than something
    /// to offer, so it selects neither and either answer can still be given.
    private var privacyBinding: Binding<RadioPrivacy?> {
        Binding(
            get: { state.radioPrivacy == .undecided ? nil : state.radioPrivacy },
            set: { choice in
                switch choice {
                case .firepit: onMakePrivate()
                case .open: onKeepPublic()
                case .undecided, nil: break
                }
            }
        )
    }

    private var rangeBinding: Binding<RangeMode> {
        Binding(get: { state.rangeMode }, set: { value in onChooseRange(value) })
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

private struct NotificationSettingsPage: View {
    let state: SettingsUiState
    let onShowMessageText: (Bool) -> Void
    let onChooseMessageAlerts: (MessageAlerts) -> Void

    @Environment(\.openURL) private var openURL
    @State private var authorization: UNAuthorizationStatus = .notDetermined

    var body: some View {
        Form {
            Section {
                Toggle(
                    "Show who and what",
                    isOn: Binding(get: { state.showMessageText }, set: { value in onShowMessageText(value) }))
                Picker("Announce a message on", selection: alertsBinding) {
                    ForEach(MessageAlerts.allCases, id: \.self) { entry in
                        Text(verbatim: entry.label).tag(entry)
                    }
                }
                .pickerStyle(.inline)
                .disabled(!state.connected)
            } footer: {
                VStack(alignment: .leading, spacing: FirepitSpacing.s) {
                    Text(
                        """
                        Off shows only that a message arrived, not who sent it, where or what it says. \
                        A notification is read by whoever is looking at the phone, which is not always you.
                        """
                    )
                    Text(state.connected ? state.messageAlerts.summary : "Connect your radio to change this.")
                    if authorization == .denied {
                        Button("Open iOS Settings") {
                            openURL(URL(string: UIApplication.openSettingsURLString) ?? URL(fileURLWithPath: "/"))
                        }
                        Text("Notifications are off in iOS Settings, so Firepit cannot show them until you allow it.")
                    }
                }
            }
            .listRowBackground(FirepitColors.surface2)
        }
        .navigationTitle("Notifications")
        .navigationBarTitleDisplayMode(.inline)
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
        .task {
            let settings = await UNUserNotificationCenter.current().notificationSettings()
            authorization = settings.authorizationStatus
        }
    }

    private var alertsBinding: Binding<MessageAlerts> {
        Binding(get: { state.messageAlerts }, set: { value in onChooseMessageAlerts(value) })
    }
}

private struct RetentionSettingsPage: View {
    let state: SettingsUiState
    let onChooseRetention: (MessageRetention) -> Void
    let onChooseRoomLifetime: (RoomLifetime) -> Void
    let onEraseHistory: () -> Void

    @State private var confirmingErase = false

    var body: some View {
        Form {
            Section {
                Picker("Keep messages for", selection: retentionBinding) {
                    ForEach(MessageRetention.allCases, id: \.self) { entry in
                        Text(verbatim: entry.label).tag(entry)
                    }
                }
                .pickerStyle(.inline)
            } footer: {
                Text(
                    """
                    Older messages are always deleted from this phone — there is no keeping them. Everyone else holds \
                    their own copy, and nothing on a mesh can delete theirs.
                    """
                )
            }
            .listRowBackground(FirepitColors.surface2)

            Section {
                Picker("Leave quiet rooms", selection: roomLifetimeBinding) {
                    ForEach(RoomLifetime.allCases, id: \.self) { entry in
                        Text(verbatim: entry.label).tag(entry)
                    }
                }
                .pickerStyle(.inline)
            } footer: {
                Text("Leaving takes the room's messages and its key with it, and cannot be undone.")
            }
            .listRowBackground(FirepitColors.surface2)

            Section {
                Button("Erase history on this phone", role: .destructive) { confirmingErase = true }
            } footer: {
                Text("Messages, pins, where people were, their names, and the map tiles you looked at")
            }
            .listRowBackground(FirepitColors.surface2)
        }
        .navigationTitle("Messages")
        .navigationBarTitleDisplayMode(.inline)
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
        .confirmationDialog("Erase history?", isPresented: $confirmingErase) {
            Button("Erase", role: .destructive) { onEraseHistory() }
            Button("Keep", role: .cancel) {}
        } message: {
            Text(
                """
                Deletes every message, pin, last-known position, name card and browsed map tile on this phone. \
                Your rooms and downloaded areas stay. Everyone else keeps their own copy.
                """
            )
        }
    }

    private var retentionBinding: Binding<MessageRetention> {
        Binding(get: { state.retention }, set: { value in onChooseRetention(value) })
    }

    private var roomLifetimeBinding: Binding<RoomLifetime> {
        Binding(get: { state.roomLifetime }, set: { value in onChooseRoomLifetime(value) })
    }
}

private struct PrivacySettingsPage: View {
    let state: SettingsUiState
    let onAllowScreenCapture: (Bool) -> Void

    var body: some View {
        Form {
            Section {
                Toggle(
                    "Allow screenshots",
                    isOn: Binding(get: { state.allowScreenCapture }, set: { value in onAllowScreenCapture(value) })
                )
            } footer: {
                Text(
                    """
                    Off hides Firepit in the app switcher and while the screen is recorded or mirrored. iOS does not \
                    let apps block screenshots. Invite codes are always hidden from recordings and the app switcher.
                    """
                )
            }
            .listRowBackground(FirepitColors.surface2)
        }
        .navigationTitle("Privacy")
        .navigationBarTitleDisplayMode(.inline)
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
    }
}

private struct LocationSettingsPage: View {
    let state: SharingUiState
    let model: SharingViewModel
    @State private var pickingRoom = false

    var body: some View {
        Form {
            Section {
                SharingRow(state: state) { pickingRoom = true }
            }
            .listRowBackground(FirepitColors.surface2)
        }
        .navigationTitle("Your location")
        .navigationBarTitleDisplayMode(.inline)
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
        .sheet(isPresented: $pickingRoom) {
            ShareLocationSheet(
                state: state,
                onDismiss: { pickingRoom = false },
                onShare: { roomId, choice in
                    pickingRoom = false
                    model.share(roomId: roomId, choice: choice)
                },
                onStop: {
                    pickingRoom = false
                    model.stop()
                }
            )
        }
    }
}

private struct AppearanceSettingsPage: View {
    let state: SettingsUiState
    let onChooseTheme: (ThemeChoice) -> Void

    var body: some View {
        Form {
            Section {
                Picker("Theme", selection: Binding(get: { state.theme }, set: { value in onChooseTheme(value) })) {
                    ForEach(ThemeChoice.allCases, id: \.self) { entry in
                        Text(entry.label).tag(entry)
                    }
                }
                .pickerStyle(.inline)
            } footer: {
                Text("Dark keeps a torch-lit camp readable and does not flare in your eyes at night.")
            }
            .listRowBackground(FirepitColors.surface2)
        }
        .navigationTitle("Appearance")
        .navigationBarTitleDisplayMode(.inline)
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
    }
}

private struct AboutSettingsPage: View {
    var body: some View {
        Form {
            Section {
                LabeledContent("Firepit", value: "Meshtastic chat, rooms and maps that work off-grid")
                Text(
                    """
                    Meshtastic radios running firmware \(RadioCapabilities.minimumFirmware.raw) or newer. \
                    An older node is refused rather than half-supported, because the privacy Firepit describes would \
                    not hold on it.
                    """
                )
            } header: {
                Text("Works with")
            }
            .listRowBackground(FirepitColors.surface2)
        }
        .navigationTitle("About")
        .navigationBarTitleDisplayMode(.inline)
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
    }
}
