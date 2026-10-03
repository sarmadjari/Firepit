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

    let hour = 491_234
    let context = SealedText.contextOf(roomId: 7, senderNodeNum: 42)

    private func sealed(_ words: String, under key: Data? = nil) -> Data {
        SealedText.seal(key: key ?? self.key, hour: hour, plaintext: Data(words.utf8), context: context)
    }

    private func opened(_ payload: Data, under key: Data? = nil, context: Data? = nil) -> String? {
        SealedText.open(key: key ?? self.key, payload: payload, context: context ?? self.context)
            .map { String(decoding: $0, as: UTF8.self) }
    }

    /// The protocol layer mirrors this figure for the composer's byte count; they must never drift.
    @Test func theBudgetTheSenderPlansWithMatchesWhatSealingActuallyCosts() {
        #expect(SealedText.overhead == MessagePrivacy.sealedOverhead)
        let plaintext = Data(count: SealedText.maxTextBytes)
        #expect(
            SealedText.seal(key: key, hour: hour, plaintext: plaintext, context: context).count
                == MeshConstants.maxTextBytes)
    }

    @Test func aMemberReadsTheSealedMessageBack() {
        #expect(opened(sealed("meet at the north gate")) == "meet at the north gate")
    }

    @Test func someoneWithOnlyTheChannelKeyReadsNothingSealed() {
        #expect(opened(sealed("meet at the north gate"), under: other) == nil)
    }

    @Test func aMessageCannotBeReattributedToAnotherSender() {
        #expect(opened(sealed("on my way"), context: SealedText.contextOf(roomId: 7, senderNodeNum: 43)) == nil)
    }

    @Test func aMessageCannotBeLiftedIntoAnotherRoom() {
        #expect(opened(sealed("on my way"), context: SealedText.contextOf(roomId: 8, senderNodeNum: 42)) == nil)
    }

    @Test func theNonceSaysWhichHourSealedItAndNothingElseDoes() {
        let payload = sealed("hello")
        #expect(SealedText.hourTagOf(payload) == RoomRatchet.tagOf(hour))
        #expect(SealedText.nonceOf(payload) == payload.subdata(in: 1..<(1 + RoomCipher.nonceSize)))
    }

    @Test func changingTheHourItClaimsBreaksTheSeal() {
        var payload = sealed("hello")
        payload[2] &+= 1
        #expect(opened(payload) == nil)
    }

    @Test func aVersionThisBuildDoesNotKnowIsRefusedNotMisread() {
        var payload = sealed("hello")
        payload[0] = 0x03
        #expect(opened(payload) == nil)
        #expect(SealedText.hourTagOf(payload) == nil)
    }

    @Test func theFirstFormatUnderAKeyThatNeverChangedIsNoLongerRead() {
        let first = Data([0x01]) + RoomCipher.seal(key: key, plaintext: Data("hello".utf8), context: context)
        #expect(opened(first) == nil)
        // Recognised, so the room can say who needs to update.
        #expect(SealedText.isFirstFormat(first))
        #expect(!SealedText.isFirstFormat(sealed("hello")))
        #expect(!SealedText.isFirstFormat(Data([0x01, 0x02])))
    }

    @Test func noiseOnThePortIsRefusedRatherThanCrashing() {
        #expect(SealedText.open(key: key, payload: Data(), context: context) == nil)
        #expect(SealedText.open(key: key, payload: Data([0x02]), context: context) == nil)
        #expect(SealedText.open(key: key, payload: Data(repeating: 0x02, count: 4), context: context) == nil)
        #expect(SealedText.hourTagOf(Data(repeating: 0x02, count: SealedText.overhead - 1)) == nil)
    }

    @Test func otherAlphabetsSurviveTheRoundTrip() {
        let arabic = "نلتقي عند البوابة"
        #expect(opened(sealed(arabic)) == arabic)
    }

    @Test func aFullLengthMessageStillFitsThePayload() {
        let text = String(repeating: "x", count: SealedText.maxTextBytes)
        let payload = sealed(text)
        #expect(payload.count <= MeshConstants.maxTextBytes, "sealed to \(payload.count) bytes")
        #expect(opened(payload) == text)
    }

    @Test func sealingCostsTwentyNineBytesOfTheBudgetAsItAlwaysHas() {
        #expect(SealedText.overhead == 29)
        #expect(SealedText.maxTextBytes == 171)
    }

    @Test func theSameWordsNeverSealTheSameWayTwice() {
        #expect(sealed("same") != sealed("same"))
    }
}

extension Data {
    /// True when `needle` appears as a contiguous run (Android tests' `containsRun`).
    func containsRun(_ needle: Data) -> Bool {
        !needle.isEmpty && range(of: needle) != nil
    }
}
