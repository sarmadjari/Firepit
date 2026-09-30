#if DEBUG
    import SwiftUI

    /// Debug builds only: launching with `-route <feature>.<screen>` opens that screen over the app, so a screen can be
    /// checked (and screenshotted) without a radio or a tap path. Each feature answers for its own routes in its own
    /// folder, e.g. `RadioDebugRoutes`.
    enum DebugRoutes {
        static var requested: String? {
            let arguments = ProcessInfo.processInfo.arguments
            guard let index = arguments.firstIndex(of: "-route"), arguments.indices.contains(index + 1) else {
                return nil
            }
            return arguments[index + 1]
        }

        @MainActor @ViewBuilder
        static func view(for route: String, app: AppContainer) -> some View {
            if let view = ChatDebugRoutes.view(route, app: app)
                ?? MapDebugRoutes.view(route, app: app)
                ?? RadioDebugRoutes.view(route, app: app)
                ?? RoomsDebugRoutes.view(route, app: app)
                ?? SettingsDebugRoutes.view(route, app: app)
                ?? LocationDebugRoutes.view(route, app: app)
            {
                view
            } else {
                ContentUnavailableView("No route \(route)", systemImage: "questionmark")
            }
        }
    }
#endif
