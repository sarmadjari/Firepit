import FirepitData
import FirepitModel
import FirepitProtocol
import SwiftUI

@main
struct FirepitApp: App {
    @State private var container: Result<AppContainer, any Error> = Result {
        try AppContainer(configuration: Self.configuration)
    }

    private static var configuration: AppContainer.Configuration {
        #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("-demo") {
                return .demo
            }
        #endif
        return .live
    }

    var body: some Scene {
        WindowGroup {
            switch container {
            case .success(let app):
                AppRoot(app: app)
            case .failure:
                StorageUnavailable()
            }
        }
    }
}

/// The root of every screen. Ported from android/app/…/MainActivity.kt: theme, the per-person colour and mark
/// overrides every avatar reads, the screen-privacy cover, and the prompts that can appear over any screen.
struct AppRoot: View {
    let app: AppContainer

    @Environment(\.scenePhase) private var scenePhase
    @State private var cards: [Int32: PersonCard] = [:]
    @State private var myNodeNum: Int32?
    @State private var reconnected = false

    var body: some View {
        AppShell(app: app)
            .environment(app)
            .environment(\.identitySlots, identitySlots)
            .environment(\.identityMarks, identityMarks)
            .tint(FirepitColors.primary)
            .preferredColorScheme(colorScheme)
            .clockOfferAlert(app.nodeClock)
            .roomJoinPrompts(app)
            .secureWindowCover(app.secureWindow)
            .onOpenURL { url in
                app.router.openInvite(url.absoluteString)
            }
            .task {
                app.start()
            }
            .task {
                for await next in app.rooms.observePersonCards() {
                    cards = next
                }
            }
            .task {
                for await next in app.mesh.myNodeNum.subscribe() {
                    myNodeNum = next
                }
            }
            .onChange(of: app.screenPrivacy.allowCapture, initial: true) { _, allow in
                if allow {
                    app.secureWindow.release(AppContainer.screenSetting)
                } else {
                    app.secureWindow.hold(AppContainer.screenSetting)
                }
            }
            .onChange(of: scenePhase, initial: true) { _, phase in
                app.chatPresence.setForeground(phase == .active)
                // Once, the first time somebody is actually looking: the reconnect then keeps waiting for the radio
                // in the background.
                if phase == .active && !reconnected {
                    reconnected = true
                    app.session.reconnectLastRadio()
                }
            }
    }

    private var colorScheme: ColorScheme? {
        switch app.themePreferences.choice {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }

    /// Your colour follows you onto whichever radio you are holding, rather than belonging to the radio.
    private var identitySlots: [Int32: Int] {
        var slots: [Int32: Int] = [:]
        for (node, card) in cards {
            if let slot = card.colourSlot {
                slots[node] = slot
            }
        }
        if let myNodeNum, let chosen = app.people.person?.colourSlot {
            slots[myNodeNum] = chosen
        }
        return slots
    }

    /// Decided in one place so every avatar in the app agrees: people wear the initials they chose, and hardware
    /// wears its role rather than a short name nobody chose to read.
    private var identityMarks: [Int32: IdentityMark] {
        var marks: [Int32: IdentityMark] = [:]
        // Hardware first, so a card takes it back: a role is only how this phone filed a device when it paired, while
        // a card is somebody saying they are holding it.
        for radio in app.savedRadios.radios {
            let icon: FirepitIcon? =
                switch radio.role {
                case .base: .roleBase
                case .router: .roleRouter
                case .personal: nil
                }
            if let icon, let node = radio.nodeNum {
                marks[node] = IdentityMark(icon: icon)
            }
        }
        for (node, card) in cards where !card.tag.trimmingCharacters(in: .whitespaces).isEmpty {
            marks[node] = IdentityMark(tag: card.tag)
        }
        if let myNodeNum, let tag = app.people.person?.tag, !tag.trimmingCharacters(in: .whitespaces).isEmpty {
            marks[myNodeNum] = IdentityMark(tag: tag)
        }
        return marks
    }
}

/// Shown when the app's storage cannot be opened — something the phone would have to be badly wrong for.
private struct StorageUnavailable: View {
    var body: some View {
        ContentUnavailableView {
            Label {
                Text("Firepit can't open its storage")
            } icon: {
                FirepitMark()
                    .fill(FirepitColors.primary)
                    .frame(width: 56, height: 56)
            }
        } description: {
            Text("Restart the phone and open Firepit again. Your messages have not been sent anywhere.")
        }
    }
}
