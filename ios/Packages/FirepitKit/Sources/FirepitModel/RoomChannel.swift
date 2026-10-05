import Foundation

/// What kind of conversation a channel slot holds, and therefore who can read it. Shown on the room itself, because
/// the difference is the whole point.
///
/// The labels name the protocol rather than describing the privacy. A word like "shared" reads as reassuring when it
/// should read as a warning, whereas "Meshtastic" states the one fact that predicts everything else: this is not a
/// Firepit room, so other apps can read it and Firepit's own features are off.
public enum RoomKind: String, CaseIterable, Sendable {
    /// Sealed under a key that only ever sits on members' phones. Other Meshtastic clients see an unreadable blob and
    /// cannot join in.
    case firepit = "FIREPIT"
    /// An ordinary Meshtastic channel with a real key. Anyone running any Meshtastic client can take part if they have
    /// the key — and so can anyone who picks up one of their radios.
    case meshtasticPrivate = "MESHTASTIC_PRIVATE"
    /// The open mesh, on the key published in the Meshtastic source. Useful, and not private in any sense.
    case meshtasticPublic = "MESHTASTIC_PUBLIC"
    /// No encryption at all. Firepit will not send here.
    case unencrypted = "UNENCRYPTED"
    /// A Firepit room whose key is not on this phone — after a reinstall, a new phone or a wiped keychain. The radio
    /// still carries it, but nothing sent from here could be sealed, so nothing is sent at all.
    case firepitKeyMissing = "FIREPIT_KEY_MISSING"
    /// A Firepit room that moved to a new key which never reached this phone. The only other people still holding the
    /// old key are whoever was removed, so nothing more is sent under it.
    case firepitMovedOn = "FIREPIT_MOVED_ON"

    /// The badge, shown wherever the room is named.
    public var label: String {
        switch self {
        case .firepit: "Firepit"
        case .meshtasticPrivate: "Meshtastic"
        case .meshtasticPublic: "Meshtastic · public"
        case .unencrypted: "No key"
        case .firepitKeyMissing: "Firepit · no key"
        case .firepitMovedOn: "Firepit · new key"
        }
    }

    /// Who can read it, in one line, for sitting under the room name.
    public var readableBy: String {
        switch self {
        case .firepit: "Only the people you invited"
        case .meshtasticPrivate: "Anyone who has this channel's key"
        case .meshtasticPublic: "Every Meshtastic radio in range"
        case .unencrypted: "Everyone, in the clear"
        case .firepitKeyMissing: "Not this phone: its key is missing"
        case .firepitMovedOn: "Members who received the new key"
        }
    }

    /// The whole story, for when there is room to tell it.
    public var summary: String {
        switch self {
        case .firepit:
            """
            Firepit's own kind of room. Messages are sealed on your phone before the radio ever sees them, so nobody \
            on the Meshtastic network can read this — not even someone holding one of the radios. Names, colours, and \
            delivery and read status all work here. Invite people with a Firepit QR code.
            """
        case .meshtasticPrivate:
            """
            A standard Meshtastic channel, so people using the ordinary Meshtastic app can take part. Its key is kept \
            on the radios, which means anyone holding one can read everything sent here. Firepit's own features — read \
            status, profile names and colours — are switched off, because other apps would not understand them.
            """
        case .meshtasticPublic:
            "The open Meshtastic network, on the key published in Meshtastic's own source code. Every radio in range "
                + "can read this. Useful for reaching strangers, never for anything you would not say out loud."
        case .unencrypted:
            "This channel has no encryption key at all, so anything sent on it travels in the clear. Firepit will not "
                + "send messages here."
        case .firepitKeyMissing:
            """
            A Firepit room whose key isn't on this phone, so nothing sent from here could be sealed and Firepit won't \
            send in it. Anything typed here would be readable by whoever holds a member's radio. Leave the room, or \
            ask a member to invite you again.
            """
        case .firepitMovedOn:
            """
            This room moved to a new key that didn't reach this phone. Anything sent under the old key would only \
            reach people no longer in the room, so Firepit won't send here. Ask a member to invite you again.
            """
        }
    }

    /// True when this room is unreadable to everyone outside it.
    public var isPrivate: Bool { self == .firepit }

    /// True when other Meshtastic clients can take part.
    public var isInteroperable: Bool { self == .meshtasticPrivate || self == .meshtasticPublic }

    /// A Firepit room this phone may not send in, although the radio still carries it.
    public var isStalledRoom: Bool { self == .firepitKeyMissing || self == .firepitMovedOn }
}

public enum ChannelRole: String, CaseIterable, Sendable {
    case disabled = "DISABLED"
    case primary = "PRIMARY"
    case secondary = "SECONDARY"
}

/// A channel slot on the radio. Slot 0 is the primary; 1-7 are rooms.
///
/// `id` is Firepit's stable room identifier and survives renames and re-indexing, so it is what the app keys rooms by —
/// never the slot index.
public struct RoomChannel: Hashable, Sendable {
    public var index: Int
    public var name: String
    public var role: ChannelRole
    public var id: Int32
    /// 0 means positions are never sent on this channel.
    public var positionPrecision: Int
    /// The radio's channel PSK as reported now. Kept only in memory.
    public var psk: Data?
    /// Least private by default: a slot is only a sealed room once a key proves it.
    public var kind: RoomKind

    public init(
        index: Int, name: String, role: ChannelRole, id: Int32, positionPrecision: Int, psk: Data? = nil,
        kind: RoomKind = .meshtasticPublic
    ) {
        self.index = index
        self.name = name
        self.role = role
        self.id = id
        self.positionPrecision = positionPrecision
        self.psk = psk
        self.kind = kind
    }

    public var isRoom: Bool { index > 0 && role == .secondary }

    /// An empty name means the firmware shows the modem preset name instead.
    public var displayName: String { name.allSatisfy(\.isWhitespace) ? "Primary" : name }

    /// What picks the room's icon. Every member has to land on the same one, and the two kinds of room agree on
    /// different things.
    ///
    /// A Firepit room travels with its id inside the invite, so that is shared by construction. A Meshtastic channel's
    /// id is whatever the app that made it happened to choose — often nothing at all — and a member who typed the
    /// channel in by hand would have a different one. Its name, though, must match on every radio or the channel does
    /// not work, so that is the only thing safe to draw from.
    public var iconSeed: Int32 {
        kind == .firepit && id != 0 ? id : RoomChannel.seed(of: name)
    }

    /// FNV-1a over the UTF-8 name, identical to Android's (Swift's own hashing is salted per process and would put the
    /// same room under a different icon on each platform).
    public static func seed(of name: String) -> Int32 {
        var hash: UInt32 = 2_166_136_261
        for byte in name.utf8 {
            hash = (hash ^ UInt32(byte)) &* 16_777_619
        }
        return Int32(bitPattern: hash)
    }
}
