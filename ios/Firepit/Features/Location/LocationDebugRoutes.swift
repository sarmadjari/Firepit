#if DEBUG
    import SwiftUI

    /// Debug routes for location sharing: `-route location.share`. See App/DebugRoutes.swift.
    enum LocationDebugRoutes {
        @MainActor
        static func view(_ route: String, app: AppContainer) -> AnyView? {
            switch route {
            case "location.share":
                AnyView(ShareSheetPreview(model: SharingViewModel(location: app.location, mesh: app.mesh)))
            default:
                nil
            }
        }
    }

    /// The sheet as a screen would host it: following the view model rather than a copy of its state.
    private struct ShareSheetPreview: View {
        @State var model: SharingViewModel

        var body: some View {
            ShareLocationSheet(
                state: model.state,
                onDismiss: {},
                onShare: { room, choice in model.share(roomId: room, choice: choice) },
                onStop: { model.stop() }
            )
            .id(model.state)
            .task { await model.observe() }
        }
    }
#endif
