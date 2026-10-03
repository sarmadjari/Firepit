import CryptoKit
import Foundation
import Testing

@testable import FirepitCrypto

/// Ported from android/core/crypto KeyEnvelopeTest and DirectSealTest: the layers that keep a room's key and one
/// person's words away from the radios they pass through.
@Suite("Phone seals")
struct PhoneSealTests {
    // MARK: KeyEnvelope

    let joiner = KeyEnvelope.generateKeyPair()
    var joinerPublic: Data { KeyEnvelope.publicBytes(joiner.publicKey) }
    let roomKey = RoomCipher.generateKey()
    let context = KeyEnvelope.contextOf(roomId: 0x0BAD_F00D, generation: 3, recipientNodeNum: 42, hour: 491_234)

    @Test func thePhoneItWasSealedToOpensIt() throws {
        let sealed = try KeyEnvelope.seal(recipient: joinerPublic, secret: roomKey, context: context)
        #expect(sealed.count == KeyEnvelope.sealedSize)
        #expect(
            KeyEnvelope.open(privateKey: joiner, ownPublic: joinerPublic, sealed: sealed, context: context) == roomKey)
    }

    @Test func anyOtherPhoneCannot() throws {
        let other = KeyEnvelope.generateKeyPair()
        let sealed = try KeyEnvelope.seal(recipient: joinerPublic, secret: roomKey, context: context)
        #expect(
            KeyEnvelope.open(
                privateKey: other, ownPublic: KeyEnvelope.publicBytes(other.publicKey), sealed: sealed,
                context: context) == nil)
        #expect(KeyEnvelope.open(privateKey: other, ownPublic: joinerPublic, sealed: sealed, context: context) == nil)
    }

    @Test func aKeySealedForOneRoomGenerationPersonOrHourOpensForNoOther() throws {
        let sealed = try KeyEnvelope.seal(recipient: joinerPublic, secret: roomKey, context: context)
        for elsewhere in [
            KeyEnvelope.contextOf(roomId: 0x0BAD_F00E, generation: 3, recipientNodeNum: 42, hour: 491_234),
            KeyEnvelope.contextOf(roomId: 0x0BAD_F00D, generation: 4, recipientNodeNum: 42, hour: 491_234),
            KeyEnvelope.contextOf(roomId: 0x0BAD_F00D, generation: 3, recipientNodeNum: 43, hour: 491_234),
            // Relabelled as an earlier hour, it would let the holder derive keys for hours they were never given.
            KeyEnvelope.contextOf(roomId: 0x0BAD_F00D, generation: 3, recipientNodeNum: 42, hour: 491_233),
        ] {
            #expect(
                KeyEnvelope.open(privateKey: joiner, ownPublic: joinerPublic, sealed: sealed, context: elsewhere) == nil
            )
        }
    }

    @Test func aChangedByteAnywhereInAnEnvelopeIsRefused() throws {
        let sealed = try KeyEnvelope.seal(recipient: joinerPublic, secret: roomKey, context: context)
        for index in sealed.indices {
            var tampered = sealed
            tampered[index] ^= 0x01
            #expect(
                KeyEnvelope.open(privateKey: joiner, ownPublic: joinerPublic, sealed: tampered, context: context)
                    == nil,
                "byte \(index)")
        }
    }

    @Test func twoSealsOfTheSameKeyLookUnrelated() throws {
        #expect(
            try KeyEnvelope.seal(recipient: joinerPublic, secret: roomKey, context: context)
                != KeyEnvelope.seal(recipient: joinerPublic, secret: roomKey, context: context))
    }

    @Test func aStoredPrivateKeyStillOpensWhatWasSealedToIt() throws {
        let restored = try #require(KeyEnvelope.restorePrivate(KeyEnvelope.privateBytes(joiner)))
        let sealed = try KeyEnvelope.seal(recipient: joinerPublic, secret: roomKey, context: context)
        #expect(
            KeyEnvelope.open(privateKey: restored, ownPublic: joinerPublic, sealed: sealed, context: context) == roomKey
        )
    }

    @Test func publicKeysAreCompressedPoints() {
        #expect(joinerPublic.count == KeyEnvelope.publicKeySize)
        #expect(joinerPublic.first == 0x02 || joinerPublic.first == 0x03)
        #expect(KeyEnvelope.isValidPublicKey(joinerPublic))
    }

    /// ProtocolContractTests restates these to size packets, since it cannot see this module.
    @Test func theSizesThePacketBudgetsArePlannedWith() {
        #expect(KeyEnvelope.publicKeySize == 33)
        #expect(KeyEnvelope.sealedSize == 93)
    }

    /// An x with no point above it is exactly what an invalid-curve attack sends. x = 1 was found with Euler's
    /// criterion
    /// (x³ − 3x + b is a non-residue mod p), independently of the code under test — the same value the Android test's
    /// search lands on.
    @Test func anXCoordinateThatIsNotOnTheCurveIsRefused() {
        let offCurve = Data([0x02]) + Data(count: 31) + Data([0x01])
        #expect(!KeyEnvelope.isValidPublicKey(offCurve))
        #expect(
            KeyEnvelope.open(
                privateKey: joiner, ownPublic: joinerPublic, sealed: offCurve + Data(count: 60),
                context: context) == nil)
    }

    @Test func anythingThatIsNotACompressedPointIsRefused() {
        var uncompressedPrefix = joinerPublic
        uncompressedPrefix[0] = 0x04
        #expect(!KeyEnvelope.isValidPublicKey(Data()))
        #expect(!KeyEnvelope.isValidPublicKey(Data(count: 32)))
        #expect(!KeyEnvelope.isValidPublicKey(uncompressedPrefix))
        #expect(!KeyEnvelope.isValidPublicKey(Data([0x02]) + Data(repeating: 0xFF, count: 32)))
        #expect(!KeyEnvelope.isValidPublicKey(joinerPublic + Data([0])))
    }

    @Test func tooShortToHoldASealedKeyIsRefusedRatherThanThrown() {
        #expect(
            KeyEnvelope.open(privateKey: joiner, ownPublic: joinerPublic, sealed: Data(count: 10), context: context)
                == nil)
    }

    @Test func sealingToAnUnusableKeyThrowsRatherThanCrashing() {
        #expect(throws: FirepitCryptoError.unusablePublicKey) {
            try KeyEnvelope.seal(recipient: Data(count: 33), secret: roomKey, context: context)
        }
    }

    // MARK: DirectSeal

    let alice = KeyEnvelope.generateKeyPair()
    var alicePublic: Data { KeyEnvelope.publicBytes(alice.publicKey) }
    let bob = KeyEnvelope.generateKeyPair()
    var bobPublic: Data { KeyEnvelope.publicBytes(bob.publicKey) }
    let words = Data("Meet at the ridge at six".utf8)
    let aliceToBob = DirectSeal.contextOf(senderNodeNum: 11, recipientNodeNum: 22)

    private func sealAliceToBob() throws -> Data {
        try DirectSeal.seal(
            ownPrivate: alice, ownPublic: alicePublic, peerPublic: bobPublic, plaintext: words,
            context: aliceToBob)
    }

    @Test func thePhoneADirectMessageWasSealedForOpensIt() throws {
        let sealed = try sealAliceToBob()
        #expect(sealed.count == words.count + DirectSeal.overhead)
        #expect(
            DirectSeal.open(
                ownPrivate: bob, ownPublic: bobPublic, peerPublic: alicePublic, sealed: sealed,
                context: aliceToBob) == words)
    }

    @Test func theSenderCanReadBackWhatItSealed() throws {
        let sealed = try sealAliceToBob()
        #expect(
            DirectSeal.open(
                ownPrivate: alice, ownPublic: alicePublic, peerPublic: bobPublic, sealed: sealed,
                context: aliceToBob) == words)
    }

    @Test func anyThirdPhoneCannot() throws {
        let mallory = KeyEnvelope.generateKeyPair()
        let malloryPublic = KeyEnvelope.publicBytes(mallory.publicKey)
        let sealed = try sealAliceToBob()
        #expect(
            DirectSeal.open(
                ownPrivate: mallory, ownPublic: malloryPublic, peerPublic: alicePublic, sealed: sealed,
                context: aliceToBob) == nil)
        #expect(
            DirectSeal.open(
                ownPrivate: mallory, ownPublic: bobPublic, peerPublic: alicePublic, sealed: sealed,
                context: aliceToBob) == nil)
    }

    @Test func nobodyButTheSendersPhoneCanSealAsThem() throws {
        // Mallory knows both public keys, which are no secret, and seals to Bob claiming to be Alice.
        let mallory = KeyEnvelope.generateKeyPair()
        let forged = try DirectSeal.seal(
            ownPrivate: mallory, ownPublic: KeyEnvelope.publicBytes(mallory.publicKey),
            peerPublic: bobPublic, plaintext: words, context: aliceToBob)
        #expect(
            DirectSeal.open(
                ownPrivate: bob, ownPublic: bobPublic, peerPublic: alicePublic, sealed: forged,
                context: aliceToBob) == nil)
    }

    @Test func aMessageCannotBeTurnedRoundOrReaddressed() throws {
        let sealed = try sealAliceToBob()
        for elsewhere in [
            DirectSeal.contextOf(senderNodeNum: 22, recipientNodeNum: 11),
            DirectSeal.contextOf(senderNodeNum: 11, recipientNodeNum: 23),
            DirectSeal.contextOf(senderNodeNum: 12, recipientNodeNum: 22),
        ] {
            #expect(
                DirectSeal.open(
                    ownPrivate: bob, ownPublic: bobPublic, peerPublic: alicePublic, sealed: sealed,
                    context: elsewhere) == nil)
        }
    }

    @Test func aChangedByteAnywhereInADirectSealIsRefused() throws {
        let sealed = try sealAliceToBob()
        for index in sealed.indices {
            var tampered = sealed
            tampered[index] ^= 0x01
            #expect(
                DirectSeal.open(
                    ownPrivate: bob, ownPublic: bobPublic, peerPublic: alicePublic, sealed: tampered,
                    context: aliceToBob) == nil, "byte \(index)")
        }
    }

    @Test func theSameWordsNeverSealTheSameWayTwiceDirectly() throws {
        #expect(try sealAliceToBob() != sealAliceToBob())
    }

    @Test func aPeerKeyThatIsNotAUsablePointIsRefusedRatherThanUsed() throws {
        // An x at or above the field prime has no point at all.
        let notAPoint = Data([0x02]) + Data(repeating: 0xFF, count: 32)
        let sealed = try sealAliceToBob()
        #expect(
            DirectSeal.open(
                ownPrivate: bob, ownPublic: bobPublic, peerPublic: notAPoint, sealed: sealed,
                context: aliceToBob) == nil)
    }

    @Test func aTruncatedOrUnversionedPayloadIsRefused() throws {
        let sealed = try sealAliceToBob()
        #expect(
            DirectSeal.open(
                ownPrivate: bob, ownPublic: bobPublic, peerPublic: alicePublic,
                sealed: sealed.prefix(DirectSeal.overhead), context: aliceToBob) == nil)
        #expect(
            DirectSeal.open(
                ownPrivate: bob, ownPublic: bobPublic, peerPublic: alicePublic,
                sealed: Data([0x02]) + sealed.dropFirst(), context: aliceToBob) == nil)
    }

    @Test func bothPhonesOrderTheirKeysAlike() {
        #expect(DirectSeal.ordered(alicePublic, bobPublic) == DirectSeal.ordered(bobPublic, alicePublic))
        #expect(DirectSeal.ordered(Data([1, 2]), Data([1, 2, 0])) == Data([1, 2, 1, 2, 0]))
        #expect(DirectSeal.ordered(Data([0x80]), Data([0x7F])) == Data([0x7F, 0x80]))
    }
}
