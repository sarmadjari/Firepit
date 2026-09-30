import FirepitModel
import FirepitProtos
import Foundation

/// Builds outgoing packets so the hop-limit trap cannot be hit.
///
/// `MeshService::handleToRadio` does not default `hop_limit`, and `sendLocal` fills it only when `want_ack` is set. A
/// phone-built packet with `hop_limit` left at 0 and no `want_ack` is transmitted and then never rebroadcast, which
/// looks exactly like a range problem.
public enum MeshPacketBuilder {
    /// A packet destined for the mesh. [hopLimit] cannot be 0 here — use [localPacket] for anything addressed to our
    /// own node.
    public static func meshPacket(
        to: Int32,
        channel: Int,
        portNum: PortNum,
        payload: Data,
        hopLimit: Int = MeshConstants.defaultHopLimit,
        wantAck: Bool = false,
        wantResponse: Bool = false,
        priority: MeshPacket.Priority = .unset,
        pkiEncrypted: Bool = false,
        publicKey: Data = Data(),
        replyId: Int32? = nil,
        emoji: Int32? = nil,
        id: Int32 = randomPacketId()
    ) throws -> MeshPacket {
        guard hopLimit >= 1 && hopLimit <= MeshConstants.maxHopLimit else {
            throw MeshPacketBuilderError.invalidHopLimit(
                "hop_limit must be 1..\(MeshConstants.maxHopLimit); 0 is transmitted but never rebroadcast"
            )
        }
        guard channel >= 0 && channel <= 7 else {
            throw MeshPacketBuilderError.invalidChannel("channel index must be 0..7, was \(channel)")
        }
        guard payload.count <= MeshConstants.dataPayloadLen else {
            throw MeshPacketBuilderError.payloadTooLarge(
                "payload is \(payload.count) bytes, over the \(MeshConstants.dataPayloadLen)-byte limit"
            )
        }

        // The three rules below are asserted here rather than left to each caller, because every one of them was broken
        // at least once by a feature written before the privacy rules existed. A packet that cannot be built is a bug
        // that cannot ship.
        guard !pkiEncrypted || publicKey.count == publicKeySize else {
            throw MeshPacketBuilderError.invalidPublicKey(
                "pki_encrypted needs the peer's \(publicKeySize)-byte key; got \(publicKey.count) bytes"
            )
        }
        guard to == MeshConstants.broadcastNodeNum || portNum != .textMessageApp || pkiEncrypted else {
            throw MeshPacketBuilderError.directTextNotEncrypted(
                "a direct text message must be encrypted to its recipient: without it the message "
                    + "rides the channel key, which is what Meshtastic direct messages did before 2.5"
            )
        }
        guard !locationPorts.contains(portNum) || channel != ChannelSlotManager.primarySlot else {
            throw MeshPacketBuilderError.locationOnPrimary(
                "\(portNum) carries a position and must not go on the primary channel, which every "
                    + "radio in range can decrypt"
            )
        }
        guard portNum != .tracerouteApp || channel != ChannelSlotManager.primarySlot else {
            throw MeshPacketBuilderError.tracerouteOnPrimary(
                "a traceroute names every node that carried it, and the primary channel's key is "
                    + "held by every Firepit radio; ask inside a room instead"
            )
        }

        var data = DataMessage()
        data.portnum = portNum
        data.payload = payload
        data.wantResponse = wantResponse
        data.replyID = UInt32(bitPattern: replyId ?? 0)
        data.emoji = UInt32(bitPattern: emoji ?? 0)

        var packet = MeshPacket()
        packet.to = UInt32(bitPattern: to)
        // PKI packets carry a channel hash of 0 on the wire.
        packet.channel = pkiEncrypted ? 0 : UInt32(channel)
        packet.id = UInt32(bitPattern: id)
        packet.hopLimit = UInt32(hopLimit)
        packet.wantAck = wantAck
        packet.priority = priority
        packet.pkiEncrypted = pkiEncrypted
        packet.publicKey = publicKey
        packet.decoded = data
        return packet
    }

    /// A packet for our own node that never reaches the air: local admin, and feeding the phone's GPS fix to a radio
    /// that has no fix of its own.
    public static func localPacket(
        myNodeNum: Int32,
        portNum: PortNum,
        payload: Data,
        wantResponse: Bool = false,
        priority: MeshPacket.Priority = .unset,
        id: Int32 = randomPacketId()
    ) -> MeshPacket {
        var data = DataMessage()
        data.portnum = portNum
        data.payload = payload
        data.wantResponse = wantResponse

        var packet = MeshPacket()
        packet.to = UInt32(bitPattern: myNodeNum)
        packet.channel = 0
        packet.id = UInt32(bitPattern: id)
        packet.hopLimit = 0
        packet.wantAck = false
        packet.priority = priority
        packet.decoded = data
        return packet
    }

    /// Non-zero so the packet can be correlated with its ACK.
    public static func randomPacketId() -> Int32 {
        var value = Int32.random(in: Int32.min...Int32.max)
        if value == 0 { value = 1 }
        return value
    }

    /// Curve25519 public key length; anything else cannot encrypt to a node.
    private static let publicKeySize = 32

    /// Payloads that say where somebody is, and so are never for the primary.
    private static let locationPorts: Set<PortNum> = [.positionApp, .waypointApp]
}

public enum MeshPacketBuilderError: Error, CustomStringConvertible, Equatable, Sendable {
    case invalidHopLimit(String)
    case invalidChannel(String)
    case payloadTooLarge(String)
    case invalidPublicKey(String)
    case directTextNotEncrypted(String)
    case locationOnPrimary(String)
    case tracerouteOnPrimary(String)

    public var description: String {
        switch self {
        case .invalidHopLimit(let message):
            return message
        case .invalidChannel(let message):
            return message
        case .payloadTooLarge(let message):
            return message
        case .invalidPublicKey(let message):
            return message
        case .directTextNotEncrypted(let message):
            return message
        case .locationOnPrimary(let message):
            return message
        case .tracerouteOnPrimary(let message):
            return message
        }
    }
}
