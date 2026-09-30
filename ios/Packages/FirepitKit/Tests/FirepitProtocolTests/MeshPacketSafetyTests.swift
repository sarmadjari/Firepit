import FirepitModel
import FirepitProtos
import Foundation
import Testing

@testable import FirepitProtocol

/// The rules that stop a packet being built at all.
///
/// Each of these was broken at least once by a feature written before the privacy rules existed — a direct message on
/// the channel key, a buzz on the primary, a map pin carrying coordinates on the public channel. They live in the
/// builder rather than in each caller so the next such feature cannot repeat them: the packet simply refuses to exist.
@Suite struct MeshPacketSafetyTests {
    private let peer: Int32 = -1_181_562_854
    private let key = repeatedBytes(count: 32, value: 7)
    private let payload = Data("hello".utf8)

    private func build(
        to: Int32 = broadcastNodeNum,
        channel: Int = 1,
        portNum: PortNum = .textMessageApp,
        pkiEncrypted: Bool = false,
        publicKey: Data = Data()
    ) throws -> MeshPacket {
        try MeshPacketBuilder.meshPacket(
            to: to,
            channel: channel,
            portNum: portNum,
            payload: payload,
            pkiEncrypted: pkiEncrypted,
            publicKey: publicKey
        )
    }

    private func refusal(_ block: () throws -> Void) -> String {
        expectFailureMessage(block)
    }

    // --- a direct message is always encrypted to its recipient --------------

    @Test func aDirectedTextMessageWithoutEncryptionIsRefused() {
        let message = refusal { _ = try build(to: peer, portNum: .textMessageApp) }

        #expect(message.contains("encrypted to its recipient"), "\(message)")
    }

    @Test func aDirectedTextMessageEncryptedToThePeerIsAllowed() throws {
        let packet = try build(to: peer, pkiEncrypted: true, publicKey: key)

        #expect(packet.pkiEncrypted)
        #expect(Int32(bitPattern: packet.to) == peer)
    }

    /// A room message is a broadcast, and is sealed by us before it gets here.
    @Test func aBroadcastTextMessageNeedsNoPki() throws {
        _ = try build(to: broadcastNodeNum, portNum: .textMessageApp)
    }

    /// Traceroute is a routing probe that cannot be encrypted to anyone.
    @Test func aDirectedPacketOnAnotherPortIsNotForcedToUsePki() throws {
        _ = try build(to: peer, channel: 1, portNum: .tracerouteApp)
        _ = try build(to: peer, channel: 0, portNum: .privateApp)
    }

    /// Being unencryptable is why it has to ride a room: the route names every node that carried it, and the primary's
    /// key is held by every Firepit radio, so asking there publishes who is checking on whom.
    @Test func aTracerouteOnThePrimaryIsRefused() {
        let message = refusal { _ = try build(to: peer, channel: 0, portNum: .tracerouteApp) }

        #expect(message.contains("every node that carried it"), "\(message)")
    }

    // --- pki needs a real key ----------------------------------------------

    @Test func claimingPkiWithoutAKeyIsRefused() {
        let message = refusal { _ = try build(to: peer, pkiEncrypted: true) }

        #expect(message.contains("32-byte key"), "\(message)")
    }

    @Test func aKeyOfTheWrongLengthIsRefused() {
        for size in [16, 31, 33, 64] {
            _ = refusal { _ = try build(to: peer, pkiEncrypted: true, publicKey: repeatedBytes(count: size, value: 0)) }
        }
    }

    // --- positions never touch the primary ---------------------------------

    @Test func aWaypointOnThePrimaryChannelIsRefused() {
        let message = refusal {
            _ = try build(channel: PrimaryChannel.slot, portNum: .waypointApp)
        }

        #expect(message.contains("must not go on the primary"), "\(message)")
    }

    @Test func aPositionOnThePrimaryChannelIsRefused() {
        _ = refusal { _ = try build(channel: PrimaryChannel.slot, portNum: .positionApp) }
    }

    @Test func aWaypointOnARoomIsAllowed() throws {
        for slot in ChannelSlotManager.firstRoomSlot...ChannelSlotManager.lastRoomSlot {
            _ = try build(channel: slot, portNum: .waypointApp)
        }
    }

    // --- the rules the builder already had ---------------------------------

    @Test func aPacketThatWouldNeverBeRebroadcastIsRefused() {
        _ = refusal {
            _ = try MeshPacketBuilder.meshPacket(
                to: broadcastNodeNum,
                channel: 1,
                portNum: .textMessageApp,
                payload: payload,
                hopLimit: 0
            )
        }
    }

    @Test func anOversizedPayloadIsRefused() {
        _ = refusal {
            _ = try MeshPacketBuilder.meshPacket(
                to: broadcastNodeNum,
                channel: 1,
                portNum: .textMessageApp,
                payload: repeatedBytes(count: MeshConstants.dataPayloadLen + 1, value: 0)
            )
        }
    }

    @Test func aChannelOutsideTheRadiosEightSlotsIsRefused() {
        _ = refusal { _ = try build(channel: 8) }
        _ = refusal { _ = try build(channel: -1) }
    }
}
