import FirepitCrypto
import FirepitModel
import SwiftUI

enum NewRoomKind: String, CaseIterable, Identifiable {
    case firepit
    case meshtasticShared

    var id: Self { self }

    var label: String {
        switch self {
        case .firepit: "Firepit"
        case .meshtasticShared: "Meshtastic"
        }
    }

    var readableBy: String {
        switch self {
        case .firepit: "Only the people you invite"
        case .meshtasticShared: "Anyone who has the channel's key"
        }
    }

    var blurb: String {
        switch self {
        case .firepit:
            """
            Firepit's own kind of room. Messages are sealed on your phone before the radio ever sees them, so nobody \
            on the Meshtastic network can read them — not even someone holding one of the radios. Names, colours, and \
            delivery and read status all work here. You invite people with a Firepit QR code.
            """
        case .meshtasticShared:
            """
            A standard Meshtastic channel, for talking to people who are not running Firepit. Its key is kept on the \
            radios, so anyone holding one can read everything sent here. Firepit's own features are switched off, \
            because other apps would not understand them. You share it from the Meshtastic app.
            """
        }
    }
}

/// Name entry for a new room. The byte budget is the radio's, not a UI choice.
struct CreateRoomDialog: View {
    let onDismiss: () -> Void
    let onCreate: (String) -> Void
    var onCreateShared: (String) -> Void = { _ in }

    @State private var name = ""
    @State private var kind = NewRoomKind.firepit

    private var trimmed: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var bytes: Int { trimmed.data(using: .utf8)?.count ?? 0 }
    private var tooLong: Bool { bytes > InviteCodec.maxRoomNameBytes }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Kind", selection: $kind) {
                        ForEach(NewRoomKind.allCases) { option in
                            Text(option.label).tag(option)
                        }
                    }
                    .pickerStyle(.segmented)

                    TextField("Camp", text: $name)
                        .textInputAutocapitalization(.words)
                    Text("\(InviteCodec.maxRoomNameBytes - bytes) bytes left")
                        .font(FirepitFont.bodySmall)
                        .foregroundStyle(tooLong ? FirepitColors.danger : FirepitColors.textSecondary)
                }
                Section {
                    Text(kind.readableBy)
                        .foregroundStyle(kind == .firepit ? FirepitColors.textSecondary : FirepitColors.warn)
                    Text(kind.blurb)
                        .font(FirepitFont.bodySmall)
                        .foregroundStyle(FirepitColors.textSecondary)
                    if kind == .firepit {
                        Text(
                            """
                            Everyone you invite gets the room's key. There is no way to remove one person later \
                            without making a new key for everybody.
                            """
                        )
                        .font(FirepitFont.bodySmall)
                        .foregroundStyle(FirepitColors.textSecondary)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(FirepitColors.surface)
            .navigationTitle("New room")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onDismiss)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        switch kind {
                        case .firepit: onCreate(trimmed)
                        case .meshtasticShared: onCreateShared(trimmed)
                        }
                    }
                    .disabled(trimmed.isEmpty || tooLong)
                }
            }
        }
    }
}

/// Rotating QR invite.
struct InviteScreen: View {
    let roomId: Int32
    let roomName: String
    let onBack: () -> Void

    @State private var viewModel: RoomsViewModel
    @State private var fingerprint: String?
    private let secureWindow: SecureWindow

    init(
        roomId: Int32,
        roomName: String,
        onBack: @escaping () -> Void,
        viewModel: RoomsViewModel,
        secureWindow: SecureWindow = SecureWindow()
    ) {
        self.roomId = roomId
        self.roomName = roomName
        self.onBack = onBack
        self.secureWindow = secureWindow
        _viewModel = State(initialValue: viewModel)
    }

    init(roomId: Int32, roomName: String, app: AppContainer, onBack: @escaping () -> Void) {
        self.init(
            roomId: roomId,
            roomName: roomName,
            onBack: onBack,
            viewModel: RoomsViewModel(app: app),
            secureWindow: app.secureWindow
        )
    }

    var body: some View {
        ScrollView {
            VStack(spacing: FirepitSpacing.l) {
                ZStack {
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .fill(Color(hex: 0xFFFFFF))
                    if let invite = viewModel.uiState.invite {
                        QrCode(content: invite.payload)
                            .padding(FirepitSpacing.l)
                    } else {
                        ProgressView()
                    }
                }
                .aspectRatio(1, contentMode: .fit)
                .accessibilityLabel(Text("Invite QR code"))

                Label {
                    Text(viewModel.uiState.invite.map { "Refreshes in \($0.secondsRemaining)s" } ?? "Preparing…")
                } icon: {
                    Image(icon: .clock)
                }
                .font(FirepitFont.bodyMedium)
                .foregroundStyle(FirepitColors.textSecondary)

                Text(
                    """
                    Show this to people next to you. It refreshes every few seconds and only works while they are \
                    here. It carries no key: a photograph of it only lets someone ask, and you still decide who comes \
                    in.
                    """
                )
                .font(FirepitFont.bodyMedium)
                .foregroundStyle(FirepitColors.textSecondary)
                .multilineTextAlignment(.center)

                if let fingerprint {
                    Text("Your key: \(fingerprint)")
                        .font(FirepitFont.titleMedium)
                        .multilineTextAlignment(.center)
                        .textSelection(.enabled)
                }

                if let error = viewModel.uiState.error {
                    Text(verbatim: error)
                        .font(FirepitFont.bodySmall)
                        .foregroundStyle(FirepitColors.danger)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(FirepitSpacing.screenMargin)
        }
        .background(FirepitColors.surface)
        .navigationTitle("Invite to \(roomName)")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            fingerprint = viewModel.ownFingerprint()
            viewModel.startInviteRotation(roomId: roomId)
            await viewModel.observe()
        }
        .onDisappear { viewModel.stopInvite() }
        .holdsSecureWindow(secureWindow, as: "invite")
    }
}

/// Camera scan to join a room.
struct JoinRoomScreen: View {
    let onBack: () -> Void

    @State private var viewModel: RoomsViewModel
    private let router: AppRouter?

    init(onBack: @escaping () -> Void, viewModel: RoomsViewModel, router: AppRouter? = nil) {
        self.onBack = onBack
        _viewModel = State(initialValue: viewModel)
        self.router = router
    }

    init(app: AppContainer, onBack: @escaping () -> Void) {
        self.init(onBack: onBack, viewModel: RoomsViewModel(app: app), router: app.router)
    }

    var body: some View {
        ZStack {
            QrScanner(
                onScanned: viewModel.joinFromScan(scanned:),
                enabled: !viewModel.uiState.busy && viewModel.scanned == nil
            )
            ScannerFrame()
            VStack(spacing: FirepitSpacing.s) {
                Spacer()
                // On a solid card rather than straight on the camera: a live picture behind small text makes it
                // unreadable, whatever its colour.
                VStack(spacing: FirepitSpacing.s) {
                    if viewModel.uiState.busy {
                        ProgressView()
                    }
                    if let error = viewModel.uiState.error {
                        Text(verbatim: error)
                            .font(FirepitFont.bodyMedium)
                            .foregroundStyle(FirepitColors.danger)
                    }
                    Text(
                        """
                        A Firepit invite joins a private room. A Meshtastic code adds a channel other Meshtastic apps \
                        can read — the room will say which.
                        """
                    )
                    .font(FirepitFont.bodySmall)
                    .foregroundStyle(FirepitColors.textPrimary)
                }
                .multilineTextAlignment(.center)
                .padding(FirepitSpacing.m)
                .frame(maxWidth: .infinity)
                .background(FirepitColors.surface2, in: .rect(cornerRadius: FirepitSpacing.cardCorner))
            }
            .padding(FirepitSpacing.screenMargin)
        }
        .background(Color.black)
        .navigationTitle("Scan a code")
        .navigationBarTitleDisplayMode(.inline)
        // The title sits on the bar, not on the camera picture, as Android's does.
        .toolbarBackground(FirepitColors.surface, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbar {
            // A link that came as text: one tap, and iOS asks nothing because the person chose to paste.
            ToolbarItem(placement: .topBarTrailing) {
                PasteButton(payloadType: String.self) { strings in
                    guard let link = strings.first?.trimmingCharacters(in: .whitespacesAndNewlines) else { return }
                    viewModel.joinFromScan(scanned: link)
                }
                .labelStyle(.titleOnly)
                .buttonBorderShape(.capsule)
                .tint(FirepitColors.primary)
            }
        }
        .task {
            if let pending = router?.pendingInvite {
                router?.pendingInvite = nil
                viewModel.joinFromScan(scanned: pending)
            }
            await viewModel.observe()
        }
        .onChange(of: viewModel.uiState.joinedRoomName) { _, joined in
            guard joined != nil else { return }
            viewModel.clearMessages()
            onBack()
        }
        .onDisappear { viewModel.cancelScan() }
        .alert(scannedTitle, isPresented: scannedBinding) {
            Button("Ask to join", action: viewModel.confirmScan)
            Button("Cancel", role: .cancel, action: viewModel.cancelScan)
        } message: {
            Text(scannedMessage)
        }
    }

    private var scannedBinding: Binding<Bool> {
        Binding(get: { viewModel.scanned != nil }, set: { if !$0 { viewModel.cancelScan() } })
    }

    private var scannedTitle: String {
        viewModel.scanned.map { "Ask to join \($0.roomName)?" } ?? "Ask to join?"
    }

    private var scannedMessage: String {
        guard let code = viewModel.scanned else { return "" }
        let key = code.fingerprint.map { ", with the key \($0)" } ?? ""
        return "This code says it is from \(code.inviterId)\(key).\n\nTheir screen shows their key under the code. "
            + "Only ask if the two match: a code is anyone's to print."
    }
}

private struct ScannerFrame: View {
    var body: some View {
        // White in both themes: it is drawn on the camera's picture, which has no theme.
        RoundedRectangle(cornerRadius: 28, style: .continuous)
            .strokeBorder(Color.white, lineWidth: 3)
            .frame(width: 250, height: 250)
            .shadow(radius: 8)
            .accessibilityHidden(true)
    }
}
