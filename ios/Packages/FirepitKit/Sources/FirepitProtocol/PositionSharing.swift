import FirepitModel
import Foundation

/// Where a position may go, and the one thing the radio must never do.
///
/// Every position Firepit shares is sealed by the phone under a room's own key, so the radios carrying it read nothing.
/// The firmware's own broadcast would undo that: it goes out under the channel key, which anyone holding a member's
/// radio has, and it carries on with the phone switched off or out of range, so a share the phone ended would not end.
/// Precision therefore stays 0 on every channel, room and primary alike.
///
/// The firmware only broadcasts on a channel whose `position_precision` is non-zero (`sendOurPosition` walks slots 0..7
/// for the first), and precision 0 also makes it refuse to answer a position request, so zero everywhere means the
/// radio never speaks for us about where we are. That is re-asserted on every connection rather than trusted, because
/// another app, another phone or a factory reset can change it between sessions.
public enum PositionSharing {
    /// True when a room may receive our sealed position at all.
    ///
    /// Only a Firepit room whose key we hold: its key never reaches a radio, so the audience is the people holding the
    /// phones we invited. A shared Meshtastic channel reaches people the group never chose, and a room we cannot seal
    /// for has no private way to carry it.
    public static func canShare(channel: RoomChannel) -> Bool {
        channel.isRoom && channel.kind == .firepit
    }

    /// Channels on which the radio itself would broadcast our position.
    public static func broadcastingChannels(channels: [RoomChannel]) -> [RoomChannel] {
        channels.filter { $0.positionPrecision > PositionPrecision.disabled }
    }

    /// True when the radio broadcasts our position nowhere.
    public static func isSilent(channels: [RoomChannel]) -> Bool {
        broadcastingChannels(channels: channels).isEmpty
    }

    /// The writes that stop the radio broadcasting anywhere. Empty when it already agrees, so a reconnect check is
    /// silent in the normal case — and also when the channels are not known yet, which callers must rule out before
    /// reading anything into an empty answer.
    public static func writesToSilence(channels: [RoomChannel]) -> [PrecisionWrite] {
        broadcastingChannels(channels: channels).map {
            PrecisionWrite(index: $0.index, precision: PositionPrecision.disabled)
        }
    }
}

public struct PrecisionWrite: Hashable, Sendable {
    public var index: Int
    public var precision: Int

    public init(index: Int, precision: Int) {
        self.index = index
        self.precision = precision
    }
}
