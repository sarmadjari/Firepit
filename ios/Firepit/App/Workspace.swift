import FirepitModel
import Observation
import SwiftUI

/// What a wide window's two sides share, and what has to outlive a change of layout (UX §6.11).
///
/// SwiftUI rebuilds a screen's own `@State` when the screen moves between the tab view and the split, so the models
/// (the map's carries its camera) and the chats stack live here, made once by the shell. Android keeps the same through
/// `movableContentOf` and its view models.
@MainActor
@Observable
final class Workspace {
    let chats: ChatsViewModel
    let rooms: RoomsViewModel
    let map: MapViewModel
    let sharing: SharingViewModel

    /// The chats stack: the open conversation and whatever was pushed over it.
    var chatPath: [ChatRoute] = []

    /// The last room opened, which the list's search returns to, and the list's filter.
    var lastChannel: Int?
    var chatFilter = ChannelFilter.all

    /// The map's open sheets, so a change of layout puts them back (UX §6.11.5).
    var mapSheets = MapSheets()

    init(app: AppContainer) {
        chats = ChatsViewModel(app: app)
        rooms = RoomsViewModel(app: app)
        map = MapViewModel(app: app)
        sharing = SharingViewModel(location: app.location, mesh: app.mesh)
    }

    /// The conversation open on the chat side, which the map beside it follows (UX §6.11.6). Only a Firepit room has
    /// a member list to follow; a Meshtastic channel would leave nobody but you on the map.
    var openConversation: Following? {
        switch chatPath.last(where: \.isConversation) {
        case .channel(let index, _):
            guard let channel = chats.uiState.channels.first(where: { $0.index == index }),
                channel.isRoom, channel.kind.isPrivate
            else { return nil }
            return .room(roomId: channel.id, name: channel.displayName)
        case .direct(let peer):
            return .direct(nodeNum: peer, name: chats.uiState.nameOf(peer))
        default:
            return nil
        }
    }
}

/// Which of the map's sheets are open.
struct MapSheets {
    var pickingRoom = false
    var showingOptions = false
    var droppingAt: DropTarget?
    var openPin: MapPin?
    var openMarker: MapMarker?
    var showingOfflineAreas = false
}

/// Where the map was looking.
struct MapCamera: Equatable {
    let latitude: Double
    let longitude: Double
    let zoom: Double
}
