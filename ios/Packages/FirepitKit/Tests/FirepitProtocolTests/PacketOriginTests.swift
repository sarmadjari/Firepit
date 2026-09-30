import FirepitProtos
import Testing

@testable import FirepitProtocol

@Suite struct PacketOriginTests {
    private func packet(hopStart: Int, hopLimit: Int) -> MeshPacket {
        var packet = MeshPacket()
        packet.from = 42
        packet.hopStart = UInt32(hopStart)
        packet.hopLimit = UInt32(hopLimit)
        return packet
    }

    @Test func anUntouchedPacketIsDirect() {
        #expect(PacketOrigin.arrivedDirectly(packet: packet(hopStart: 3, hopLimit: 3)))
    }

    @Test func aPacketSentToANeighbourOnlyIsDirect() {
        #expect(PacketOrigin.arrivedDirectly(packet: packet(hopStart: 0, hopLimit: 0)))
    }

    @Test func oneRelayIsEnoughToStopBeingDirect() {
        #expect(!PacketOrigin.arrivedDirectly(packet: packet(hopStart: 3, hopLimit: 2)))
        #expect(PacketOrigin.hopsTravelled(packet: packet(hopStart: 3, hopLimit: 2)) == 1)
    }

    @Test func aPacketFromAcrossTheMeshIsNotDirect() {
        #expect(!PacketOrigin.arrivedDirectly(packet: packet(hopStart: 7, hopLimit: 0)))
        #expect(PacketOrigin.hopsTravelled(packet: packet(hopStart: 7, hopLimit: 0)) == 7)
    }

    @Test func claimingZeroHopStartWhileStillBeingRelayedIsRefused() {
        let relayed = packet(hopStart: 0, hopLimit: 2)
        #expect(PacketOrigin.hopsTravelled(packet: relayed) == -2)
        #expect(!PacketOrigin.arrivedDirectly(packet: relayed))
    }

    @Test func everyInconsistentPairIsRefused() {
        for start in 0...7 {
            for limit in 0...7 {
                let direct = PacketOrigin.arrivedDirectly(packet: packet(hopStart: start, hopLimit: limit))
                #expect(direct == (start == limit))
            }
        }
    }
}
