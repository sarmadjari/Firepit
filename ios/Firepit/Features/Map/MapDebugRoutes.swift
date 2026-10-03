#if DEBUG
    import SwiftUI

    /// Debug routes for this feature: `-route map.<screen>`. See App/DebugRoutes.swift.
    enum MapDebugRoutes {
        @MainActor
        static func view(_ route: String, app: AppContainer) -> AnyView? {
            switch route {
            case "map.main":
                AnyView(MapScreen(app: app))
            case "map.everyone":
                AnyView(MapScreen(app: app, debugFramesEveryone: true))
            case "map.pins":
                AnyView(NavigationStack { PinsScreen(app: app) })
            default:
                nil
            }
        }
    }
#endif
