import SwiftUI

/// Top-level shell. Ported from android/app/…/ui/FirepitApp.kt.
///
/// Three tabs, each its own navigation stack. Android hides its bar while a chat or a settings page is open so reading
/// gets the whole screen; on iOS the pushed screens hide the tab bar themselves (`.toolbar(.hidden, for: .tabBar)`),
/// and the map keeps it — it is a place you pass through, not one you read.
struct AppShell: View {
    let app: AppContainer

    var body: some View {
        @Bindable var router = app.router
        TabView(selection: $router.selectedTab) {
            ChatsPane(app: app)
                .tabItem {
                    Label {
                        Text(TopLevelDestination.chats.label)
                    } icon: {
                        Image(icon: .chats)
                    }
                }
                .tag(TopLevelDestination.chats)
            MapScreen(app: app)
                .tabItem {
                    Label {
                        Text(TopLevelDestination.map.label)
                    } icon: {
                        Image(icon: .map)
                    }
                }
                .tag(TopLevelDestination.map)
            SettingsScreen(app: app)
                .tabItem {
                    Label {
                        Text(TopLevelDestination.settings.label)
                    } icon: {
                        Image(icon: .settings)
                    }
                }
                .tag(TopLevelDestination.settings)
        }
        #if DEBUG
            .overlay {
                if let route = DebugRoutes.requested {
                    DebugRoutes.view(for: route, app: app)
                    .background(FirepitColors.surface)
                }
            }
        #endif
    }
}
