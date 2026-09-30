import FirepitModel
import FirepitProtocol
import FirepitProtos
import FirepitTransport
import Foundation
import os

/// What the mesh said about a buzz. Silence is not success.
public enum BuzzResult: Sendable, Equatable {
    /// The radio acknowledged it. Whether it made a sound is between it and its owner.
    case delivered

    /// Heard being rebroadcast, which says the mesh took it, not that it arrived.
    case reachedMesh

    /// It answered, and the answer was no.
    case refused(reason: Routing.Error)

    case noAnswer

    /**
     * Their node has never published a public key, so there is no way to reach
     * it without putting "this phone buzzed that node" on a shared channel.
     */
    case noKey
}

/// Makes one radio announce itself.
///
/// Two identical boards on a desk are told apart by making one of them make a
/// noise, which is the one question a list of names cannot settle when the names
/// were chosen badly.
///
/// Sent as a **text message**, not on `ALERT_APP`. Field-tested: an alert reaches
/// the node and a screen will draw it, but the External Notification module only
/// sounds its buzzer for an arriving text, so the port that looks right is the
/// one that stays silent. The bell character leads the payload so a radio with
/// `alert_bell` configured rings on that too.
///
/// A board with no buzzer, or with the module switched off, does nothing at all,
/// and nothing comes back over the mesh to say which happened.
public final class AlertClient: Sendable {
    public let link: any RadioLinking
    public let mesh: MeshRepository
    private let pacer = OutboundPacer(nowMillis: Date.nowMillis)
    private let log = Logger(subsystem: "com.getfirepit.app", category: "FirepitAlert")

    public init(link: any RadioLinking, mesh: MeshRepository) {
        self.link = link
        self.mesh = mesh
    }

    public func buzz(nodeNum: Int32, timeout: Duration = .seconds(30)) async -> BuzzResult {
        guard let myNodeNum = mesh.myNodeNum.value else {
            return .noAnswer
        }

        // Our own radio is reached without touching the air at all, the firmware
        // hands a local packet to its modules just the same, and answers nothing.
        if nodeNum == myNodeNum {
            let packet = MeshPacketBuilder.localPacket(
                myNodeNum: myNodeNum,
                portNum: .textMessageApp,
                payload: Self.bell
            )
            var toRadio = ToRadio()
            toRadio.packet = packet
            // Delivered only once the radio took it: Kotlin's send throws out of buzz otherwise.
            do {
                try await link.send(toRadio)
            } catch {
                log.warning("could not buzz our own node")
                return .noAnswer
            }
            log.info("buzzed our own node")
            return .delivered
        }

        // Encrypted to the node, like any other directed message: on a shared
        // channel this would announce who is buzzing whom to everyone holding
        // the key, and on a factory primary that is every radio in range.
        guard let publicKey = await mesh.publicKeyOf(nodeNum: nodeNum) else {
            log.warning("no public key for peer, not buzzing over a shared channel")
            return .noKey
        }

        await pacer.awaitSlot(portNum: .textMessageApp)
        guard
            let packet = try? MeshPacketBuilder.meshPacket(
                to: nodeNum,
                channel: 0,
                portNum: .textMessageApp,
                payload: Self.bell,
                wantAck: true,
                pkiEncrypted: true,
                publicKey: publicKey
            )
        else {
            return .noAnswer
        }

        // Listening starts before sending: an answer can arrive first, and a child task may only start after it.
        let replies = link.inbound.subscribe()
        let result: BuzzResult? = try? await withTimeoutOrNil(timeout) {
            async let reply = replies.first { from in
                guard case .packet(let incoming)? = from.payloadVariant,
                    case .decoded(let data)? = incoming.payloadVariant
                else {
                    return false
                }
                let sender = Int32(bitPattern: incoming.from)
                return data.portnum == .routingApp && data.requestID == packet.id
                    && (sender == nodeNum || sender == myNodeNum)
            }
            var toRadio = ToRadio()
            toRadio.packet = packet
            try await self.link.send(toRadio)
            guard let from = await reply,
                case .packet(let answer)? = from.payloadVariant,
                case .decoded(let data)? = answer.payloadVariant,
                let routing = try? Routing(serializedBytes: data.payload)
            else {
                return .noAnswer
            }
            let status = MessageStatusRules.fromRouting(
                errorReason: routing.errorReason,
                ackFrom: Int32(bitPattern: answer.from),
                myNodeNum: myNodeNum,
                sentTo: nodeNum
            )
            switch status {
            case .delivered:
                return .delivered
            case .reachedMesh:
                return .reachedMesh
            default:
                return .refused(reason: routing.errorReason)
            }
        }

        let final = result ?? .noAnswer
        log.info("buzz result recorded")
        return final
    }

    /**
     * Bell first, because that is the byte the firmware's notification
     * module rings on. The word is only for whoever is looking at a screen
     * when it lands, and is deliberately not the product name: this is the
     * one thing Firepit sends that cannot be sealed, so it must not be the
     * thing that announces Firepit to anyone holding the channel key.
     */
    internal static let bell = Data("\u{0007}Ping".utf8)
}

extension Date {
    fileprivate static func nowMillis() -> Int64 {
        currentEpochMillis()
    }
}
