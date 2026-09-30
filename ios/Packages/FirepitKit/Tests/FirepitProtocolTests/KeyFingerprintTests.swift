import Foundation
import Testing

@testable import FirepitProtocol

@Suite struct KeyFingerprintTests {
    private func keyOf(_ seed: Int) -> String {
        Data((0..<32).map { UInt8(($0 + seed) & 0xff) }).base64EncodedString()
    }

    private var key: String { keyOf(0) }

    @Test func readsAsThreeGroupsOfFour() throws {
        let fingerprint = try #require(KeyFingerprint.of(publicKeyBase64: key))
        #expect(fingerprint.wholeMatch(of: /[0-9A-F]{4} [0-9A-F]{4} [0-9A-F]{4}/) != nil)
    }

    @Test func theSameKeyAlwaysGivesTheSameFingerprint() {
        #expect(KeyFingerprint.of(publicKeyBase64: key) == KeyFingerprint.of(publicKeyBase64: key))
    }

    @Test func aDifferentKeyGivesADifferentFingerprint() {
        #expect(KeyFingerprint.of(publicKeyBase64: key) != KeyFingerprint.of(publicKeyBase64: keyOf(1)))
    }

    @Test func aNodeThatHasSharedNoKeyHasNoFingerprintToShow() {
        #expect(KeyFingerprint.of(publicKeyBase64: nil) == nil)
        #expect(KeyFingerprint.of(publicKeyBase64: "") == nil)
        #expect(KeyFingerprint.of(publicKeyBase64: "   ") == nil)
    }

    @Test func nonsenseIsRefusedRatherThanShownAsAFingerprint() {
        #expect(KeyFingerprint.of(publicKeyBase64: "not base64 at all!!") == nil)
    }

    @Test func aJoinLineReadsLikeAnyOtherFingerprint() throws {
        let line = try #require(
            KeyFingerprint.ofJoin(radioKey: Data(repeating: 1, count: 32), phoneKey: Data(repeating: 2, count: 33)))
        #expect(line.wholeMatch(of: /[0-9A-F]{4} [0-9A-F]{4} [0-9A-F]{4}/) != nil)
    }

    @Test func aDifferentPhoneBehindTheSameRadioReadsDifferently() {
        let radio = Data(repeating: 1, count: 32)
        #expect(
            KeyFingerprint.ofJoin(radioKey: radio, phoneKey: Data(repeating: 2, count: 33))
                != KeyFingerprint.ofJoin(radioKey: radio, phoneKey: Data(repeating: 3, count: 33)))
    }

    @Test func bothPhonesComputeTheSameJoinLine() {
        let radio = Data((0..<32).map { UInt8(($0 * 3) & 0xff) })
        let phone = Data((0..<33).map { UInt8(($0 * 5) & 0xff) })
        #expect(
            KeyFingerprint.ofJoin(radioKey: radio, phoneKey: phone)
                == KeyFingerprint.ofJoin(radioKey: Data(radio), phoneKey: Data(phone)))
    }

    @Test func aJoinLineIsNeverAPlainKeysLine() {
        let radio = Data(repeating: 7, count: 32)
        #expect(
            KeyFingerprint.of(publicKeyBase64: radio.base64EncodedString())
                != KeyFingerprint.ofJoin(radioKey: radio, phoneKey: Data(repeating: 7, count: 33)))
    }

    @Test func aJoinLineNeedsBothKeys() {
        #expect(KeyFingerprint.ofJoin(radioKey: Data(), phoneKey: Data(repeating: 0, count: 33)) == nil)
        #expect(KeyFingerprint.ofJoin(radioKey: Data(repeating: 0, count: 32), phoneKey: Data()) == nil)
    }
}
