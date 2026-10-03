import Foundation
import Observation

/// The three tabs, in the order the bar shows them. Ported from android/app/…/ui/TopLevelDestination.kt.
enum TopLevelDestination: String, CaseIterable, Hashable {
    case chats
    case map
    case settings

    var label: LocalizedStringResource {
        switch self {
        case .chats: "Chats"
        case .map: "Map"
        case .settings: "Settings"
        }
    }

    var icon: FirepitIcon {
        switch self {
        case .chats: .chats
        case .map: .map
        case .settings: .settings
        }
    }
}

/// Where the app is asked to go from outside a screen: a tapped notification or an opened invite link.
///
/// Android carries these as intent extras into the activity; here the requester sets a value and the screen that
/// owns the destination consumes it.
@Observable
final class AppRouter {
    var selectedTab: TopLevelDestination = .chats

    /// A conversation to open, by channel, set by a tapped notification. The chats screen clears it once shown.
    var pendingChannel: Int?

    /// A conversation with one person to open: a tapped direct-message notification, or Message on someone.
    var pendingDirect: Int32?

    /// An invite link opened from outside the app, handled like a scanned code.
    var pendingInvite: String?

    func open(channel: Int) {
        selectedTab = .chats
        pendingChannel = channel
    }

    func openDirect(_ peer: Int32) {
        selectedTab = .chats
        pendingDirect = peer
    }

    func openInvite(_ link: String) {
        selectedTab = .chats
        pendingInvite = link
    }
}
