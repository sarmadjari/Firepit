import FirepitProtocol
import Foundation

/**
 * What a channel slot means to Firepit.
 *
 * Several repositories need these, and a mirrored copy in each is one edit away
 * from the two disagreeing about what a slot is.
 */

/// The room a slot carries, or nil when the slot is not a Firepit room.
internal func roomIdForChannel(_ repository: MeshRepository, channel: Int) -> Int32? {
    repository.roomIdForChannel(channel)
}

/// True when this slot holds a conversation at all.
///
/// Judged by the slot and its role, never by the room id: a channel somebody
/// made in the official Meshtastic app is a perfectly good conversation and
/// often carries no id of its own.
internal func isRoomSlot(_ repository: MeshRepository, channel: Int) -> Bool {
    repository.isRoomSlot(channel)
}

/// How private the radio's own encryption on this slot is.
///
/// This is all an interoperable channel has — the key lives on the radio, so it
/// protects against the mesh at large and against nobody holding the hardware.
internal func channelKeyOf(_ repository: MeshRepository, channel: Int) -> ChannelKey {
    repository.channelKeyOf(channel)
}
