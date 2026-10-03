#if DEBUG
    import FirepitModel
    import SwiftUI

    /// Debug routes for this feature: `-route rooms.<screen>`. See App/DebugRoutes.swift.
    enum RoomsDebugRoutes {
        @MainActor
        static func view(_ route: String, app: AppContainer) -> AnyView? {
            switch route {
            case "rooms.create":
                AnyView(CreateRoomDialog(onDismiss: {}, onCreate: { _ in }, onCreateShared: { _ in }))
            case "rooms.invite":
                AnyView(
                    NavigationStack {
                        // The demo world's own Camp, so the code and the members are real.
                        InviteScreen(roomId: DemoWorld.camp, roomName: "Camp", app: app) {}
                    }
                )
            case "rooms.join":
                AnyView(NavigationStack { JoinRoomScreen(app: app) {} })
            case "rooms.members":
                AnyView(
                    NavigationStack {
                        RoomMembersScreen(
                            roomId: DemoWorld.camp,
                            channelIndex: 1,
                            roomName: "Camp",
                            app: app,
                            onBack: {},
                            kind: .firepit
                        )
                    }
                )
            case "rooms.makePrivate":
                AnyView(MakeRadioPrivateDialog(onMakePrivate: {}, onKeepPublic: {}))
            default:
                nil
            }
        }
    }
#endif
