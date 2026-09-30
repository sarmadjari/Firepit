import Testing

@testable import FirepitProtocol

@Suite struct ReceiptRulesTests {
    @Test func aMessageThatArrivesIsOwedADeliveryReceipt() {
        let pending = ReceiptRules.received(pending: PendingReceipts(), messageId: 11)
        #expect(pending.delivered == [11])
        #expect(pending.read.isEmpty)
    }

    @Test func openingAMessageReplacesItsDeliveryReceiptRatherThanAddingOne() {
        let arrived = ReceiptRules.received(pending: PendingReceipts(), messageId: 11)
        let opened = ReceiptRules.opened(pending: arrived, messageIds: [11])
        #expect(opened.delivered.isEmpty)
        #expect(opened.read == [11])
    }

    @Test func aMessageIsNeverOwedBothReceiptsAtOnce() {
        var pending = ReceiptRules.received(pending: PendingReceipts(), messageId: 11)
        pending = ReceiptRules.opened(pending: pending, messageIds: [11])
        pending = ReceiptRules.received(pending: pending, messageId: 11)
        #expect(pending.delivered.isEmpty)
        #expect(pending.read == [11])
    }

    @Test func aMessageReadWithoutBeingNoticedFirstIsStillOwedAReadReceipt() {
        let opened = ReceiptRules.opened(pending: PendingReceipts(), messageIds: [11])
        #expect(opened.read == [11])
    }

    @Test func aPacketCarriesNoMoreIdsThanItHasRoomFor() {
        let pending = PendingReceipts(delivered: Array(1...100).map(Int32.init), read: Array(200...300).map(Int32.init))
        #expect(ReceiptRules.batch(pending: pending).size == ReceiptRules.maxIdsPerPacket)
    }

    @Test func readReceiptsTakeTheRoomFirst() {
        let pending = PendingReceipts(delivered: Array(1...100).map(Int32.init), read: Array(200...210).map(Int32.init))
        let batch = ReceiptRules.batch(pending: pending)
        #expect(batch.read.count == 11)
        #expect(batch.delivered.count == ReceiptRules.maxIdsPerPacket - 11)
    }

    @Test func whatWasSentIsNoLongerOwed() {
        let pending = PendingReceipts(delivered: [1, 2, 3], read: [9])
        let sent = ReceiptRules.batch(pending: pending)
        #expect(ReceiptRules.remaining(pending: pending, sent: sent).isEmpty)
    }

    @Test func whatDidNotFitIsStillOwed() {
        let pending = PendingReceipts(delivered: Array(1...100).map(Int32.init))
        let sent = ReceiptRules.batch(pending: pending)
        #expect(ReceiptRules.remaining(pending: pending, sent: sent).delivered.count == 60)
    }

    @Test func aLostReceiptIsRepeatedInTheNextPacket() {
        let lost = ReceiptRules.batch(pending: PendingReceipts(read: [11, 12]))
        let next = ReceiptRules.batch(pending: PendingReceipts(read: [13]), echo: ReceiptRules.echoOf(sent: lost))
        #expect(next.read.contains(11))
        #expect(next.read.contains(13))
    }

    @Test func onlyTheMostRecentAreRepeated() {
        let sent = PendingReceipts(read: Array(1...50).map(Int32.init))
        #expect(ReceiptRules.echoOf(sent: sent).read.count == ReceiptRules.echo)
        #expect(ReceiptRules.echoOf(sent: sent).read.contains(50))
    }

    @Test func repeatingNeverPushesAPacketOverTheLimit() {
        let pending = PendingReceipts(read: Array(1...100).map(Int32.init))
        let echo = PendingReceipts(read: Array(500...508).map(Int32.init))
        #expect(ReceiptRules.batch(pending: pending, echo: echo).size == ReceiptRules.maxIdsPerPacket)
    }

    @Test func aRepeatDoesNotDuplicateSomethingAlreadyOwed() {
        let pending = PendingReceipts(read: [11, 12])
        let echo = PendingReceipts(read: [12])
        #expect(ReceiptRules.batch(pending: pending, echo: echo).read == [11, 12])
    }

    @Test func aMessageRepeatedAsReadIsNotAlsoRepeatedAsDelivered() {
        let batch = ReceiptRules.batch(pending: PendingReceipts(read: [11]), echo: PendingReceipts(delivered: [11]))
        #expect(batch.read == [11])
        #expect(batch.delivered.isEmpty)
    }

    @Test func owingNothingMeansSendingNothing() {
        #expect(PendingReceipts().isEmpty)
        #expect(ReceiptRules.batch(pending: PendingReceipts()).isEmpty)
        #expect(!ReceiptRules.received(pending: PendingReceipts(), messageId: 1).isEmpty)
    }

    private let room: Int32 = 0x0BADF00D
    private let peer: Int32 = -1_181_562_854
    private let slot = 3

    @Test func aFirepitRoomGetsASealedReceipt() {
        #expect(
            ReceiptRules.carriageFor(channel: slot, roomId: room, peer: nil, hasPeerKey: false)
                == .sealedRoom(roomId: room, channel: slot))
    }

    @Test func aDirectMessageGetsOneSealedToThatPersonsPhone() {
        #expect(
            ReceiptRules.carriageFor(channel: 0, roomId: nil, peer: peer, hasPeerKey: true, hasPeerPhoneKey: true)
                == .sealedDirect(nodeNum: peer))
    }

    @Test func nobodyGetsAReceiptTheirPhoneCannotOpen() {
        #expect(
            ReceiptRules.carriageFor(channel: 0, roomId: nil, peer: peer, hasPeerKey: true, hasPeerPhoneKey: false)
                == .none)
    }

    @Test func aStandardMeshtasticChannelGetsNothing() {
        #expect(ReceiptRules.carriageFor(channel: slot, roomId: nil, peer: nil, hasPeerKey: false) == .none)
        #expect(!ReceiptRules.tracks(roomId: nil, peer: nil))
    }

    @Test func aDirectMessageWithoutThePeersKeyIsNotSentInTheOpen() {
        #expect(ReceiptRules.carriageFor(channel: 0, roomId: nil, peer: peer, hasPeerKey: false) == .none)
    }

    @Test func nothingIsCollectedForAConversationThatCouldNeverSendOne() {
        #expect(ReceiptRules.tracks(roomId: room, peer: nil))
        #expect(ReceiptRules.tracks(roomId: nil, peer: peer))
        #expect(!ReceiptRules.tracks(roomId: nil, peer: nil))
    }

    @Test func noCombinationPutsAReceiptOnTheAirUnencrypted() {
        let flags = [false, true]
        for roomId in [nil, room] as [Int32?] {
            for peerNum in [nil, peer] as [Int32?] {
                for hasKey in flags {
                    for hasPhoneKey in flags {
                        let carriage = ReceiptRules.carriageFor(
                            channel: slot, roomId: roomId, peer: peerNum, hasPeerKey: hasKey,
                            hasPeerPhoneKey: hasPhoneKey)
                        switch carriage {
                        case .sealedRoom:
                            #expect(roomId != nil)
                        case .sealedDirect:
                            #expect(peerNum != nil && hasKey && hasPhoneKey)
                        case .none:
                            break
                        }
                    }
                }
            }
        }
    }
}
