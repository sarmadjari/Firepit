import FirepitModel
import SwiftUI

/// Top-level shell. Ported from android/app/…/ui/FirepitApp.kt.
///
/// Lays the window out by the one rule both apps share (`PaneLayouts`, UX §6.11): the phone app in a narrow window,
/// and the chat side beside the map side in a wide one. The models, the chats stack and the map's camera live in
/// `Workspace`, so moving between the two keeps what the user had open.
///
/// In one pane: three tabs, each its own navigation stack. Android hides its bar while a chat or a settings page is
/// open so reading gets the whole screen; on iOS the pushed screens hide the tab bar themselves
/// (`.toolbar(.hidden, for: .tabBar)`), and the map keeps it — it is a place you pass through, not one you read.
struct AppShell: View {
    let app: AppContainer

    @State private var workspace: Workspace
    @State private var settingsOpen = false
    @State private var lastUsed = Side.chat
    @State private var wasSplit = false
    /// A side being closed: the panes move first, then one pane shows (UX §6.11.11).
    @State private var closing: Side?
    /// Where the chat side starts when two panes open again from one.
    @State private var enterFrom: Double?
    private var preferences = LayoutPreferences()
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.layoutDirection) private var direction

    init(app: AppContainer) {
        self.app = app
        _workspace = State(initialValue: Workspace(app: app))
    }

    var body: some View {
        GeometryReader { proxy in
            // The whole window: what the safe area takes is still the window's.
            let insets = proxy.safeAreaInsets
            shell(
                window: WindowShape(
                    width: proxy.size.width + insets.leading + insets.trailing,
                    height: proxy.size.height + insets.top + insets.bottom
                )
            )
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

    private func shell(window: WindowShape) -> some View {
        let choice = preferences.choice
        let chatMin = PaneLayouts.chatMinFor(typeSize.textScale)
        let layout = PaneLayouts.layoutFor(window, choice, chatMin: chatMin)
        let controls = wideScreenControls(window: window, choice: choice, layout: layout, chatMin: chatMin)
        return panes(layout: layout, window: window, chatMin: chatMin)
            // Before the environment, so Settings sees the wide-screen choices too.
            .sheet(isPresented: $settingsOpen) {
                SettingsScreen(app: app, onClose: { settingsOpen = false })
            }
            .environment(\.wideScreen, controls.canSplit ? controls : nil)
            .onChange(of: layout.isSplit, initial: true) { _, isSplit in foldOrUnfold(isSplit) }
            // The map follows the open conversation while both are on screen (UX §6.11.6).
            .onChange(of: following(split: layout.isSplit), initial: true) { _, target in
                workspace.map.follow(target)
            }
            .onChange(of: workspace.chatPath) { lastUsed = .chat }
            .background {
                TouchDownReader { point in
                    if let side = side(at: point, layout: layout, window: window) { lastUsed = side }
                }
            }
    }

    @ViewBuilder
    private func panes(layout: PaneLayout, window: WindowShape, chatMin: Double) -> some View {
        switch layout {
        case .onePane:
            tabs
        case .sideBySide(let sides):
            SidePanes(
                layout: sides,
                window: window,
                chatMin: chatMin,
                enterFrom: enterFrom,
                closing: closing,
                onClosed: {
                    let closed = closing
                    closing = nil
                    arrange(closed == .map ? .chatOnly : .mapOnly)
                },
                onSettle: { settle($0, layout: layout, window: window) },
                onReset: { preferences.resetDivider() },
                onSwapSides: { preferences.swapSides() },
                chat: { chatSide.environment(\.listBesideConversation, sides.listBesideConversation) },
                map: { mapSide }
            )
        case .stacked(let mapHeight, _, let gap):
            StackedPanes(mapHeight: mapHeight, gap: gap, chat: { chatSide }, map: { mapSide })
        }
    }

    private func wideScreenControls(
        window: WindowShape, choice: LayoutChoice, layout: PaneLayout, chatMin: Double
    ) -> WideScreenControls {
        let swap: (() -> Void)? = layout.isSideBySide ? { preferences.swapSides() } : nil
        let settings: (() -> Void)? = layout.isSplit ? { settingsOpen = true } : nil
        return WideScreenControls(
            canSplit: Self.canSplit(window, choice, chatMin: chatMin),
            arrangement: choice.arrangement,
            onArrange: { request($0, layout: layout, window: window) },
            onSwapSides: swap,
            onOpenSettings: settings
        )
    }

    private func following(split: Bool) -> Following? {
        split ? workspace.openConversation : nil
    }

    private var tabs: some View {
        @Bindable var router = app.router
        return TabView(selection: $router.selectedTab) {
            ChatsPane(app: app, workspace: workspace)
                .tabItem {
                    Label {
                        Text(TopLevelDestination.chats.label)
                    } icon: {
                        Image(icon: .chats)
                    }
                }
                .tag(TopLevelDestination.chats)
            MapScreen(app: app, workspace: workspace)
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
    }

    private var chatSide: some View {
        ChatsPane(app: app, workspace: workspace)
            .environment(\.besideMap, true)
    }

    private var mapSide: some View {
        MapScreen(app: app, workspace: workspace)
    }

    /// Which side a touch at `point`, in window coordinates, landed on; nil in one pane.
    private func side(at point: CGPoint, layout: PaneLayout, window: WindowShape) -> Side? {
        switch layout {
        case .onePane:
            return nil
        case .sideBySide(let sides):
            let fromStart = direction == .rightToLeft ? window.width - point.x : point.x
            let chatFirst = sides.mapSide == .end
            let inFirst = fromStart < (chatFirst ? sides.chatWidth : sides.mapWidth)
            return inFirst == chatFirst ? .chat : .map
        case .stacked(let mapHeight, _, _):
            return point.y < mapHeight ? .map : .chat
        }
    }

    private func arrange(_ next: PaneArrangement) {
        preferences.setArrangement(next)
        switch next {
        case .chatOnly: app.router.selectedTab = .chats
        case .mapOnly: app.router.selectedTab = .map
        case .chatAndMap: break
        }
    }

    /// What the layout menu and a divider dragged closed ask for. Side by side, closing a side moves the panes first;
    /// opening again from one side starts the split where that side is.
    private func request(_ next: PaneArrangement, layout: PaneLayout, window: WindowShape) {
        let current = preferences.choice.arrangement
        if layout.isSideBySide && next != .chatAndMap {
            closing = next == .chatOnly ? .map : .chat
        } else if next == .chatAndMap && current != .chatAndMap {
            enterFrom = current == .chatOnly ? window.width : 0
            arrange(next)
        } else {
            arrange(next)
        }
    }

    private func settle(_ settle: DividerSettle, layout: PaneLayout, window: WindowShape) {
        switch settle {
        case .share(let share): preferences.setShare(upright: window.isUpright, share)
        case .closeChat: request(.mapOnly, layout: layout, window: window)
        case .closeMap: request(.chatOnly, layout: layout, window: window)
        }
    }

    /// Unfolding keeps Settings open over the two sides; folding shows one pane, chosen in this order: Settings if it
    /// was open, the arrangement the user picked, then the side used last (UX §6.11.5).
    private func foldOrUnfold(_ isSplit: Bool) {
        // The split that opened has taken its starting width; a later one starts settled. A close the window outran
        // (a fold mid-animation) must not carry over to the next split.
        Task { @MainActor in enterFrom = nil }
        if !isSplit { closing = nil }
        if isSplit {
            wasSplit = true
            if app.router.selectedTab == .settings { settingsOpen = true }
        } else if wasSplit {
            wasSplit = false
            let arrangement = preferences.choice.arrangement
            app.router.selectedTab =
                if settingsOpen { .settings }
                else if arrangement == .mapOnly { .map }
                else if arrangement == .chatOnly { .chats }
                else if lastUsed == .map { .map }
                else { .chats }
            settingsOpen = false
        }
    }

    private static func canSplit(_ window: WindowShape, _ choice: LayoutChoice, chatMin: Double) -> Bool {
        var both = choice
        both.arrangement = .chatAndMap
        return PaneLayouts.layoutFor(window, both, chatMin: chatMin).isSplit
    }
}

extension PaneLayout {
    /// Two sides on screen, side by side or stacked.
    fileprivate var isSplit: Bool {
        if case .onePane = self { return false }
        return true
    }

    fileprivate var isSideBySide: Bool {
        if case .sideBySide = self { return true }
        return false
    }
}
