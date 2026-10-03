#if DEBUG
    import SwiftUI

    /// Debug routes for this feature: `-route settings.<screen>`. See App/DebugRoutes.swift. The page's sections open
    /// the page scrolled to them; the rest push their own screen.
    enum SettingsDebugRoutes {
        @MainActor
        static func view(_ route: String, app: AppContainer) -> AnyView? {
            guard route.hasPrefix("settings.") else { return nil }
            let name = String(route.dropFirst("settings.".count))
            if name == "main" {
                return AnyView(SettingsScreen(app: app))
            }
            if let screen = SettingsSection(rawValue: name) {
                return AnyView(SettingsScreen(app: app, initialRoute: screen))
            }
            if let section = SettingsPageSection(rawValue: name) {
                return AnyView(SettingsScreen(app: app, initialRoute: nil, scrollTo: section))
            }
            return nil
        }
    }
#endif
