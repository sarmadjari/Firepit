import CryptoKit
import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation
import Testing

@testable import FirepitCrypto

/// What the Android app's own crypto produced (Fixtures/android-vectors.json, written by its Kotlin code from random
/// keys), opened and reproduced here. If any of these fail, an iPhone and an Android phone cannot read each other.
@Suite("Android interop")
struct AndroidInteropTests {
    let vectors: [String: String]

    init() throws {
        guard
            let url = Bundle.module.url(
                forResource: "android-vectors", withExtension: "json",
                subdirectory: "Fixtures")
        else {
            throw CocoaError(.fileNoSuchFile)
        }
        vectors = try JSONDecoder().decode([String: String].self, from: Data(contentsOf: url))
    }

    private func bytes(_ key: String) throws -> Data {
        guard let value = vectors[key], let data = Data(hex: value) else { throw CocoaError(.fileReadCorruptFile) }
        return data
    }

    private func value(_ key: String) throws -> String {
        guard let value = vectors[key] else { throw CocoaError(.fileReadNoSuchFile) }
        return value
    }

    private let sender: Int32 = -1_181_562_854

    @Test func aRoomMessageSealedOnAndroidOpensOnIOS() throws {
        let context = try bytes("textContext")
        #expect(SealedText.contextOf(roomId: 0x0BAD_F00D, senderNodeNum: sender) == context)
        let roomKey = try bytes("roomKey")
        let sealedText = try bytes("sealedText")
        let textPlain = try bytes("textPlain")
        // Derived here from the room key alone: if the two apps moved keys on differently, it would not open.
        let generation = Int(try value("ratchetGeneration")) ?? -1
        let hour = Int(try value("ratchetHour")) ?? -1
        let hourKey = RoomRatchet.forward(
            key: roomKey, roomId: 0x0BAD_F00D, generation: generation,
            from: Int(try value("ratchetFromHour")) ?? -1, to: hour)
        let senderKey = RoomRatchet.senderKey(
            hourKey: try #require(hourKey), roomId: 0x0BAD_F00D, generation: generation, hour: hour, sender: sender)
        #expect(SealedText.hourTagOf(sealedText) == RoomRatchet.tagOf(hour))
        #expect(SealedText.open(key: senderKey, payload: sealedText, context: context) == textPlain)
        let roomCipherEmpty = try bytes("roomCipherEmpty")
        #expect(RoomCipher.open(key: roomKey, sealed: roomCipherEmpty, context: Data()) == Data())
    }

    @Test func aRoomKeySealedOnAndroidToThisPhoneOpens() throws {
        let joiner = try P256.KeyAgreement.PrivateKey(rawRepresentation: try bytes("joinerScalar"))
        let joinerPublic = try bytes("joinerPublic")
        #expect(KeyEnvelope.publicBytes(joiner.publicKey) == joinerPublic, "both platforms compress a point alike")
        let context = try bytes("envelopeContext")
        #expect(
            KeyEnvelope.contextOf(roomId: 0x0BAD_F00D, generation: 3, recipientNodeNum: -42, hour: 491_234) == context)
        let envelope = try bytes("envelope")
        let roomKey = try bytes("roomKey")
        #expect(
            KeyEnvelope.open(privateKey: joiner, ownPublic: joinerPublic, sealed: envelope, context: context) == roomKey
        )
    }

    @Test func androidsStoredPrivateKeyFormatImportsToo() throws {
        // Keys never travel between phones, but a PKCS#8 key from Android's keystore is readable here if ever needed.
        let fromPkcs8 = try P256.KeyAgreement.PrivateKey(derRepresentation: try bytes("joinerPkcs8"))
        let joinerPublic = try bytes("joinerPublic")
        #expect(KeyEnvelope.publicBytes(fromPkcs8.publicKey) == joinerPublic)
    }

    @Test func aDirectMessageSealedOnAndroidOpensForBothPhones() throws {
        let alice = try P256.KeyAgreement.PrivateKey(rawRepresentation: try bytes("aliceScalar"))
        let bob = try P256.KeyAgreement.PrivateKey(rawRepresentation: try bytes("bobScalar"))
        let alicePublic = try bytes("alicePublic")
        let bobPublic = try bytes("bobPublic")
        #expect(KeyEnvelope.publicBytes(alice.publicKey) == alicePublic)
        #expect(KeyEnvelope.publicBytes(bob.publicKey) == bobPublic)
        let context = try bytes("directContext")
        #expect(DirectSeal.contextOf(senderNodeNum: 11, recipientNodeNum: sender) == context)
        let sealed = try bytes("direct")
        let words = try bytes("directPlain")
        #expect(
            DirectSeal.open(
                ownPrivate: bob, ownPublic: bobPublic, peerPublic: alicePublic, sealed: sealed,
                context: context)?.plain == words)
        #expect(
            DirectSeal.open(
                ownPrivate: alice, ownPublic: alicePublic, peerPublic: bobPublic, sealed: sealed,
                context: context)?.plain == words)
        let room = DirectSeal.RoomSecret(
            roomId: Int32(try value("directRoomId")) ?? 0,
            generation: Int(try value("directRoomGeneration")) ?? 0,
            hour: Int(try value("directRoomHour")) ?? 0,
            key: try bytes("directRoomHourly")
        )
        #expect(
            DirectSeal.open(
                ownPrivate: bob, ownPublic: bobPublic, peerPublic: alicePublic, sealed: try bytes("directV2"),
                context: context, rooms: [room])?.plain == words)
    }

    @Test func inviteKeysAndQRTokensAreIdentical() throws {
        let inviteKey = RoomCrypto.inviteKey(roomPsk: try bytes("psk"), roomId: 0x0BAD_F00D, generation: 3)
        let expectedInviteKey = try bytes("inviteKey")
        #expect(inviteKey == expectedInviteKey)
        let window = RoomCrypto.windowFor(epochMillis: 1_789_000_000_123)
        let expectedWindow = try value("window")
        let expectedNegativeWindow = try value("negativeWindow")
        let expectedToken = try bytes("token")
        #expect(String(window) == expectedWindow)
        #expect(String(RoomCrypto.windowFor(epochMillis: -1)) == expectedNegativeWindow)
        #expect(RoomCrypto.token(inviteKey: inviteKey, inviterNodeNum: sender, window: window) == expectedToken)
    }

    @Test func anAndroidInviteDecodesAndReencodesToTheSameCode() throws {
        let uri = try value("inviteUri")
        guard let invite = InviteCodec.decode(uri) else {
            Issue.record("Android invite should decode")
            return
        }
        #expect(invite.roomID == 0x0BAD_F00D)
        #expect(invite.roomName == "Camp 🔥")
        #expect(invite.generation == 3)
        #expect(invite.lora.usePreset)
        #expect(invite.lora.modemPreset == .longFast)
        #expect(invite.lora.region == .eu868)
        #expect(invite.lora.hopLimit == 3)
        #expect(invite.lora.meshMode == .publicRelay)
        #expect(Int32(bitPattern: invite.inviter.nodeNum) == sender)
        #expect(invite.inviter.user.longName == "Sam نلتقي")
        #expect(invite.inviter.user.shortName == "SA")
        #expect(invite.inviter.user.hwModel == .tEcho)
        #expect(invite.inviteID == 0x1234_5678)
        #expect(invite.issuedAt == 1_789_000_000)
        let token = try bytes("token")
        #expect(invite.token == token)
        #expect(InviteCodec.encode(invite) == uri, "iOS writes the identical code for the same invite")
        guard case .firepit = CodeScanner.classify(uri) else {
            Issue.record("an Android invite must classify as Firepit")
            return
        }
    }

    @Test func aMeshtasticLinkFromAndroidDecodes() throws {
        let channelLink = try value("channelLink")
        guard let shared = ChannelUrl.decode(channelLink) else {
            Issue.record("Android channel link should decode")
            return
        }
        #expect(shared.channels.map(\.name) == ["LongFast", "Friends"])
        #expect(shared.channels.map(\.psk.count) == [1, 16])
        #expect(shared.channels[1].id == 99)
        #expect(shared.lora?.modemPreset == .mediumFast)
        #expect(shared.lora?.hopLimit == 4)
        #expect(!shared.addOnly)
    }

    @Test func roomIconSeedsMatch() throws {
        let names = try value("seedNames").split(separator: ",", omittingEmptySubsequences: false)
            .map { String(decoding: Data(hex: String($0)) ?? Data(), as: UTF8.self) }
        let seeds = try value("seeds").split(separator: ",").map { Int32($0) }
        #expect(names.count == seeds.count)
        for (name, seed) in zip(names, seeds) {
            #expect(RoomChannel.seed(of: name) == seed, "seed of \"\(name)\"")
        }
    }
}

extension Data {
    /// Lowercase or uppercase hex, two digits per byte; nil for anything else.
    init?(hex: String) {
        guard hex.count.isMultiple(of: 2) else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self.init(bytes)
    }
}
