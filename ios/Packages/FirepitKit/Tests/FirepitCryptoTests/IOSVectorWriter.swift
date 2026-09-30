import CryptoKit
import FirepitProtocol
import FirepitProtos
import Foundation
import Testing

@testable import FirepitCrypto

/// The reverse direction of `AndroidInteropTests`: what iOS seals and encodes, written out for the Android app's own
/// Kotlin code to open (scripts/check-android-interop.sh runs both sides). Writes only when
/// `FIREPIT_IOS_VECTORS_OUT` names a file, so ordinary test runs do nothing here.
@Suite("iOS vectors for Android")
struct IOSVectorWriter {
    @Test func writeVectorsForTheAndroidVerifier() throws {
        guard let out = ProcessInfo.processInfo.environment["FIREPIT_IOS_VECTORS_OUT"] else { return }
        var fields: [String: String] = [:]
        let sender: Int32 = -1_181_562_854

        let roomKey = Data((0..<32).map { UInt8(truncatingIfNeeded: $0 &* 5 &+ 3) })
        let text = Data("iPhone to Android — من الآيفون 🔥".utf8)
        let textContext = SealedText.contextOf(roomId: 0x0BAD_F00D, senderNodeNum: sender)
        fields["roomKey"] = roomKey.hex
        fields["textPlain"] = text.hex
        fields["textContext"] = textContext.hex
        fields["sealedText"] = SealedText.seal(key: roomKey, plaintext: text, context: textContext).hex

        let joiner = P256.KeyAgreement.PrivateKey()
        let joinerPublic = KeyEnvelope.publicBytes(joiner.publicKey)
        let envelopeContext = KeyEnvelope.contextOf(roomId: 0x0BAD_F00D, generation: 3, recipientNodeNum: -42)
        fields["joinerScalar"] = joiner.rawRepresentation.hex
        fields["joinerPublic"] = joinerPublic.hex
        fields["envelopeContext"] = envelopeContext.hex
        fields["envelope"] = try KeyEnvelope.seal(recipient: joinerPublic, secret: roomKey, context: envelopeContext)
            .hex

        let alice = P256.KeyAgreement.PrivateKey()
        let bob = P256.KeyAgreement.PrivateKey()
        let alicePublic = KeyEnvelope.publicBytes(alice.publicKey)
        let bobPublic = KeyEnvelope.publicBytes(bob.publicKey)
        let words = Data("Meet at the ridge at six".utf8)
        let directContext = DirectSeal.contextOf(senderNodeNum: 11, recipientNodeNum: sender)
        fields["aliceScalar"] = alice.rawRepresentation.hex
        fields["alicePublic"] = alicePublic.hex
        fields["bobScalar"] = bob.rawRepresentation.hex
        fields["bobPublic"] = bobPublic.hex
        fields["directContext"] = directContext.hex
        fields["directPlain"] = words.hex
        fields["direct"] = try DirectSeal.seal(
            ownPrivate: alice, ownPublic: alicePublic, peerPublic: bobPublic,
            plaintext: words, context: directContext
        ).hex

        let psk = Data((0..<32).map { UInt8(truncatingIfNeeded: 200 &- $0) })
        let inviteKey = RoomCrypto.inviteKey(roomPsk: psk, roomId: 0x0BAD_F00D, generation: 3)
        let window = RoomCrypto.windowFor(epochMillis: 1_789_000_000_123)
        let token = RoomCrypto.token(inviteKey: inviteKey, inviterNodeNum: sender, window: window)
        fields["psk"] = psk.hex
        fields["inviteKey"] = inviteKey.hex
        fields["window"] = String(window)
        fields["token"] = token.hex

        let invite = Meshchat_Invite.with {
            $0.version = InviteCodec.version
            $0.roomID = 0x0BAD_F00D
            $0.roomName = "Trail 🥾"
            $0.generation = 3
            $0.lora = Meshchat_LoRaProfile.with {
                $0.usePreset = true
                $0.modemPreset = .mediumFast
                $0.region = .us
                $0.hopLimit = 4
                $0.meshMode = .groupOnly
            }
            $0.inviter = Meshchat_Inviter.with {
                $0.nodeNum = UInt32(bitPattern: sender)
                $0.user = User.with {
                    $0.id = "!b992a91a"
                    $0.longName = "Lena من"
                    $0.shortName = "LE"
                    $0.publicKey = Data(repeating: 9, count: 32)
                    $0.hwModel = .rak4631
                }
            }
            $0.inviteID = 0x0F0F_1234
            $0.issuedAt = 1_789_000_000
            $0.window = UInt32(bitPattern: window)
            $0.token = token
        }
        fields["inviteUri"] = InviteCodec.encode(invite)

        let json = try JSONSerialization.data(
            withJSONObject: fields,
            options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
        try json.write(to: URL(fileURLWithPath: out))
    }
}

extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
