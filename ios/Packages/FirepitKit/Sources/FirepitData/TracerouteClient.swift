import FirepitModel
import FirepitProtocol
import FirepitProtos
import FirepitTransport
import Foundation
import os

/// Asks the mesh which way it reaches a node.
///
/// The only honest answer to "did my message get through" that the protocol can
/// give. Delivery receipts do not exist on a flood network, but the path does,
/// and it is measured on demand rather than added to every message.
public final class TracerouteClient: Sendable {
    public let link: any RadioLinking
    public let mesh: MeshRepository
    public let rooms: RoomRepository

    private let pacer: OutboundPacer
    private let log = Logger(subsystem: "com.getfirepit.app", category: "FirepitTrace")

    public init(
        link: any RadioLinking,
        mesh: MeshRepository,
        rooms: RoomRepository,
        nowMillis: @escaping @Sendable () -> Int64 = currentEpochMillis,
        sleepMillis: @escaping @Sendable (Int64) async -> Void = { millis in
            if millis > 0 {
                try? await Task.sleep(for: .milliseconds(millis))
            }
        }
    ) {
        self.link = link
        self.mesh = mesh
        self.rooms = rooms
        self.pacer = OutboundPacer(nowMillis: nowMillis, sleepMillis: sleepMillis)
    }

    /**
     * Traces the route to [nodeNum], or returns null if nothing answers.
     *
     * Asked on a room's channel, never the primary: a route names every node
     * that carried it, and on a key this many people hold that is a map of who
     * is checking on whom, readable by all of them.
     *
     * The firmware allows one traceroute every 30 s and silently drops the
     * rest, so the pacer holds the caller rather than letting it believe a
     * dropped probe timed out.
     */
    public func trace(nodeNum: Int32, timeout: Duration = .seconds(60)) async -> TraceRouteResult? {
        guard mesh.myNodeNum.value != nil else {
            return nil
        }
        guard let room = await rooms.sharedRoomWith(nodeNum: nodeNum) else {
            log.info("no room shared, not tracing where others could read it")
            return nil
        }
        await pacer.awaitSlot(portNum: .tracerouteApp)
        let packet: MeshPacket
        do {
            packet = try MeshPacketBuilder.meshPacket(
                to: nodeNum,
                channel: room.index,
                portNum: .tracerouteApp,
                payload: try RouteDiscovery().serializedData(),
                hopLimit: mesh.hopLimitForSending(),
                wantResponse: true
            )
        } catch {
            return nil
        }
        let replies = link.inbound.subscribe()
        let result: TraceRouteResult? = try? await withTimeoutOrNil(timeout) {
            var toRadio = ToRadio()
            toRadio.packet = packet
            try await self.link.send(toRadio)
            guard
                let data = await replies.first(where: { from in
                    let packet = from.packet
                    return packet.decoded.portnum == .tracerouteApp && Int32(bitPattern: packet.from) == nodeNum
                })?.packet.decoded,
                let discovery = try? RouteDiscovery(serializedBytes: data.payload)
            else {
                return nil
            }
            return TraceRouteResult.from(
                target: nodeNum,
                route: discovery.route.map { Int32(bitPattern: $0) },
                snrTowards: discovery.snrTowards,
                routeBack: discovery.routeBack.map { Int32(bitPattern: $0) },
                snrBack: discovery.snrBack
            )
        }
        if result == nil {
            log.info("no traceroute reply")
        }
        return result ?? nil
    }
}
