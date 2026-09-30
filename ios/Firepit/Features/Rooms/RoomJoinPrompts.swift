import FirepitData
import SwiftUI

extension View {
    /// Shows join approvals over any screen, one at a time, as Android's root-level `RoomJoinPrompts` does.
    func roomJoinPrompts(_ app: AppContainer) -> some View {
        modifier(RoomJoinPromptsModifier(viewModel: RoomsViewModel(app: app)))
    }
}

private struct RoomJoinPromptsModifier: ViewModifier {
    @State var viewModel: RoomsViewModel

    func body(content: Content) -> some View {
        content
            .task { await viewModel.observe() }
            .alert("Let them in?", isPresented: pendingBinding) {
                if let request = currentRequest {
                    Button("Let in") { viewModel.approveJoin(nodeNum: request.nodeNum) }
                    Button("No", role: .cancel) { viewModel.declineJoin(nodeNum: request.nodeNum) }
                }
            } message: {
                Text(pendingMessage)
            }
            .alert(awaitingTitle, isPresented: awaitingBinding) {
                Button(viewModel.awaiting?.declined == true ? "Close" : "Stop waiting") {
                    viewModel.stopWaiting()
                }
            } message: {
                Text(awaitingMessage)
            }
    }

    private var currentRequest: PendingJoin? {
        viewModel.pendingJoins.min { $0.askedAt < $1.askedAt }
    }

    private var pendingBinding: Binding<Bool> {
        Binding(get: { currentRequest != nil }, set: { _ in })
    }

    private var pendingMessage: String {
        guard let request = currentRequest else { return "" }
        let key = request.fingerprint.map { "\n\nTheir key: \($0)" } ?? ""
        return "\(request.nodeId) scanned your code and is asking to join.\(key)\n\nAsk them to read the key on "
            + "their screen, and only say yes if it matches and they are the person in front of you. Letting them "
            + "in hands over the room's key, and nothing can take it back."
    }

    private var awaitingBinding: Binding<Bool> {
        Binding(
            get: { viewModel.awaiting != nil && currentRequest == nil }, set: { if !$0 { viewModel.stopWaiting() } })
    }

    private var awaitingTitle: String {
        guard let room = viewModel.awaiting else { return "Asking to join" }
        return room.declined ? "Not let in" : "Asking to join \(room.roomName)"
    }

    private var awaitingMessage: String {
        guard let room = viewModel.awaiting else { return "" }
        if room.declined {
            return "Whoever showed you the code said no. Nothing was added to your radio."
        }
        let key = room.ownFingerprint.map { "\n\nYour key: \($0) — read it out so they can check it." } ?? ""
        return "Waiting for them to let you in. The code carries no key, so the room only arrives once they say yes."
            + key
    }
}
