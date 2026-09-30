#if DEBUG
    import SwiftUI

    /// Debug routes for this feature: `-route chat.<screen>`. See App/DebugRoutes.swift. Each opens the real pane and
    /// navigates as a tap would, against the demo world's rooms and people.
    enum ChatDebugRoutes {
        @MainActor
        static func view(_ route: String, app: AppContainer) -> AnyView? {
            let start: ChatsDebugStart?
            switch route {
            case "chat.list": start = nil
            case "chat.room": start = .room(1)
            case "chat.crew": start = .room(2)
            case "chat.search": start = .search(1)
            case "chat.direct": start = .direct
            case "chat.info": start = .info(messageId: 107)
            case "chat.reply": start = .reply(messageId: 106)
            case "chat.new": start = .newRoom
            default: return nil
            }
            return AnyView(start.map { ChatsPane(app: app, debugStart: $0) } ?? ChatsPane(app: app))
        }
    }
#endif
