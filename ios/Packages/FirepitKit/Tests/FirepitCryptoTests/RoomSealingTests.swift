import FirepitProtocol
import Foundation
import Testing

@testable import FirepitCrypto

/// Ported from android/core/crypto RoomCipherTest and SealedTextTest.
@Suite("Room sealing")
struct RoomSealingTests {
    let key = RoomCipher.generateKey()
    let other = RoomCipher.generateKey()
    let text = Data("meet at the north gate".utf8)
    let room = Data("room-7".utf8)

    @Test func memberWithTheKeyReadsTheMessageBack() {
        let sealed = RoomCipher.seal(key: key, plaintext: text, context: room)
        #expect(RoomCipher.open(key: key, sealed: sealed, context: room) == text)
    }

    @Test func someoneHoldingOnlyTheChannelKeyReadsNothing() {
        let sealed = RoomCipher.seal(key: key, plaintext: text, context: room)
        #expect(RoomCipher.open(key: other, sealed: sealed, context: room) == nil)
    }

    @Test func theWordsAreNotInThePacket() {
        let sealed = RoomCipher.seal(key: key, plaintext: text, context: room)
        #expect(!sealed.containsRun(Data("north gate".utf8)))
    }

    @Test func changingAByteInFlightIsRefusedNotDelivered() {
        var sealed = RoomCipher.seal(key: key, plaintext: text, context: room)
        sealed[sealed.count - 1] &+= 1
        #expect(RoomCipher.open(key: key, sealed: sealed, context: room) == nil)
    }

    @Test func aMessageCannotBeReplayedIntoAnotherRoom() {
        let sealed = RoomCipher.seal(key: key, plaintext: text, context: room)
        #expect(RoomCipher.open(key: key, sealed: sealed, context: Data("room-8".utf8)) == nil)
    }

    @Test func sealingTheSameWordsTwiceNeverRepeatsANonce() {
        let nonces = (1...200).map { _ in
            RoomCipher.seal(key: key, plaintext: text, context: room).prefix(RoomCipher.nonceSize)
        }
        #expect(Set(nonces).count == nonces.count)
    }

    @Test func theSameWordsSealDifferentlyEveryTime() {
        #expect(
            RoomCipher.seal(key: key, plaintext: text, context: room)
                != RoomCipher.seal(key: key, plaintext: text, context: room))
    }

    @Test func sealingCostsExactlyTheAdvertisedOverhead() {
        #expect(RoomCipher.seal(key: key, plaintext: text, context: room).count == text.count + RoomCipher.overhead)
        #expect(RoomCipher.overhead == 28)
    }

    @Test func truncatedOrEmptyInputIsRefusedRatherThanCrashing() {
        #expect(RoomCipher.open(key: key, sealed: Data(), context: room) == nil)
        #expect(RoomCipher.open(key: key, sealed: Data(count: RoomCipher.nonceSize), context: room) == nil)
        #expect(RoomCipher.open(key: key, sealed: Data(count: RoomCipher.overhead - 1), context: room) == nil)
    }

    @Test func anEmptyMessageStillSealsAndOpens() {
        let sealed = RoomCipher.seal(key: key, plaintext: Data(), context: room)
        #expect(RoomCipher.open(key: key, sealed: sealed, context: room) == Data())
    }

    // MARK: SealedText

    let context = SealedText.contextOf(roomId: 7, senderNodeNum: 42)

    /// The protocol layer mirrors this figure for the composer's byte count; they must never drift.
    @Test func theBudgetTheSenderPlansWithMatchesWhatSealingActuallyCosts() {
        #expect(SealedText.overhead == MessagePrivacy.sealedOverhead)
        let plaintext = Data(count: SealedText.maxTextBytes)
        #expect(SealedText.seal(key: key, plaintext: plaintext, context: context).count == MeshConstants.maxTextBytes)
    }

    @Test func aMemberReadsTheSealedMessageBack() {
        let sealed = SealedText.seal(key: key, plaintext: Data("meet at the north gate".utf8), context: context)
        #expect(
            SealedText.open(key: key, payload: sealed, context: context).map { String(decoding: $0, as: UTF8.self) }
                == "meet at the north gate")
    }

    @Test func someoneWithOnlyTheChannelKeyReadsNothingSealed() {
        let sealed = SealedText.seal(key: key, plaintext: Data("meet at the north gate".utf8), context: context)
        #expect(SealedText.open(key: other, payload: sealed, context: context) == nil)
    }

    @Test func aMessageCannotBeReattributedToAnotherSender() {
        let sealed = SealedText.seal(key: key, plaintext: Data("on my way".utf8), context: context)
        #expect(
            SealedText.open(key: key, payload: sealed, context: SealedText.contextOf(roomId: 7, senderNodeNum: 43))
                == nil)
    }

    @Test func aMessageCannotBeLiftedIntoAnotherRoom() {
        let sealed = SealedText.seal(key: key, plaintext: Data("on my way".utf8), context: context)
        #expect(
            SealedText.open(key: key, payload: sealed, context: SealedText.contextOf(roomId: 8, senderNodeNum: 42))
                == nil)
    }

    @Test func aVersionThisBuildDoesNotKnowIsRefusedNotMisread() {
        var sealed = SealedText.seal(key: key, plaintext: Data("hello".utf8), context: context)
        sealed[0] = 0x02
        #expect(SealedText.open(key: key, payload: sealed, context: context) == nil)
    }

    @Test func noiseOnThePortIsRefusedRatherThanCrashing() {
        #expect(SealedText.open(key: key, payload: Data(), context: context) == nil)
        #expect(SealedText.open(key: key, payload: Data([0x01]), context: context) == nil)
        #expect(SealedText.open(key: key, payload: Data(repeating: 0x01, count: 4), context: context) == nil)
    }

    @Test func otherAlphabetsSurviveTheRoundTrip() {
        let arabic = "نلتقي عند البوابة"
        let sealed = SealedText.seal(key: key, plaintext: Data(arabic.utf8), context: context)
        #expect(
            SealedText.open(key: key, payload: sealed, context: context).map { String(decoding: $0, as: UTF8.self) }
                == arabic)
    }

    @Test func aFullLengthMessageStillFitsThePayload() {
        let text = String(repeating: "x", count: SealedText.maxTextBytes)
        let sealed = SealedText.seal(key: key, plaintext: Data(text.utf8), context: context)
        #expect(sealed.count <= MeshConstants.maxTextBytes, "sealed to \(sealed.count) bytes")
        #expect(
            SealedText.open(key: key, payload: sealed, context: context).map { String(decoding: $0, as: UTF8.self) }
                == text)
    }

    @Test func sealingCostsTwentyNineBytesOfTheBudget() {
        #expect(SealedText.overhead == 29)
        #expect(SealedText.maxTextBytes == 171)
    }

    @Test func theSameWordsNeverSealTheSameWayTwice() {
        #expect(
            SealedText.seal(key: key, plaintext: Data("same".utf8), context: context)
                != SealedText.seal(key: key, plaintext: Data("same".utf8), context: context))
    }
}

extension Data {
    /// True when `needle` appears as a contiguous run (Android tests' `containsRun`).
    func containsRun(_ needle: Data) -> Bool {
        !needle.isEmpty && range(of: needle) != nil
    }
}
