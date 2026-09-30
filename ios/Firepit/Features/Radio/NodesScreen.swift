import FirepitModel
import FirepitProtocol
import SwiftUI

/// Everyone the mesh can currently see, and what is known about each of them.
struct NodesScreen: View {
    @State private var viewModel: RadioViewModel
    @State private var expanded: Int32?
    @State private var actions: MeshNode?

    init(app: AppContainer) {
        _viewModel = State(initialValue: RadioViewModel(app: app))
    }

    init(viewModel: RadioViewModel) {
        _viewModel = State(initialValue: viewModel)
    }

    var body: some View {
        let state = viewModel.uiState
        List {
            if state.nodes.isEmpty {
                Text(
                    state.details == nil ? "Connect a device to see the mesh around it." : "Nobody has been heard yet."
                )
                .font(FirepitFont.bodyMedium)
                .foregroundStyle(FirepitColors.textSecondary)
                .listRowBackground(FirepitColors.surface2)
            }
            ForEach(state.nodes) { node in
                NodeRow(
                    node: node,
                    isSelf: node.nodeNum == state.myNodeNum,
                    tracing: state.tracing == node.nodeNum,
                    enabled: state.tracing == nil,
                    expanded: expanded == node.nodeNum,
                    onToggle: { expanded = expanded == node.nodeNum ? nil : node.nodeNum },
                    onActions: { actions = node },
                    onCheckPath: { viewModel.checkPath(node) }
                )
            }
        }
        .navigationTitle("Nodes")
        .navigationBarTitleDisplayMode(.inline)
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(FirepitColors.surface)
        .task { await viewModel.observe() }
        .alert(
            "Path check",
            isPresented: Binding(get: { state.traceResult != nil }, set: { if !$0 { viewModel.clearTrace() } })
        ) {
            Button("Close") { viewModel.clearTrace() }
        } message: {
            Text(traceMessage(state.traceResult))
        }
        .confirmationDialog(
            actions?.displayName ?? "Node",
            isPresented: Binding(get: { actions != nil }, set: { if !$0 { actions = nil } }),
            titleVisibility: .visible,
            presenting: actions
        ) { node in
            Button("Message") {}
            Button("Locate") {}
            Button("Path") { viewModel.checkPath(node) }
            Button("Request position") {}
            Button("Buzz") { buzzNode(node, state: state) }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("Some actions need the chat and map screens that own those routes. Path check works here.")
        }
    }

    private func buzzNode(_ node: MeshNode, state: RadioUiState) {
        guard let saved = state.saved.first(where: { $0.nodeNum == node.nodeNum }) else {
            viewModel.say("Connect \(node.displayName) once so Firepit learns which radio it is.")
            return
        }
        viewModel.buzz(saved)
    }

    private func traceMessage(_ result: String?) -> String {
        """
        \(result ?? "")

        A path is not a delivery receipt. It shows the mesh could reach them just now, not that anyone read anything.
        """
    }
}

private struct NodeRow: View {
    let node: MeshNode
    let isSelf: Bool
    let tracing: Bool
    let enabled: Bool
    let expanded: Bool
    let onToggle: () -> Void
    let onActions: () -> Void
    let onCheckPath: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: FirepitSpacing.s) {
            Button(action: onToggle) {
                HStack(spacing: FirepitSpacing.m) {
                    avatar
                    VStack(alignment: .leading, spacing: FirepitSpacing.xs) {
                        Text(verbatim: isSelf ? "\(node.displayName) (this device)" : node.displayName)
                            .font(FirepitFont.titleMedium)
                            .foregroundStyle(FirepitColors.textPrimary)
                            .lineLimit(1)
                        Text(verbatim: nodeDetail(node))
                            .font(FirepitFont.bodySmall)
                            .foregroundStyle(FirepitColors.textSecondary)
                            .lineLimit(2)
                    }
                    Spacer(minLength: FirepitSpacing.s)
                    if !isSelf {
                        Button(tracing ? "…" : "Path", action: onCheckPath)
                            .buttonStyle(.bordered)
                            .disabled(!enabled)
                    }
                    Button(action: onActions) {
                        Image(icon: .more)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Actions for \(node.displayName)"))
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            if expanded {
                VStack(spacing: FirepitSpacing.xs) {
                    ForEach(nodeFacts(node), id: \.0) { label, value in
                        Field(label, value, monospace: Self.monospace.contains(label))
                    }
                }
                .padding(.bottom, FirepitSpacing.s)
            }
        }
        .padding(.vertical, FirepitSpacing.s)
        .listRowBackground(FirepitColors.surface2)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var avatar: some View {
        if node.role?.uppercased().contains("ROUTER") == true {
            InfraAvatar(isRouter: true, size: 36)
        } else if node.role?.uppercased().contains("BASE") == true {
            InfraAvatar(isRouter: false, size: 36)
        } else {
            IdentityAvatar(nodeNum: node.nodeNum, tag: node.shortName, size: 36)
        }
    }

    private static let monospace = Set(["Node ID", "Key fingerprint"])
}

/// Only what the node actually reported: a blank row teaches nothing.
func nodeFacts(_ node: MeshNode) -> [(String, String)] {
    var facts: [(String, String)] = []
    facts.append(("Node ID", MeshConstants.formatNodeId(node.nodeNum)))
    if let hardware = node.hwModel { facts.append(("Hardware", prettyName(hardware))) }
    if let role = node.role { facts.append(("Device role", prettyName(role))) }
    facts.append(("Hops away", node.hopsAway.map { $0 == 0 ? "Direct" : "\($0)" } ?? "Unknown"))
    if let snr = node.snr { facts.append(("Signal to noise", String(format: "%.1f dB", snr))) }
    if let rssi = node.rssi, rssi != 0 { facts.append(("Signal strength", "\(rssi) dBm")) }
    if let battery = node.batteryLevel {
        facts.append(("Battery", battery > 100 ? "Powered from mains" : "\(battery)%"))
    }
    if let voltage = node.voltage, voltage > 0 { facts.append(("Voltage", String(format: "%.2f V", voltage))) }
    if let busy = node.channelUtilization { facts.append(("Channel busy", String(format: "%.1f%%", busy))) }
    if let air = node.airUtilTx { facts.append(("Air time sending", String(format: "%.1f%%", air))) }
    if node.hasPosition, let latitude = node.latitude, let longitude = node.longitude {
        facts.append(("Position", String(format: "%.5f, %.5f", latitude, longitude)))
        if let altitude = node.altitude { facts.append(("Altitude", "\(altitude) m")) }
        if let speed = node.groundSpeed, speed > 0 { facts.append(("Speed", "\(speed) km/h")) }
        if let track = node.groundTrack { facts.append(("Heading", "\(track)°")) }
    }
    facts.append(("Encryption key", node.publicKey?.isEmpty == false ? "Shared" : "Not shared"))
    if let fingerprint = KeyFingerprint.of(publicKeyBase64: node.publicKey) {
        facts.append(("Key fingerprint", fingerprint))
    }
    if node.isUnmessagable { facts.append(("Messages", "Does not accept them")) }
    facts.append(("Last heard", node.lastHeard.map { shortAge($0) } ?? "Not heard yet"))
    return facts
}
