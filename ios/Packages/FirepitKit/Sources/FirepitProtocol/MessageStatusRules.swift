import FirepitModel
import FirepitProtos
import Foundation

public enum MessageStatusRules {
    /// After this, stop expecting a routing packet and admit we do not know.
    public static let ackTimeout: Duration = .seconds(120)

    /// `QueueStatus.res` is the radio's accept/reject for a packet we wrote.
    ///
    /// It is **not** a rate-limit signal: a position, waypoint, alert or
    /// telemetry packet dropped for exceeding the phone-API rate limit still
    /// reports success here. `OutboundPacer` exists because of that.
    public static func fromQueueStatus(res: Int32) -> MessageStatus {
        if res == 0 {
            return .sentToNode
        } else {
            return .failed
        }
    }

    /// A `ROUTING_APP` packet whose `request_id` matches a message we sent, or
    /// nil when it proves nothing about that message.
    ///
    /// - Parameter ackFrom: the `from` field of the routing packet. The firmware sends
    /// an implicit ACK from **our own** node number when it overhears our packet
    /// being rebroadcast; a real acknowledgement carries the peer's number.
    /// - Parameter sentTo: who the message went to. Packet ids are in every header, so
    /// anybody can answer one: only the recipient's own ACK says it arrived, and
    /// only our radio or the recipient may say it failed.
    public static func fromRouting(
        errorReason: Routing.Error?,
        ackFrom: Int32,
        myNodeNum: Int32,
        sentTo: Int32
    ) -> MessageStatus? {
        let fromUs = ackFrom == myNodeNum
        let fromRecipient = sentTo != MeshConstants.broadcastNodeNum && ackFrom == sentTo
        switch errorReason ?? .none {
        case .none:
            if fromUs {
                return .reachedMesh
            }
            if fromRecipient {
                return .delivered
            }
            return nil
        case .maxRetransmit:
            if fromUs {
                return .unheard
            }
            return nil
        default:
            if fromUs || fromRecipient {
                return .failed
            }
            return nil
        }
    }

    /// Packets arrive out of order, so status only ever moves forward. Without
    /// this a late implicit ACK could downgrade a confirmed delivery.
    public static func advance(current: MessageStatus, next: MessageStatus) -> MessageStatus {
        if next.rawValue > current.rawValue {
            return next
        } else {
            return current
        }
    }
}
