/// How somebody in one of your rooms describes themselves.
///
/// Distinct from the node's own name: the mesh addresses radios, and this is the person holding one. Claimed rather
/// than proven — only `nodeNum` is attested — so it is shown beside the node, never as a substitute for it.
public struct PersonCard: Hashable, Sendable {
    public var nodeNum: Int32
    public var name: String
    public var tag: String
    /// Nil leaves the colour derived from `nodeNum`, as it is for everyone else.
    public var colourSlot: Int?
    public var updatedAt: Int64

    public init(nodeNum: Int32, name: String, tag: String, colourSlot: Int?, updatedAt: Int64) {
        self.nodeNum = nodeNum
        self.name = name
        self.tag = tag
        self.colourSlot = colourSlot
        self.updatedAt = updatedAt
    }
}

/// How far a message got with one person.
///
/// Only ever what someone told us. Silence is not a third state: a member out of range, asleep or with a flat battery
/// looks the same as one ignoring you, so nothing here is ever rendered as "unread by".
public enum ReceiptState: Int, CaseIterable, Sendable {
    /// Their phone has it, and has not shown it to them.
    case received
    /// They had the conversation open.
    case read

    /// Android's enum constant name, for storage.
    public var name: String { self == .received ? "RECEIVED" : "READ" }

    public init?(name: String) {
        switch name {
        case "RECEIVED": self = .received
        case "READ": self = .read
        default: return nil
        }
    }
}

/// One person's progress with one message.
public struct Receipt: Hashable, Sendable {
    public var nodeNum: Int32
    public var state: ReceiptState
    public var at: Int64

    public init(nodeNum: Int32, state: ReceiptState, at: Int64) {
        self.nodeNum = nodeNum
        self.state = state
        self.at = at
    }
}

/// Somebody we believe is in a room.
///
/// Meshtastic channels have no membership: anyone holding the key can talk, and nobody announces leaving. So this is
/// what we have *observed*, not an authoritative list. A member who never speaks is invisible, and one who has deleted
/// the room still appears until they go stale.
public struct RoomMember: Hashable, Sendable {
    public var roomId: Int32
    public var nodeNum: Int32
    /// Who vouched for them. Set only when their join was proved against an invite we issued, or relayed to us by the
    /// inviter. Nil means we simply heard them on the channel.
    public var invitedBy: Int32?
    public var firstSeen: Int64
    /// Nil when another member told us about them but we have never heard them.
    public var lastHeard: Int64?

    public init(roomId: Int32, nodeNum: Int32, invitedBy: Int32? = nil, firstSeen: Int64, lastHeard: Int64? = nil) {
        self.roomId = roomId
        self.nodeNum = nodeNum
        self.invitedBy = invitedBy
        self.firstSeen = firstSeen
        self.lastHeard = lastHeard
    }

    /// True when the invite chain proved how they got in, not just that they talk.
    public var isVouched: Bool { invitedBy != nil }

    /// False when the entry is somebody else's word rather than our own observation.
    public var isFirstHand: Bool { lastHeard != nil }
}
