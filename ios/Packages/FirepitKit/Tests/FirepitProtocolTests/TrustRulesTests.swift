import Foundation
import Testing

@testable import FirepitProtocol

/// The rules that decide which mesh traffic is believed.
///
/// Each negative case here is an attack that worked before the rule existed.
@Suite struct TrustRulesTests {
    private let bob = key(1)
    private let eve = key(2)
    private let bobPhone = key(3, size: 33)
    private let evePhone = key(4, size: 33)

    @Test func aHelloDecryptedWithTheKeyItNamesIsBelieved() {
        #expect(TrustRules.helloIsBound(pkiEncrypted: true, addressedToUs: true, decryptedWith: bob, claimed: bob))
    }

    @Test func aHelloThatNamesAKeyOtherThanTheOneItCameUnderIsNot() {
        #expect(!TrustRules.helloIsBound(pkiEncrypted: true, addressedToUs: true, decryptedWith: bob, claimed: eve))
    }

    @Test func anUnencryptedOrMisaddressedHelloIsNot() {
        #expect(!TrustRules.helloIsBound(pkiEncrypted: false, addressedToUs: true, decryptedWith: bob, claimed: bob))
        #expect(!TrustRules.helloIsBound(pkiEncrypted: true, addressedToUs: false, decryptedWith: bob, claimed: bob))
    }

    @Test func aHelloWithNoUsableKeyIsNot() {
        #expect(
            !TrustRules.helloIsBound(pkiEncrypted: true, addressedToUs: true, decryptedWith: Data(), claimed: Data()))
    }

    @Test func aSecondHelloCannotSwapTheKeysOfOneAlreadyWaiting() {
        #expect(
            TrustRules.mayReplacePending(waitingRadioKey: nil, waitingPhoneKey: nil, radioKey: bob, phoneKey: bobPhone))
        #expect(
            TrustRules.mayReplacePending(
                waitingRadioKey: bob, waitingPhoneKey: bobPhone, radioKey: bob, phoneKey: bobPhone))
        #expect(
            !TrustRules.mayReplacePending(
                waitingRadioKey: bob, waitingPhoneKey: bobPhone, radioKey: eve, phoneKey: bobPhone))
        #expect(
            !TrustRules.mayReplacePending(
                waitingRadioKey: bob, waitingPhoneKey: bobPhone, radioKey: bob, phoneKey: evePhone))
    }

    @Test func aScannedCodeNeverOverwritesAKeyTheRadioAlreadyHolds() {
        #expect(TrustRules.Contact.add == TrustRules.contactFor(knownKey: nil, codeKey: bob))
        #expect(TrustRules.Contact.known == TrustRules.contactFor(knownKey: bob, codeKey: bob))
        #expect(TrustRules.Contact.mismatch == TrustRules.contactFor(knownKey: bob, codeKey: eve))
    }

    @Test func aGrantForANewRoomIsTaken() {
        #expect(
            TrustRules.mayTakeGrant(alreadyHeld: false, senderIsMember: false, grantGeneration: 1, currentGeneration: 1)
        )
    }

    @Test func aStrangerCannotOverwriteARoomWeAlreadyHold() {
        #expect(
            !TrustRules.mayTakeGrant(alreadyHeld: true, senderIsMember: false, grantGeneration: 9, currentGeneration: 1)
        )
    }

    @Test func aMemberCanBringUsForwardNeverBack() {
        #expect(
            TrustRules.mayTakeGrant(alreadyHeld: true, senderIsMember: true, grantGeneration: 3, currentGeneration: 2))
        #expect(
            !TrustRules.mayTakeGrant(alreadyHeld: true, senderIsMember: true, grantGeneration: 2, currentGeneration: 2))
        #expect(
            !TrustRules.mayTakeGrant(alreadyHeld: true, senderIsMember: true, grantGeneration: 1, currentGeneration: 2))
    }

    private func rotation(
        sealedUnderRoom: Int32? = Self.room,
        sealedUnderGeneration: Int32? = 2,
        rotationRoom: Int32 = Self.room,
        rotationGeneration: Int32 = 3,
        privatelyToUs: Bool = true,
        senderIsMember: Bool = true
    ) -> Bool {
        TrustRules.rotationAcceptable(
            sealedUnderRoom: sealedUnderRoom,
            sealedUnderGeneration: sealedUnderGeneration,
            rotationRoom: rotationRoom,
            rotationGeneration: rotationGeneration,
            currentGeneration: 2,
            privatelyToUs: privatelyToUs,
            senderIsMember: senderIsMember
        )
    }

    @Test func aRotationSealedUnderTheCurrentKeyPrivatelyFromAMemberIsTaken() {
        #expect(rotation())
    }

    @Test func aRotationThatDoesNotProveItHoldsTheCurrentKeyIsRefused() {
        #expect(!rotation(sealedUnderRoom: nil, sealedUnderGeneration: nil))
        #expect(!rotation(sealedUnderGeneration: 1))
        #expect(!rotation(sealedUnderRoom: Self.room + 1))
    }

    @Test func aRotationBroadcastFromAStrangerOrNotMovingForwardIsRefused() {
        #expect(!rotation(privatelyToUs: false))
        #expect(!rotation(senderIsMember: false))
        #expect(!rotation(rotationGeneration: 2))
    }

    @Test func aSealedMessageIsOnlyBelievedOnItsOwnRoomsSlotOrPrivately() {
        #expect(TrustRules.sealedPlacementOk(slotRoom: Self.room, sealedRoom: Self.room, privatelyToUs: false))
        #expect(TrustRules.sealedPlacementOk(slotRoom: nil, sealedRoom: Self.room, privatelyToUs: true))
        #expect(!TrustRules.sealedPlacementOk(slotRoom: Self.room + 1, sealedRoom: Self.room, privatelyToUs: false))
        #expect(!TrustRules.sealedPlacementOk(slotRoom: nil, sealedRoom: Self.room, privatelyToUs: false))
    }

    private func sync(
        privatelyToUs: Bool = true,
        sender: Int32 = 7,
        ourInviter: Int32? = 7,
        senderIsMember: Bool = true,
        sealedUnderCurrent: Bool = true
    ) -> Bool {
        TrustRules.rosterSyncAcceptable(
            privatelyToUs: privatelyToUs,
            sender: sender,
            ourInviter: ourInviter,
            senderIsMember: senderIsMember,
            sealedUnderCurrent: sealedUnderCurrent
        )
    }

    @Test func aRosterSyncIsOnlyTheWordOfWhoeverLetUsIn() {
        #expect(sync())
        #expect(!sync(privatelyToUs: false))
        #expect(!sync(sender: 8))
        #expect(!sync(ourInviter: nil))
    }

    @Test func anInviterSinceRemovedCannotPutThemselvesBack() {
        #expect(!sync(senderIsMember: false))
    }

    /// Unsealed, the membership list is readable by both radios it passes through.
    @Test func aRosterSyncOnlyCountsSealedUnderTheRoomsCurrentKey() {
        #expect(!sync(sealedUnderCurrent: false))
    }

    @Test func aNoticeThatTheRoomMovedOnIsBelievedFromAMemberUnderTheCurrentKey() {
        #expect(
            TrustRules.rotationNoticeAcceptable(
                sealedUnderCurrent: true, senderIsMember: true, noticeGeneration: 3, currentGeneration: 2))
    }

    @Test func aRotationNoticeUnderAnOldKeyFromAStrangerOrNotMovingForwardIsIgnored() {
        #expect(
            !TrustRules.rotationNoticeAcceptable(
                sealedUnderCurrent: false, senderIsMember: true, noticeGeneration: 3, currentGeneration: 2))
        #expect(
            !TrustRules.rotationNoticeAcceptable(
                sealedUnderCurrent: true, senderIsMember: false, noticeGeneration: 3, currentGeneration: 2))
        #expect(
            !TrustRules.rotationNoticeAcceptable(
                sealedUnderCurrent: true, senderIsMember: true, noticeGeneration: 2, currentGeneration: 2))
        #expect(
            !TrustRules.rotationNoticeAcceptable(
                sealedUnderCurrent: true, senderIsMember: true, noticeGeneration: 1, currentGeneration: 2))
    }

    /// A removed member still holds the old key, so they can seal a notice. One
    /// naming a generation far ahead would leave members waiting for a key that
    /// never comes; only the very next generation is believed.
    @Test func aRotationNoticeThatSkipsAheadIsIgnored() {
        #expect(
            !TrustRules.rotationNoticeAcceptable(
                sealedUnderCurrent: true, senderIsMember: true, noticeGeneration: 4, currentGeneration: 2))
        #expect(
            !TrustRules.rotationNoticeAcceptable(
                sealedUnderCurrent: true, senderIsMember: true, noticeGeneration: Int32.max, currentGeneration: 2))
    }

    @Test func aSealedPositionCountsOnlyUnderTheCurrentKeyOnItsOwnRoom() {
        #expect(TrustRules.sealedPositionAcceptable(sealedUnderCurrent: true, onItsRoomSlot: true))
        #expect(!TrustRules.sealedPositionAcceptable(sealedUnderCurrent: false, onItsRoomSlot: true))
        #expect(!TrustRules.sealedPositionAcceptable(sealedUnderCurrent: true, onItsRoomSlot: false))
    }

    /// A member's position only ever travels sealed; an unsealed one was written by a radio holder.
    @Test func anUnsealedPositionIsNeverBelievedAboutAMember() {
        #expect(!TrustRules.unsealedPositionAcceptable(senderInOurRooms: true))
        #expect(TrustRules.unsealedPositionAcceptable(senderInOurRooms: false))
    }

    @Test func whereWeAreIsOnlySaidToAMemberOfTheRoomWeShareWith() {
        #expect(
            TrustRules.positionQueryAnswerable(
                sealedUnderCurrent: true, addressedToUs: true, queryRoom: Self.room, sharingWithRoom: Self.room))
        #expect(
            !TrustRules.positionQueryAnswerable(
                sealedUnderCurrent: true, addressedToUs: true, queryRoom: Self.room, sharingWithRoom: nil))
        #expect(
            !TrustRules.positionQueryAnswerable(
                sealedUnderCurrent: true, addressedToUs: true, queryRoom: Self.room, sharingWithRoom: Self.room + 1))
        #expect(
            !TrustRules.positionQueryAnswerable(
                sealedUnderCurrent: false, addressedToUs: true, queryRoom: Self.room, sharingWithRoom: Self.room))
        #expect(
            !TrustRules.positionQueryAnswerable(
                sealedUnderCurrent: true, addressedToUs: false, queryRoom: Self.room, sharingWithRoom: Self.room))
    }

    @Test func phoneKeyProvenanceRuleTable() {
        for source in TrustRules.PhoneKeySource.allCases {
            let decision = TrustRules.shouldStorePhoneKey(
                known: nil,
                knownInPerson: false,
                incoming: bobPhone,
                source: source
            )
            #expect(decision.store)
            #expect(decision.inPerson == (source == .inPerson))
            #expect(!decision.replaced)
        }

        for source in TrustRules.PhoneKeySource.allCases {
            for knownInPerson in [false, true] {
                let decision = TrustRules.shouldStorePhoneKey(
                    known: bobPhone,
                    knownInPerson: knownInPerson,
                    incoming: bobPhone,
                    source: source
                )
                #expect(decision.store == (source == .inPerson && !knownInPerson))
                #expect(decision.inPerson == (knownInPerson || source == .inPerson))
                #expect(!decision.replaced)
            }
        }

        for source in TrustRules.PhoneKeySource.allCases {
            for knownInPerson in [false, true] {
                let decision = TrustRules.shouldStorePhoneKey(
                    known: bobPhone,
                    knownInPerson: knownInPerson,
                    incoming: evePhone,
                    source: source
                )
                let shouldStore = source == .inPerson || (source == .vouched && !knownInPerson)
                #expect(decision.store == shouldStore)
                #expect(decision.inPerson == (shouldStore ? source == .inPerson : knownInPerson))
                #expect(decision.replaced == shouldStore)
            }
        }
    }

    private func pin(
        sealedInRoom: Int32? = Self.room,
        sender: Int32 = 7,
        claimedLock: Int32 = 7,
        existingLock: Int32? = nil,
        existingRoom: Int32? = nil
    ) -> Bool {
        TrustRules.pinUpdateAllowed(
            sealedInRoom: sealedInRoom,
            sender: sender,
            claimedLock: claimedLock,
            existingLock: existingLock,
            existingRoom: existingRoom
        )
    }

    @Test func aPinSealedInARoomByItsOwnerIsTaken() {
        #expect(pin())
        #expect(pin(claimedLock: 0))
    }

    /// Unsealed, anyone holding a member's radio could have written it under any name.
    @Test func aPinThatWasNotSealedInARoomIsRefused() {
        #expect(!pin(sealedInRoom: nil))
    }

    @Test func nobodyCanLockAPinToSomebodyElse() {
        #expect(!pin(claimedLock: 8))
    }

    @Test func aLockedPinOnlyChangesAtItsOwnersHandAndNeverChangesRoom() {
        #expect(pin(sender: 7, existingLock: 7, existingRoom: Self.room))
        #expect(!pin(sender: 8, claimedLock: 0, existingLock: 7, existingRoom: Self.room))
        #expect(pin(sender: 8, claimedLock: 0, existingLock: 0, existingRoom: Self.room))
        #expect(!pin(sender: 7, existingLock: 7, existingRoom: Self.room + 1))
    }

    @Test func aReceiptForARoomMessageComesOnlyFromAMember() {
        #expect(
            TrustRules.receiptAllowed(
                isOutgoing: true, sentTo: MeshConstants.broadcastNodeNum, sender: 7, senderIsRoomMember: true))
        #expect(
            !TrustRules.receiptAllowed(
                isOutgoing: true, sentTo: MeshConstants.broadcastNodeNum, sender: 7, senderIsRoomMember: false))
    }

    @Test func aReceiptForADirectMessageComesOnlyFromItsRecipient() {
        #expect(TrustRules.receiptAllowed(isOutgoing: true, sentTo: 7, sender: 7, senderIsRoomMember: false))
        #expect(!TrustRules.receiptAllowed(isOutgoing: true, sentTo: 7, sender: 8, senderIsRoomMember: true))
    }

    @Test func nobodyCanReceiptAMessageWeDidNotSend() {
        #expect(!TrustRules.receiptAllowed(isOutgoing: false, sentTo: 7, sender: 7, senderIsRoomMember: true))
    }

    @Test func aDirectMessageIsOnlyStoredWhenItCameUnderPki() {
        #expect(TrustRules.plainTextAcceptable(direct: true, pkiEncrypted: true, onPrimary: true, onFirepitRoom: false))
        #expect(
            !TrustRules.plainTextAcceptable(direct: true, pkiEncrypted: false, onPrimary: true, onFirepitRoom: false))
        #expect(
            !TrustRules.plainTextAcceptable(direct: true, pkiEncrypted: false, onPrimary: false, onFirepitRoom: true))
    }

    @Test func unsealedTextOnASealedRoomOrThePrimaryIsDropped() {
        #expect(
            !TrustRules.plainTextAcceptable(direct: false, pkiEncrypted: false, onPrimary: false, onFirepitRoom: true))
        #expect(
            !TrustRules.plainTextAcceptable(direct: false, pkiEncrypted: false, onPrimary: true, onFirepitRoom: false))
    }

    @Test func textOnAnOrdinaryMeshtasticChannelIsKept() {
        #expect(
            TrustRules.plainTextAcceptable(direct: false, pkiEncrypted: false, onPrimary: false, onFirepitRoom: false))
    }

    private static func key(_ seed: UInt8, size: Int = 32) -> Data {
        Data(repeating: seed, count: size)
    }

    private static let room: Int32 = 0x0BAD_F00D
}
