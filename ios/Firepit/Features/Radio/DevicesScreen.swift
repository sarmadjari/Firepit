import FirepitData
import FirepitModel
import FirepitProtocol
import FirepitTransport
import SwiftUI
import UIKit

/// The Meshtastic devices this phone can talk to, and which one it is using.
struct DevicesScreen: View {
    @State private var viewModel: RadioViewModel
    @State private var openId: String?

    init(app: AppContainer) {
        _viewModel = State(initialValue: RadioViewModel(app: app))
    }

    init(viewModel: RadioViewModel) {
        _viewModel = State(initialValue: viewModel)
    }

    var body: some View {
        let state = viewModel.uiState
        let open = state.saved.first { $0.identifier == openId }
        Group {
            if let open {
                DeviceDetail(
                    radio: open,
                    state: state,
                    viewModel: viewModel,
                    onForgotten: { openId = nil }
                )
            } else {
                DeviceList(state: state, viewModel: viewModel, onOpen: { openId = $0.identifier })
            }
        }
        .task { await viewModel.observe() }
    }
}

/// Which devices exist and which one is in use. Everything else is a tap away.
private struct DeviceList: View {
    let state: RadioUiState
    let viewModel: RadioViewModel
    let onOpen: (SavedRadio) -> Void

    var body: some View {
        List {
            LinkError(message: state.error)
            availabilityRow
            scanSection
            savedSection
            nearbySection
        }
        .navigationTitle("Devices")
        .navigationBarTitleDisplayMode(.inline)
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
        .safeAreaInset(edge: .bottom) {
            if let notice = state.notice {
                Text(verbatim: notice)
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.textPrimary)
                    .padding(FirepitSpacing.m)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(FirepitColors.surface2)
            }
        }
    }

    @ViewBuilder private var availabilityRow: some View {
        switch state.bluetoothAvailability {
        case .poweredOff, .unauthorized, .unsupported:
            Section {
                PermissionNeeded(title: availabilityTitle, message: availabilityMessage)
                    .frame(minHeight: 220)
            }
            .listRowBackground(FirepitColors.surface2)
        default:
            EmptyView()
        }
    }

    private var scanSection: some View {
        Section {
            HStack(spacing: FirepitSpacing.m) {
                Button(state.scanning ? "Stop" : "Scan") {
                    state.scanning ? viewModel.stopScan() : viewModel.startScan()
                }
                .buttonStyle(.borderedProminent)
                .disabled(!canScan)
                if state.scanning {
                    ProgressView()
                    Text("Looking for radios…")
                        .font(FirepitFont.bodySmall)
                        .foregroundStyle(FirepitColors.textSecondary)
                }
            }
            .listRowBackground(FirepitColors.surface2)
        }
    }

    @ViewBuilder private var savedSection: some View {
        if !state.saved.isEmpty {
            Section("Paired Devices") {
                ForEach(state.saved, id: \.identifier) { radio in
                    DeviceRow(
                        radio: radio,
                        status: statusOf(radio, state: state),
                        onBuzz: { viewModel.buzz(radio) },
                        onOpen: { onOpen(radio) }
                    )
                }
            }
            .listRowBackground(FirepitColors.surface2)
        }
    }

    @ViewBuilder private var nearbySection: some View {
        let nearby = state.found.filter { found in
            state.saved.allSatisfy { $0.identifier != found.identifier }
        }
        if !nearby.isEmpty {
            Section("New devices nearby") {
                ForEach(nearby, id: \.identifier) { radio in
                    Button {
                        viewModel.connect(radio)
                    } label: {
                        VStack(alignment: .leading, spacing: FirepitSpacing.xs) {
                            Text(verbatim: radio.name ?? "(unnamed device)")
                                .font(FirepitFont.titleMedium)
                                .foregroundStyle(FirepitColors.textPrimary)
                            Text(verbatim: "Bluetooth · \(radio.identifier) · \(radio.rssi) dBm")
                                .font(FirepitFont.bodySmall)
                                .foregroundStyle(FirepitColors.textSecondary)
                        }
                    }
                }
            }
            .listRowBackground(FirepitColors.surface2)
        }
    }

    private var canScan: Bool {
        switch state.bluetoothAvailability {
        case .unknown, .poweredOn, .resetting: true
        default: false
        }
    }

    private var availabilityTitle: String {
        switch state.bluetoothAvailability {
        case .poweredOff: "Bluetooth is off"
        case .unauthorized: "Bluetooth is not allowed"
        case .unsupported: "Bluetooth is not available"
        default: "Bluetooth is not ready"
        }
    }

    private var availabilityMessage: String {
        switch state.bluetoothAvailability {
        case .poweredOff:
            "Turn Bluetooth on in Settings to scan for Meshtastic radios."
        case .unauthorized:
            "Allow Bluetooth for Firepit in Settings so it can talk to your radio."
        case .unsupported:
            "This device cannot use Bluetooth LE with Meshtastic radios."
        default:
            "Bluetooth is getting ready. Try again in a moment."
        }
    }
}

private struct DeviceRow: View {
    let radio: SavedRadio
    let status: DeviceStatus
    let onBuzz: () -> Void
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: FirepitSpacing.s) {
                RoleBadge(role: radio.role)
                VStack(alignment: .leading, spacing: FirepitSpacing.xs) {
                    Text(verbatim: radio.name)
                        .font(FirepitFont.titleMedium)
                        .foregroundStyle(FirepitColors.textPrimary)
                        .lineLimit(1)
                    Text(verbatim: "\(radio.role.label) · \(status.detail)")
                        .font(FirepitFont.bodySmall)
                        .foregroundStyle(status.tone)
                        .lineLimit(2)
                }
                Spacer(minLength: FirepitSpacing.s)
                if status.active { ActivePill() } else if status.connected { LiveRing(size: 8) }
                Button(action: onBuzz) {
                    Image(icon: .bell)
                        .foregroundStyle(FirepitColors.textSecondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Buzz \(radio.name)"))
                Image(icon: .chevron)
                    .foregroundStyle(FirepitColors.textSecondary)
                    .accessibilityHidden(true)
            }
            .padding(.vertical, FirepitSpacing.xs)
        }
        .buttonStyle(.plain)
        .listRowBackground(
            RoundedRectangle(cornerRadius: FirepitRadius.medium)
                .strokeBorder(status.active ? FirepitColors.primary : .clear)
                .background(FirepitColors.surface2)
        )
    }
}

/// What one saved radio is doing right now.
struct DeviceStatus: Equatable {
    let detail: String
    let tone: Color
    let connected: Bool
    let active: Bool
}

func statusOf(_ radio: SavedRadio, state: RadioUiState) -> DeviceStatus {
    let connected = state.bluetooth.connected.contains(radio.identifier)
    if radio.identifier == state.activeRadioId {
        return DeviceStatus(
            detail: linkStateWords(state.link),
            tone: linkTone(state.link),
            connected: true,
            active: true
        )
    }
    let signal = state.found.first { $0.identifier == radio.identifier }?.rssi
    let whereText: String
    if connected {
        whereText = "Connected · tap to administer"
    } else if let signal {
        whereText = "In range · \(signal) dBm"
    } else if state.scanning {
        whereText = "Not seen yet"
    } else {
        whereText = "Not connected"
    }
    return DeviceStatus(
        detail: whereText,
        tone: FirepitColors.textSecondary,
        connected: connected,
        active: false
    )
}

private func linkTone(_ state: LinkState) -> Color {
    switch state {
    case .ready: FirepitColors.live
    case .unsupported: FirepitColors.danger
    case .disconnected: FirepitColors.stale
    default: FirepitColors.warn
    }
}

/// The radio this session is bound to, among however many are connected.
private struct ActivePill: View {
    var body: some View {
        Text("Active")
            .font(FirepitFont.labelSmall)
            .foregroundStyle(FirepitColors.onPrimary)
            .padding(.horizontal, FirepitSpacing.s)
            .padding(.vertical, FirepitSpacing.xs)
            .background(FirepitColors.primary, in: .rect(cornerRadius: FirepitSpacing.chipCorner))
    }
}

private struct LinkError: View {
    let message: String?

    var body: some View {
        if let message {
            Text(verbatim: message)
                .font(FirepitFont.bodyMedium)
                .foregroundStyle(FirepitColors.danger)
                .listRowBackground(FirepitColors.surface2)
        }
    }
}

/// A radio this phone administers. Tapping the card reconnects to it; the chips say what the radio is for.
private struct RoleBadge: View {
    let role: NodeRole

    var body: some View {
        ZStack {
            Circle().fill(FirepitColors.surface2)
            Image(icon: icon)
                .foregroundStyle(role == .personal ? FirepitColors.textPrimary : FirepitColors.infra)
        }
        .frame(width: 40, height: 40)
        .accessibilityLabel(Text(verbatim: role.label))
    }

    private var icon: FirepitIcon {
        switch role {
        case .personal: .rolePersonal
        case .base: .roleBase
        case .router: .roleRouter
        }
    }
}

/// What this radio calls itself.
private struct NodeNameFields: View {
    let owner: Owner?
    let onRename: (String, String) -> Void

    @State private var longName = ""
    @State private var shortName = ""

    var body: some View {
        VStack(alignment: .leading, spacing: FirepitSpacing.s) {
            TextField("Node name", text: $longName)
                .textFieldStyle(.roundedBorder)
            TextField("Node tag", text: $shortName)
                .textFieldStyle(.roundedBorder)
            Text(verbatim: helper)
                .font(FirepitFont.bodySmall)
                .foregroundStyle(tooLong ? FirepitColors.danger : FirepitColors.textSecondary)
            Button("Rename device") { onRename(longName, shortName) }
                .buttonStyle(.borderedProminent)
                .disabled(!changed || tooLong || longName.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .onAppear { loadOwner() }
        .onChange(of: owner?.longName) { _, _ in loadOwner() }
        .onChange(of: owner?.shortName) { _, _ in loadOwner() }
    }

    private var changed: Bool {
        guard let owner else { return false }
        return longName.trimmingCharacters(in: .whitespaces) != owner.longName
            || shortName.trimmingCharacters(in: .whitespaces) != owner.shortName
    }

    private var tooLong: Bool {
        !OwnerName.fits(
            longName: longName.trimmingCharacters(in: .whitespaces),
            shortName: shortName.trimmingCharacters(in: .whitespaces))
    }

    private var helper: String {
        tooLong
            ? "The radio allows \(OwnerName.maxLongBytes) and \(OwnerName.maxShortBytes) bytes"
            : "Stored on the radio, and seen by apps that are not Firepit"
    }

    private func loadOwner() {
        longName = owner?.longName ?? ""
        shortName = owner?.shortName ?? ""
    }
}

/// How often your location goes to the room you share it with.
private struct BeaconFields: View {
    let rate: BeaconRate?
    let whenMoved: Bool
    let onRate: (BeaconRate) -> Void
    let onWhenMoved: (Bool) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: FirepitSpacing.xs) {
            SectionLabel("Beacon")
            Text(
                """
                How often your location goes to the room you share it with, while you share it. Your phone seals and \
                sends it, so it pauses if your phone is away from this radio.
                """
            )
            .font(FirepitFont.bodySmall)
            .foregroundStyle(FirepitColors.textSecondary)
            ChipRow {
                ForEach(BeaconRate.allCases, id: \.name) { choice in
                    FirepitChip(verbatim: choice.label, selected: rate == choice) { onRate(choice) }
                }
            }
            if rate == nil {
                Text("Set to an interval Firepit does not offer. Any choice replaces it.")
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.textSecondary)
            }
            Toggle(isOn: Binding(get: { whenMoved }, set: { value in onWhenMoved(value) })) {
                VStack(alignment: .leading) {
                    Text("Only when I've moved")
                    Text("Saves airtime and battery while you are sitting still")
                        .font(FirepitFont.bodySmall)
                        .foregroundStyle(FirepitColors.textSecondary)
                }
            }
            Text("Changing either restarts the radio.")
                .font(FirepitFont.bodySmall)
                .foregroundStyle(FirepitColors.warn)
        }
    }
}

/// Everything about one device, in the order you would ask about it.
private struct DeviceDetail: View {
    let radio: SavedRadio
    let state: RadioUiState
    let viewModel: RadioViewModel
    let onForgotten: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var confirmingForget = false

    var body: some View {
        List {
            headSection
            detailSections
        }
        .navigationTitle(radio.name)
        .navigationBarTitleDisplayMode(.inline)
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
        .confirmationDialog("Forget \(radio.name)?", isPresented: $confirmingForget, titleVisibility: .visible) {
            forgetButtons
        } message: {
            Text(forgetMessage)
        }
        .alert(
            "New Bluetooth PIN",
            isPresented: Binding(get: { state.newPin != nil }, set: { if !$0 { viewModel.dismissNewPin() } })
        ) {
            Button("Copy") {
                if let pin = state.newPin { UIPasteboard.general.string = pinText(pin) }
            }
            Button("I've written it down") { viewModel.dismissNewPin() }
        } message: {
            if let pin = state.newPin {
                Text(pinDialogMessage(pin))
            }
        }
    }

    private var connected: Bool { state.connectedTo == radio.identifier }

    private var headSection: some View {
        Section {
            Text(verbatim: "\(radio.transport.label) · \(radio.identifier)")
                .font(FirepitFont.bodySmall)
                .foregroundStyle(FirepitColors.textSecondary)
            HStack {
                if connected {
                    Button("Disconnect") { viewModel.disconnect() }
                        .buttonStyle(.bordered)
                } else {
                    Button("Connect") { viewModel.connectSaved(radio) }
                        .buttonStyle(.borderedProminent)
                }
                Button("Forget", role: .destructive) { confirmingForget = true }
            }
            if let error = state.error {
                Text(verbatim: error)
                    .font(FirepitFont.bodyMedium)
                    .foregroundStyle(FirepitColors.danger)
            }
            SectionLabel("What it is for")
            ChipRow {
                ForEach(NodeRole.allCases, id: \.name) { role in
                    FirepitChip(verbatim: role.label, selected: radio.role == role) {
                        viewModel.setRole(radio, role: role)
                    }
                }
            }
            Toggle(isOn: Binding(get: { radio.onMap }, set: { viewModel.showOnMap(radio, onMap: $0) })) {
                VStack(alignment: .leading) {
                    Text("Show on the map")
                    Text(radio.nodeNum == nil ? missingMarkerCopy : hiddenMarkerCopy)
                        .font(FirepitFont.bodySmall)
                        .foregroundStyle(FirepitColors.textSecondary)
                }
            }
            .disabled(radio.nodeNum == nil)
        }
        .listRowBackground(FirepitColors.surface2)
    }

    @ViewBuilder private var detailSections: some View {
        let details = state.details.takeIf(connected)
        if let details {
            if state.identityDoubt == radio.identifier {
                IdentityWarning(onTrust: viewModel.trustConnectedRadio, onDisconnect: viewModel.disconnect)
            }
            if !state.risks.isEmpty {
                SecurityFields(risks: state.risks, onFix: viewModel.fixRisk)
            }
            Section("Name on the mesh") {
                NodeNameFields(owner: state.owner, onRename: viewModel.renameNode)
            }
            Section {
                BeaconFields(
                    rate: state.beaconRate, whenMoved: state.beaconWhenMoved, onRate: viewModel.setBeaconRate,
                    onWhenMoved: viewModel.setBeaconWhenMoved)
            }
            Section {
                RelayFields(reach: state.relayReach, channels: details.channels, onReach: viewModel.setRelayReach)
            }
            Section("About this device") {
                Field("Node", details.nodeId, monospace: true)
                Field("Firmware", details.firmware)
                if !details.capabilities.isSupported {
                    Text(unsupportedFirmwareMessage)
                        .font(FirepitFont.bodySmall)
                        .foregroundStyle(FirepitColors.danger)
                }
                Field("Hardware", prettyName(details.hardware))
                Field("Region", details.region.replacingOccurrences(of: "_", with: " "))
                Field("Encryption keys", details.capabilities.supportsPki ? "Yes" : "No")
                Field("Signed messages", details.capabilities.supportsSigning ? "Yes" : "No")
            }
            Section("Channels") {
                ForEach(details.channels.filter { $0.role != "DISABLED" }) { channel in
                    Field(
                        "\(channel.index)  \(prettyName(channel.role))",
                        "\(channel.name) · \(channel.keyLabel)"
                    )
                }
            }
        } else {
            Section {
                Text("Connect this device to change its name, how it beacons, and who it relays for.")
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.textSecondary)
            }
            .listRowBackground(FirepitColors.surface2)
        }
    }

    @ViewBuilder private var forgetButtons: some View {
        if connected {
            Button("Take rooms off and forget", role: .destructive) {
                viewModel.forget(radio, takeRoomsOff: true)
                onForgotten()
            }
        } else if radio.nodeNum != nil && state.connectedTo != nil {
            Button("Remove it from my rooms", role: .destructive) {
                viewModel.removeFromRooms(radio)
            }
        }
        Button("Just forget", role: .destructive) {
            viewModel.forget(radio, takeRoomsOff: false)
            onForgotten()
        }
        Button("Keep", role: .cancel) {}
    }

    private var forgetMessage: String {
        if connected {
            connectedForgetMessage
        } else {
            disconnectedForgetMessage
        }
    }

    private let missingMarkerCopy = "Connect it once so Firepit knows which marker is this device"
    private let hiddenMarkerCopy = "Off keeps it out of the way while it goes on relaying"
    private var unsupportedFirmwareMessage: String {
        """
        Firepit needs \(RadioCapabilities.minimumFirmware.raw) or newer. On this one, private messages and rooms \
        may not work as described.
        """
    }
    private let connectedForgetMessage =
        """
        Firepit will stop administering this radio. It still holds your rooms' channel keys: take the rooms off \
        first if someone else will have it. Your phone keeps the rooms.
        """
    private let disconnectedForgetMessage =
        """
        Firepit will stop administering this radio, but it still holds your rooms' channel keys. If it is lost or \
        someone else has it, remove it from your rooms so it gets none of their new keys.
        """

    private func pinDialogMessage(_ pin: UInt32) -> String {
        """
        \(pinText(pin))

        Write it down. The radio restarts, and your phone will ask for this PIN when it reconnects. \
        Nobody can pair with the radio without it.
        """
    }
}

/// Who this radio will pass traffic on for.
private struct RelayFields: View {
    let reach: RelayReach?
    let channels: [ChannelRow]
    let onReach: (RelayReach) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: FirepitSpacing.xs) {
            SectionLabel("Relays for")
            ChipRow {
                ForEach(RelayReach.allCases, id: \.name) { choice in
                    FirepitChip(verbatim: choice.label, selected: reach == choice) { onReach(choice) }
                }
            }
            Text(explanation)
                .font(FirepitFont.bodySmall)
                .foregroundStyle(FirepitColors.textSecondary)
            if reach == .group && !openChannels.isEmpty {
                Text(openWarning)
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.warn)
            }
            Text("Changing this restarts the radio.")
                .font(FirepitFont.bodySmall)
                .foregroundStyle(FirepitColors.textSecondary)
        }
    }

    private var live: [ChannelRow] { channels.filter { $0.role != "DISABLED" } }
    private var openChannels: [ChannelRow] { live.filter { !$0.key.isPrivate } }

    private var explanation: String {
        switch reach {
        case nil: "Set to something Firepit does not offer. Either choice replaces it"
        case .group: "Your group is whoever holds the keys to this radio's channels"
        case .everyone: "Carries traffic for any mesh on the same frequency"
        }
    }

    private var openWarning: String {
        let names = openChannels.map { $0.name.isEmpty ? "Channel \($0.index)" : $0.name }.joined(separator: ", ")
        return names
            + (openChannels.count == 1
                ? " is not private, so its traffic still counts as yours."
                : " are not private, so their traffic still counts as yours.")
    }
}

/// A radio at this address answered as somebody else.
private struct IdentityWarning: View {
    let onTrust: () -> Void
    let onDisconnect: () -> Void

    var body: some View {
        Section("Not the radio you saved") {
            Text(identityWarningMessage)
                .font(FirepitFont.bodySmall)
                .foregroundStyle(FirepitColors.danger)
            HStack {
                Button("Disconnect") { onDisconnect() }
                    .buttonStyle(.bordered)
                Button("It's mine") { onTrust() }
            }
        }
        .listRowBackground(FirepitColors.surface2)
    }

    private let identityWarningMessage =
        """
        Something answered at this radio's address with a different identity. If you reset or reflashed it, that \
        is expected. If not, it may not be your radio.
        """
}

/// What in this radio's own settings lets people read it or run it, each with its fix.
private struct SecurityFields: View {
    let risks: [RadioRisk]
    let onFix: (RadioRisk) -> Void

    var body: some View {
        Section("Security") {
            ForEach(risks, id: \.self) { risk in
                VStack(alignment: .leading, spacing: FirepitSpacing.xs) {
                    Text(verbatim: risk.title)
                        .font(FirepitFont.bodyMedium)
                        .foregroundStyle(FirepitColors.warn)
                    Text(verbatim: risk.detail)
                        .font(FirepitFont.bodySmall)
                        .foregroundStyle(FirepitColors.textSecondary)
                    if risk.canFix {
                        Button(fixLabel(risk)) { onFix(risk) }
                            .buttonStyle(.bordered)
                    }
                }
            }
            Text("Each fix restarts the radio.")
                .font(FirepitFont.bodySmall)
                .foregroundStyle(FirepitColors.warn)
        }
        .listRowBackground(FirepitColors.surface2)
    }
}

private func fixLabel(_ risk: RadioRisk) -> String {
    switch risk {
    case .bluetoothOpen, .bluetoothDefaultPin: "Set a new PIN"
    case .remoteAdminKey: "Remove remote admin"
    case .legacyAdminChannel: "Turn off the admin channel"
    case .debugLog: "Turn off debug logs"
    case .mqttUplink, .mqttMapReport: "Turn off MQTT"
    case .managed: ""
    }
}

struct Field: View {
    let label: String
    let value: String
    var monospace = false

    init(_ label: String, _ value: String, monospace: Bool = false) {
        self.label = label
        self.value = value
        self.monospace = monospace
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text(verbatim: label)
                .font(FirepitFont.bodyMedium)
                .foregroundStyle(FirepitColors.textSecondary)
            Spacer(minLength: FirepitSpacing.m)
            Text(verbatim: value)
                .font(monospace ? FirepitFont.mono : FirepitFont.bodyMedium)
                .foregroundStyle(FirepitColors.textPrimary)
                .multilineTextAlignment(.trailing)
                .textSelection(.enabled)
        }
        .accessibilityElement(children: .combine)
    }
}

extension Optional {
    fileprivate func takeIf(_ condition: Bool) -> Wrapped? {
        condition ? self : nil
    }
}
