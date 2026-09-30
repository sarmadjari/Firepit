#if DEBUG
    import SwiftUI

    /// Debug routes for this feature: `-route settings.<screen>`. See App/DebugRoutes.swift.
    enum SettingsDebugRoutes {
        @MainActor
        static func view(_ route: String, app: AppContainer) -> AnyView? {
            let screen: SettingsRoute?
            switch route {
            case "settings.main": screen = nil
            case "settings.you": screen = .you
            case "settings.radio": screen = .radio
            case "settings.devices": screen = .devices
            case "settings.nodes": screen = .nodes
            case "settings.notifications": screen = .notifications
            case "settings.retention": screen = .retention
            case "settings.privacy": screen = .privacy
            case "settings.location": screen = .location
            case "settings.offlineMaps": screen = .offlineMaps
            case "settings.pins": screen = .pins
            case "settings.appearance": screen = .appearance
            case "settings.about": screen = .about
            default: return nil
            }
            return AnyView(SettingsScreen(app: app, initialRoute: screen))
        }
    }
#endif
