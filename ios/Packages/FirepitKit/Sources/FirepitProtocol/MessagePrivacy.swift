import FirepitModel
import Foundation

/// The one rule about what may go on the air, kept as data rather than as branches inside the sender.
///
/// Meshtastic's channel encryption cannot make a conversation private. The primary channel ships with a key that is
/// published in the firmware source, a room's key sits on hardware anyone can pick up, and the protocol's own
/// documentation notes that before 2.5 a direct message was "a channel message with a `to` field set — anyone in the
/// channel could read all your direct messages". An app that quietly falls back to the channel key when something
/// better is unavailable rebuilds exactly that.
///
/// So the rule is not "always encrypt", which would make talking to people outside Firepit impossible. It is **never
/// downgrade silently**: a message travels the most private way its conversation allows, and a conversation that cannot
/// be private is one somebody deliberately created and which says so on its face.
public enum Carriage: Hashable, Sendable {
    /// Sealed by us under the room's key and carried on `PRIVATE_APP`. Relays, non-members and the radio's own screen
    /// see an opaque blob.
    case sealedRoom(roomId: Int32, channel: Int)

    /// Sealed by us to the recipient's phone key, then handed to the firmware with their radio key as well. Relays read
    /// nothing, and neither does whoever holds either radio: the radios' own keys open only the outer layer.
    case sealedDirect(nodeNum: Int32)

    /// Handed to the firmware with the recipient's radio key alone, for somebody whose phone key we have never learned
    /// — typically a person not running Firepit. The mesh cannot read it, but a radio gives its private key to any
    /// phone
    /// that connects, so whoever holds either radio can. The conversation says so on its face.
    case toOneNode(nodeNum: Int32)

    /// Ordinary `TEXT_MESSAGE_APP` on an ordinary channel, so a stock Meshtastic client can read it — which is the
    /// entire point, and also the cost. The radio holds the key, so anyone holding the radio has it; on a [key] of
    /// `DEFAULT` the whole mesh has it.
    case openChannel(channel: Int, key: ChannelKey)

    /// No way to carry this at all, so it is not sent.
    case refused(reason: Reason)

    /// True when nobody outside the intended audience can read the words.
    public var isPrivate: Bool {
        switch self {
        case .sealedRoom:
            return true
        case .sealedDirect:
            return true
        case .toOneNode:
            return false
        case .openChannel:
            return false
        case .refused:
            return true
        }
    }

    public enum Reason: String, CaseIterable, Sendable {
        /// Their node has never published a public key we could encrypt to.
        case noPeerKey = "NO_PEER_KEY"

        /// Not a conversation. Slot 0 sets the radio's frequency and carries NodeInfo; Firepit never puts words on it,
        /// so that every conversation is one somebody deliberately made.
        case notARoom = "NOT_A_ROOM"

        /// A channel with no key at all. Firepit has no reason to send in the clear.
        case notEncrypted = "NOT_ENCRYPTED"

        /// A Firepit room whose key is not on this phone. Words sent here could only travel under the channel key,
        /// which
        /// every member's radio holds.
        case roomKeyMissing = "ROOM_KEY_MISSING"

        /// A Firepit room that moved to a new key which never reached us. The old key is what a removed member still
        /// holds, so words sealed under it would reach them and nobody else.
        case roomMovedOn = "ROOM_MOVED_ON"
    }
}

public enum MessagePrivacy {
    /// How a message to [to] on [channel] may travel, or why it may not.
    ///
    /// [sealingRoomId] is the room this channel carries **and** whose Firepit key we hold — null when either is
    /// missing,
    /// since a room we cannot seal for is not one we can be private in. [isRoomSlot] separates a conversation from slot
    /// 0. [channelKey] is how private the radio's own encryption on this channel is, which is all an interoperable
    /// channel has. [hasPeerKey] is their radio key, without which the firmware will not encrypt to them;
    /// [hasPeerPhoneKey] is their phone key, which is what makes a direct message private from the radios too.
    /// [roomKind] names a Firepit room this phone may not send in.
    public static func carriageFor(
        to: Int32,
        channel: Int,
        isRoomSlot: Bool,
        sealingRoomId: Int32?,
        hasPeerKey: Bool,
        channelKey: ChannelKey,
        hasPeerPhoneKey: Bool = false,
        roomKind: RoomKind? = nil
    ) -> Carriage {
        if to != broadcastNodeNum {
            if !hasPeerKey { return .refused(reason: .noPeerKey) }
            if hasPeerPhoneKey { return .sealedDirect(nodeNum: to) }
            return .toOneNode(nodeNum: to)
        }

        if !isRoomSlot { return .refused(reason: .notARoom) }

        // Still a Firepit room on the radio, but not one this phone can seal for. Falling back to the channel key here
        // is exactly the downgrade the rest of this file exists to prevent.
        if roomKind == .firepitKeyMissing { return .refused(reason: .roomKeyMissing) }
        if roomKind == .firepitMovedOn { return .refused(reason: .roomMovedOn) }

        // A Firepit room, sealed under a key the radio never holds. Always preferred where it exists.
        if let sealingRoomId { return .sealedRoom(roomId: sealingRoomId, channel: channel) }

        // An interoperable channel: readable by other Meshtastic clients, which is the only reason somebody would make
        // one.
        if channelKey.isPrivate || channelKey == .default {
            return .openChannel(channel: channel, key: channelKey)
        }

        return .refused(reason: .notEncrypted)
    }

    /// What the composer may type, given how the message will travel.
    public static func textBudgetFor(carriage: Carriage) -> Int {
        switch carriage {
        case .sealedRoom:
            return MeshConstants.maxTextBytes - sealedOverhead
        case .sealedDirect:
            return maxDirectSealedTextBytes
        case .toOneNode:
            return MeshConstants.maxTextBytes
        case .openChannel:
            return MeshConstants.maxTextBytes
        case .refused:
            return 0
        }
    }

    /// Sealing costs a version byte, a nonce and a tag. Stated here rather than imported so `FirepitProtocol` keeps no
    /// dependency on `FirepitCrypto`; SealedTextTest asserts the two agree.
    public static let sealedOverhead = 1 + 12 + 16

    /// A sealed direct message costs the same version byte, nonce and tag. DirectSealTest checks it.
    public static let directSealedOverhead = 1 + 12 + 16

    /// What a sealed direct message leaves for words. It rides inside PKI, which takes 12 bytes of the payload, and the
    /// envelope around the words takes the rest. ProtocolContractTest encodes the largest one to prove it fits.
    public static let maxDirectSealedTextBytes = 170
}
