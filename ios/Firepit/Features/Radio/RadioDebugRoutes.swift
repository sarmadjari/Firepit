#if DEBUG
    import SwiftUI

    /// Debug routes for this feature: `-route radio.<screen>`. See App/DebugRoutes.swift.
    enum RadioDebugRoutes {
        @MainActor
        static func view(_ route: String, app: AppContainer) -> AnyView? {
            switch route {
            case "radio.devices":
                AnyView(NavigationStack { DevicesScreen(app: app) })
            case "radio.nodes":
                AnyView(NavigationStack { NodesScreen(app: app) })
            default:
                nil
            }
        }
    }
#endif
