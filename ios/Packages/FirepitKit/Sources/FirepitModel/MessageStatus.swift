/// How far an outgoing message actually got.
///
/// A LoRa mesh cannot prove a person read anything, and for a room broadcast it cannot even prove anyone received it.
/// Each state maps to something the radio genuinely reported — see `meshchat-ux-design.md` §7.2 for the wording.
///
/// Declaration order is the progression order: status only ever moves forward, so a late packet cannot downgrade a
/// confirmed delivery. The raw value is that order (Android's `ordinal`).
public enum MessageStatus: Int, CaseIterable, Comparable, Sendable {
    /// In the app's outbound queue, not yet written to the radio.
    case queued
    /// The radio accepted it for transmission.
    case sentToNode
    /// No routing packet arrived in time. Displayed the same as `sentToNode`.
    case unknown
    /// Retransmits exhausted and nobody repeated it.
    case unheard
    case failed
    /// We heard our own packet rebroadcast, so it entered the mesh. This is the final, honest state for a room message:
    /// it never means anyone received it.
    case reachedMesh
    /// The destination node acknowledged. Direct messages only.
    case delivered
    /// Somebody else's message that we received. Outside the progression above: a delivery status describes something
    /// we sent, and there is nothing to confirm about a message already in our hands.
    case received

    public var isFailure: Bool { self == .failed || self == .unheard }

    /// Android's enum constant name, used wherever a status is stored or logged, so both platforms spell it alike.
    public var name: String {
        switch self {
        case .queued: "QUEUED"
        case .sentToNode: "SENT_TO_NODE"
        case .unknown: "UNKNOWN"
        case .unheard: "UNHEARD"
        case .failed: "FAILED"
        case .reachedMesh: "REACHED_MESH"
        case .delivered: "DELIVERED"
        case .received: "RECEIVED"
        }
    }

    public init?(name: String) {
        guard let match = Self.allCases.first(where: { $0.name == name }) else { return nil }
        self = match
    }

    public static func < (lhs: MessageStatus, rhs: MessageStatus) -> Bool { lhs.rawValue < rhs.rawValue }
}
