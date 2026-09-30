import FirepitModel
import Foundation
import Testing

@testable import FirepitProtocol

/// The privacy guarantee, stated as tests.
///
/// If any refusal here starts returning a carriage, or any sealed case starts returning an open one, a message that
/// used to be private is going out readable.
@Suite struct MessagePrivacyTests {
    private let peer: Int32 = -1_181_562_854
    private let roomId: Int32 = 0x0BAD_F00D
    private let roomSlot = 3

    private func carriage(
        to: Int32 = broadcastNodeNum,
        channel: Int = 3,
        isRoomSlot: Bool = true,
        sealingRoomId: Int32? = 0x0BAD_F00D,
        hasPeerKey: Bool = true,
        channelKey: ChannelKey = .private,
        hasPeerPhoneKey: Bool = false,
        roomKind: RoomKind? = nil
    ) -> Carriage {
        MessagePrivacy.carriageFor(
            to: to,
            channel: channel,
            isRoomSlot: isRoomSlot,
            sealingRoomId: sealingRoomId,
            hasPeerKey: hasPeerKey,
            channelKey: channelKey,
            hasPeerPhoneKey: hasPeerPhoneKey,
            roomKind: roomKind
        )
    }

    @Test func aFirepitRoomIsSealed() {
        #expect(carriage() == .sealedRoom(roomId: roomId, channel: roomSlot))
    }

    @Test func aDirectMessageIsSealedToTheirPhoneWhenWeKnowItsKey() {
        let result = carriage(to: peer, hasPeerPhoneKey: true)

        #expect(result == .sealedDirect(nodeNum: peer))
        #expect(result.isPrivate)
    }

    /// Without their phone key the firmware's PKI is all there is, and a radio hands its private key to any phone that
    /// connects. It is still sent — this is how Firepit reaches people not running it — but never called private.
    @Test func aDirectMessageWithoutTheirPhoneKeyRidesTheRadiosAloneAndSaysSo() {
        let result = carriage(to: peer)

        #expect(result == .toOneNode(nodeNum: peer))
        #expect(!result.isPrivate)
    }

    @Test func aPhoneKeyWithoutARadioKeyIsNoWayToSend() {
        #expect(
            carriage(to: peer, hasPeerKey: false, hasPeerPhoneKey: true) == .refused(reason: .noPeerKey)
        )
    }

    /// A room still on the radio whose key this phone lost. The only way left to send is the channel key, which every
    /// member's radio holds.
    @Test func aFirepitRoomWhoseKeyIsMissingIsRefusedNeverDowngradedToTheChannel() {
        #expect(
            carriage(sealingRoomId: nil, roomKind: .firepitKeyMissing) == .refused(reason: .roomKeyMissing)
        )
    }

    /// The old key is what a removed member still holds.
    @Test func aRoomThatMovedOnToAKeyWeNeverGotIsRefused() {
        #expect(
            carriage(sealingRoomId: nil, roomKind: .firepitMovedOn) == .refused(reason: .roomMovedOn)
        )
    }

    /// The gap this object exists to close. Before, an unknown peer key meant the message rode the channel instead —
    /// and
    /// the channel for a direct message is the primary, whose key is published in the firmware source.
    @Test func aDirectMessageWithoutThePeersKeyIsRefusedNeverDowngraded() {
        #expect(
            carriage(to: peer, hasPeerKey: false) == .refused(reason: .noPeerKey)
        )
    }

    /// Slot 0 sets the radio's frequency and carries NodeInfo. Keeping conversations off it means every conversation is
    /// one somebody deliberately created, and so can be labelled for what it is.
    @Test func thePrimaryChannelNeverCarriesWords() {
        #expect(
            carriage(channel: PrimaryChannel.slot, isRoomSlot: false, sealingRoomId: nil) == .refused(reason: .notARoom)
        )
    }

    @Test func aFirepitKeyIsPreferredOverTheChannelsOwnEncryption() {
        // Even on a channel with a perfectly good PSK the sealed path wins: the radio does not hold the Firepit key,
        // and
        // it does hold the PSK.
        for key in [ChannelKey.private, ChannelKey.default, ChannelKey.none] {
            #expect(carriage(channelKey: key) == .sealedRoom(roomId: roomId, channel: roomSlot), "\(key.name)")
        }
    }

    @Test func aSharedMeshtasticChannelSendsInTheOpenSoOtherClientsCanReadIt() {
        #expect(
            carriage(sealingRoomId: nil, channelKey: .private) == .openChannel(channel: roomSlot, key: .private)
        )
    }

    @Test func aPublicMeshtasticChannelSendsInTheOpenToo() {
        #expect(
            carriage(sealingRoomId: nil, channelKey: .default) == .openChannel(channel: roomSlot, key: .default)
        )
    }

    /// An interoperable channel is honest about not being private.
    @Test func anOpenChannelNeverClaimsToBePrivate() {
        for key in [ChannelKey.private, ChannelKey.default] {
            let result = carriage(sealingRoomId: nil, channelKey: key)

            if case .openChannel = result {
                #expect(true, "\(key.name)")
            } else {
                Issue.record("\(key.name) was not open")
            }
            #expect(!result.isPrivate, "\(key.name)")
        }
    }

    @Test func aChannelWithNoEncryptionAtAllIsRefused() {
        #expect(
            carriage(sealingRoomId: nil, channelKey: .none) == .refused(reason: .notEncrypted)
        )
    }

    @Test func keysDoNotSubstituteForOneAnother() {
        #expect(
            carriage(to: peer, sealingRoomId: roomId, hasPeerKey: false) == .refused(reason: .noPeerKey)
        )
        #expect(carriage(to: peer, sealingRoomId: nil) == .toOneNode(nodeNum: peer))
        #expect(carriage(hasPeerKey: false) == .sealedRoom(roomId: roomId, channel: roomSlot))
        // A room key is no substitute for somebody's phone key.
        #expect(carriage(to: peer, sealingRoomId: roomId, hasPeerPhoneKey: false) == .toOneNode(nodeNum: peer))
    }

    /// The property that matters: an open channel is only ever reached when the conversation itself is an interoperable
    /// one. No combination of missing keys turns a Firepit room or a direct message into a readable packet.
    @Test func nothingDowngradesIntoTheOpenByAccident() {
        let flags = [false, true]
        var cases: [Case] = []
        for to in [broadcastNodeNum, peer] {
            for channel in 0...ChannelSlotManager.lastRoomSlot {
                for room in [nil, roomId] as [Int32?] {
                    for isRoomSlot in flags {
                        for peerKey in flags {
                            for phoneKey in flags {
                                for kind in [nil, RoomKind.firepitKeyMissing, RoomKind.firepitMovedOn] as [RoomKind?] {
                                    for key in ChannelKey.allCases {
                                        cases.append(
                                            Case(
                                                to: to, channel: channel, isRoomSlot: isRoomSlot, room: room,
                                                peerKey: peerKey, phoneKey: phoneKey, kind: kind, key: key))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        for testCase in cases {
            let result = MessagePrivacy.carriageFor(
                to: testCase.to,
                channel: testCase.channel,
                isRoomSlot: testCase.isRoomSlot,
                sealingRoomId: testCase.room,
                hasPeerKey: testCase.peerKey,
                channelKey: testCase.key,
                hasPeerPhoneKey: testCase.phoneKey,
                roomKind: testCase.kind
            )
            let label = "\(testCase) -> \(result)"

            switch result {
            case .openChannel:
                // Only ever a broadcast on a room slot we hold no seal for, and never one that is a Firepit room we
                // lost
                // the key to.
                #expect(
                    testCase.to == broadcastNodeNum && testCase.isRoomSlot && testCase.room == nil
                        && testCase.kind == nil,
                    "\(label)"
                )
            case .sealedRoom:
                #expect(
                    testCase.to == broadcastNodeNum && testCase.isRoomSlot && testCase.room != nil,
                    "\(label)"
                )
            case .sealedDirect:
                #expect(testCase.to != broadcastNodeNum && testCase.peerKey && testCase.phoneKey, "\(label)")
            case .toOneNode:
                #expect(testCase.to != broadcastNodeNum && testCase.peerKey && !testCase.phoneKey, "\(label)")
            case .refused:
                break
            }
        }
    }

    private struct Case: CustomStringConvertible {
        var to: Int32
        var channel: Int
        var isRoomSlot: Bool
        var room: Int32?
        var peerKey: Bool
        var phoneKey: Bool
        var kind: RoomKind?
        var key: ChannelKey

        var description: String {
            """
            Case(to: \(to), channel: \(channel), isRoomSlot: \(isRoomSlot), room: \(String(describing: room)), \
            peerKey: \(peerKey), phoneKey: \(phoneKey), kind: \(String(describing: kind)), key: \(key))
            """
        }
    }

    /// A direct message is never affected by how weak the channel's key is.
    @Test func aDirectMessageIgnoresTheChannelEntirely() {
        for key in ChannelKey.allCases {
            for channel in 0...ChannelSlotManager.lastRoomSlot {
                #expect(
                    carriage(to: peer, channel: channel, isRoomSlot: channel > 0, channelKey: key)
                        == .toOneNode(nodeNum: peer),
                    "\(key.name) on \(channel)"
                )
            }
        }
    }

    @Test func sealingCostsTheComposerBytesAndARefusalLeavesNoBudgetAtAll() {
        #expect(
            MessagePrivacy.textBudgetFor(carriage: .sealedRoom(roomId: roomId, channel: roomSlot))
                == MeshConstants.maxTextBytes - MessagePrivacy.sealedOverhead
        )
        #expect(
            MessagePrivacy.textBudgetFor(carriage: .sealedDirect(nodeNum: peer))
                == MessagePrivacy.maxDirectSealedTextBytes
        )
        #expect(
            MessagePrivacy.textBudgetFor(carriage: .toOneNode(nodeNum: peer))
                == MeshConstants.maxTextBytes
        )
        #expect(
            MessagePrivacy.textBudgetFor(carriage: .openChannel(channel: roomSlot, key: .private))
                == MeshConstants.maxTextBytes
        )
        #expect(MessagePrivacy.textBudgetFor(carriage: .refused(reason: .noPeerKey)) == 0)
    }

    /// A sealed room message plus its envelope still has to fit one LoRa packet.
    @Test func theSealedBudgetLeavesRoomForTheProtobufEnvelope() {
        let budget = MessagePrivacy.textBudgetFor(carriage: .sealedRoom(roomId: roomId, channel: roomSlot))

        #expect(
            budget + MessagePrivacy.sealedOverhead <= MeshConstants.dataPayloadLen,
            "budget \(budget) plus overhead exceeds the payload limit"
        )
    }
}
