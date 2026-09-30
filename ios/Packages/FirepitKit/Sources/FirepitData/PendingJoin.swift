import FirepitModel
import FirepitProtocol
import Foundation

/// Somebody who has shown a valid code and is waiting to be let in.
///
/// The keys do not move until a person answers this, which is what makes a photographed code a request rather than a
/// way in.
public struct PendingJoin: Sendable, Equatable {
    public var nodeNum: Int32
    public var roomId: Int32
    public var inviteId: Int32
    public var generation: Int
    /// Their radio's key: the one the firmware decrypted their hello with.
    public var joinerKey: Data
    /// Their phone's key, which the room's own key is sealed to.
    public var phoneKey: Data
    public var askedAt: Int64

    public init(
        nodeNum: Int32,
        roomId: Int32,
        inviteId: Int32,
        generation: Int,
        joinerKey: Data,
        phoneKey: Data,
        askedAt: Int64
    ) {
        self.nodeNum = nodeNum
        self.roomId = roomId
        self.inviteId = inviteId
        self.generation = generation
        self.joinerKey = joinerKey
        self.phoneKey = phoneKey
        self.askedAt = askedAt
    }

    /// The only identity the mesh attests. Anyone can claim any name.
    public var nodeId: String {
        MeshNode.formatNodeId(nodeNum)
    }

    /// Their radio's key and their phone's key as one line, short enough to read aloud.
    public var fingerprint: String? {
        KeyFingerprint.ofJoin(radioKey: joinerKey, phoneKey: phoneKey)
    }
}

/// A room we have asked to join, while the answer is still outstanding.
public struct AwaitedRoom: Sendable, Equatable {
    public var roomId: Int32
    public var roomName: String
    public var inviteId: Int32
    /// Who we asked. Only the inviter can answer as the node whose key was seeded from the code.
    public var inviter: Int32
    /// Our own radio's and phone's keys as one line, for reading aloud so the inviter can check them.
    public var ownFingerprint: String?
    /// Set when the answer came back and it was no.
    public var declined: Bool

    public init(
        roomId: Int32,
        roomName: String,
        inviteId: Int32,
        inviter: Int32,
        ownFingerprint: String? = nil,
        declined: Bool = false
    ) {
        self.roomId = roomId
        self.roomName = roomName
        self.inviteId = inviteId
        self.inviter = inviter
        self.ownFingerprint = ownFingerprint
        self.declined = declined
    }
}
