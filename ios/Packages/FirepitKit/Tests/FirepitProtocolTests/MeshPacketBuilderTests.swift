import FirepitModel
import FirepitProtos
import Foundation
import Testing

@testable import FirepitProtocol

@Suite struct MeshPacketBuilderTests {
    @Test func rejectsAHopLimitOfZeroWhichIsTransmittedButNeverRebroadcast() {
        let error = expectFailureMessage {
            _ = try MeshPacketBuilder.meshPacket(
                to: MeshConstants.broadcastNodeNum,
                channel: 1,
                portNum: .textMessageApp,
                payload: Data("hi".utf8),
                hopLimit: 0
            )
        }

        #expect(error.contains("never rebroadcast"))
    }

    @Test func defaultsToTheFirmwareHopLimit() throws {
        let packet = try MeshPacketBuilder.meshPacket(
            to: MeshConstants.broadcastNodeNum,
            channel: 1,
            portNum: .textMessageApp,
            payload: Data("hi".utf8)
        )

        #expect(Int(packet.hopLimit) == MeshConstants.defaultHopLimit)
        #expect(packet.id != 0, "id must be non-zero to correlate ACKs")
    }

    @Test func rejectsAPayloadOverTheFirmwareBudget() {
        #expect(throws: MeshPacketBuilderError.self) {
            _ = try MeshPacketBuilder.meshPacket(
                to: MeshConstants.broadcastNodeNum,
                channel: 1,
                portNum: .textMessageApp,
                payload: Data(String(repeating: "x", count: MeshConstants.dataPayloadLen + 1).utf8)
            )
        }
    }

    @Test func pkiPacketsCarryAZeroChannelHash() throws {
        let packet = try MeshPacketBuilder.meshPacket(
            to: 0x1234,
            channel: 5,
            portNum: .textMessageApp,
            payload: Data("hi".utf8),
            pkiEncrypted: true,
            publicKey: repeatedBytes(count: 32, value: 7)
        )

        #expect(packet.channel == 0)
    }

    @Test func localPacketsUseHopLimitZeroSoTheyNeverReachTheAir() {
        let packet = MeshPacketBuilder.localPacket(
            myNodeNum: 0x1234,
            portNum: .positionApp,
            payload: Data()
        )

        #expect(packet.hopLimit == 0)
        #expect(packet.to == 0x1234)
    }

    @Test func formatsNodeIdsTheWayEveryMeshtasticClientShowsThem() {
        #expect(MeshConstants.formatNodeId(0xABCD) == "!0000abcd")
        #expect(MeshConstants.formatNodeId(MeshConstants.broadcastNodeNum) == "!ffffffff")
    }
}
