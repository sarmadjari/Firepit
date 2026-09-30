import FirepitModel
import FirepitProtos
import Testing

@testable import FirepitProtocol

@Suite struct MessageStatusRulesTests {
    private let myNodeNum: Int32 = 0x1234
    private let peerNodeNum: Int32 = 0x5678
    private let strangerNodeNum: Int32 = 0x9ABC

    @Test func queueStatusZeroMeansTheRadioAcceptedIt() {
        #expect(MessageStatus.sentToNode == MessageStatusRules.fromQueueStatus(res: 0))
    }

    @Test func queueStatusNonZeroMeansTheRadioRefusedIt() {
        #expect(MessageStatus.failed == MessageStatusRules.fromQueueStatus(res: 1))
    }

    @Test func ackFromOurOwnNodeIsAnImplicitAckNeverADelivery() {
        let status = MessageStatusRules.fromRouting(
            errorReason: Routing.Error.none, ackFrom: myNodeNum, myNodeNum: myNodeNum, sentTo: peerNodeNum)

        #expect(MessageStatus.reachedMesh == status)
    }

    @Test func ackFromThePeerIsARealDelivery() {
        let status = MessageStatusRules.fromRouting(
            errorReason: Routing.Error.none, ackFrom: peerNodeNum, myNodeNum: myNodeNum, sentTo: peerNodeNum)

        #expect(MessageStatus.delivered == status)
    }

    /// A packet id is in every header, so anybody who heard the message can
    /// answer it. Only the person it went to can say it arrived.
    @Test func anAckFromAnyoneButTheRecipientProvesNothing() {
        #expect(
            MessageStatusRules.fromRouting(
                errorReason: Routing.Error.none, ackFrom: strangerNodeNum, myNodeNum: myNodeNum, sentTo: peerNodeNum)
                == nil)
    }

    /// A room message has no single recipient, so nobody else's ack can deliver it.
    @Test func nobodyCanClaimDeliveryOfABroadcast() {
        let broadcast = MeshConstants.broadcastNodeNum

        #expect(
            MessageStatusRules.fromRouting(
                errorReason: Routing.Error.none, ackFrom: peerNodeNum, myNodeNum: myNodeNum, sentTo: broadcast) == nil)
        #expect(
            MessageStatus.reachedMesh
                == MessageStatusRules.fromRouting(
                    errorReason: Routing.Error.none, ackFrom: myNodeNum, myNodeNum: myNodeNum, sentTo: broadcast)
        )
    }

    @Test func aStrangerCannotMarkOurMessageFailedEither() {
        #expect(
            MessageStatusRules.fromRouting(
                errorReason: .noRoute, ackFrom: strangerNodeNum, myNodeNum: myNodeNum, sentTo: peerNodeNum) == nil)
        #expect(
            MessageStatusRules.fromRouting(
                errorReason: .maxRetransmit, ackFrom: strangerNodeNum, myNodeNum: myNodeNum, sentTo: peerNodeNum) == nil
        )
    }

    @Test func maxRetransmitMeansNobodyHeardIt() {
        let status = MessageStatusRules.fromRouting(
            errorReason: .maxRetransmit, ackFrom: myNodeNum, myNodeNum: myNodeNum, sentTo: peerNodeNum)

        #expect(MessageStatus.unheard == status)
    }

    @Test func everyOtherRoutingErrorFromTheRecipientOrOurRadioIsAFailure() {
        let handledSeparately: Set<Routing.Error> = [.none, .maxRetransmit]

        let errors: [Routing.Error] = [
            .noRoute,
            .gotNak,
            .timeout,
            .noInterface,
            .noChannel,
            .tooLarge,
            .noResponse,
            .dutyCycleLimit,
            .badRequest,
            .notAuthorized,
            .pkiFailed,
            .pkiUnknownPubkey,
            .adminBadSessionKey,
            .adminPublicKeyUnauthorized,
            .rateLimitExceeded,
            .pkiSendFailPublicKey,
        ].filter { !handledSeparately.contains($0) }

        for error in errors {
            for from in [peerNodeNum, myNodeNum] {
                #expect(
                    MessageStatus.failed
                        == MessageStatusRules.fromRouting(
                            errorReason: error, ackFrom: from, myNodeNum: myNodeNum, sentTo: peerNodeNum))
            }
        }
    }

    @Test func aMissingErrorReasonIsTreatedAsSuccess() {
        // Wire leaves the field null when the firmware omits it, which the
        // firmware does for a plain ACK.
        let status = MessageStatusRules.fromRouting(
            errorReason: nil, ackFrom: myNodeNum, myNodeNum: myNodeNum, sentTo: peerNodeNum)

        #expect(MessageStatus.reachedMesh == status)
    }

    @Test func statusOnlyEverMovesForward() {
        let delivered = MessageStatus.delivered

        #expect(delivered == MessageStatusRules.advance(current: delivered, next: .reachedMesh))
        #expect(delivered == MessageStatusRules.advance(current: delivered, next: .sentToNode))
        #expect(delivered == MessageStatusRules.advance(current: delivered, next: .unheard))
    }

    @Test func normalProgressionAdvances() {
        var status = MessageStatus.queued
        status = MessageStatusRules.advance(current: status, next: .sentToNode)
        status = MessageStatusRules.advance(current: status, next: .reachedMesh)
        status = MessageStatusRules.advance(current: status, next: .delivered)

        #expect(MessageStatus.delivered == status)
    }

    @Test func aTimeoutCannotMaskAResultThatAlreadyArrived() {
        let reached = MessageStatus.reachedMesh

        #expect(reached == MessageStatusRules.advance(current: reached, next: .unknown))
    }

    @Test func unknownIsOnlyReachableFromSentToNode() {
        #expect(
            MessageStatus.unknown == MessageStatusRules.advance(current: .sentToNode, next: .unknown)
        )
    }

    @Test func failureStatesAreFlaggedForTheUi() {
        #expect(MessageStatus.failed.isFailure)
        #expect(MessageStatus.unheard.isFailure)
        #expect(!MessageStatus.reachedMesh.isFailure)
        #expect(!MessageStatus.delivered.isFailure)
    }
}
