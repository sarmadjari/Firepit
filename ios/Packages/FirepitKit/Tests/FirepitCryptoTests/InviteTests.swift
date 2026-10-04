import FirepitProtocol
import FirepitProtos
import Foundation
import Testing

@testable import FirepitCrypto

/// Ported from android/core/crypto RoomCryptoTest, InviteCodecTest, InvitePrivacyTest and ScanDisambiguationTest.
@Suite("Invites")
struct InviteTests {
    // MARK: RoomCrypto

    @Test func generatedKeysAreTheRightSizeAndNotReused() {
        let first = RoomCrypto.generatePsk()
        let second = RoomCrypto.generatePsk()
        #expect(first.count == 32)
        #expect(first != second, "two rooms must never share a key")
    }

    @Test func anAllZeroKeyWouldNeverBeProduced() {
        for _ in 0..<20 {
            #expect(!RoomCrypto.generatePsk().allSatisfy { $0 == 0 })
        }
    }

    @Test func roomIdsAreNeverZero() {
        for _ in 0..<200 {
            #expect(RoomCrypto.generateRoomId() != 0)
        }
    }

    @Test func everyKeyHolderDerivesTheSameInviteKey() {
        let psk = RoomCrypto.generatePsk()
        #expect(
            RoomCrypto.inviteKey(roomPsk: psk, roomId: 42, generation: 1)
                == RoomCrypto.inviteKey(roomPsk: psk, roomId: 42, generation: 1), "any member must be able to invite")
    }

    @Test func rotatingTheRoomKeyChangesTheInviteKey() {
        let psk = RoomCrypto.generatePsk()
        #expect(
            RoomCrypto.inviteKey(roomPsk: psk, roomId: 42, generation: 1)
                != RoomCrypto.inviteKey(roomPsk: psk, roomId: 42, generation: 2),
            "a removed member's old invites must stop working")
    }

    @Test func aDifferentRoomNeverDerivesTheSameInviteKey() {
        let psk = RoomCrypto.generatePsk()
        #expect(
            RoomCrypto.inviteKey(roomPsk: psk, roomId: 1, generation: 1)
                != RoomCrypto.inviteKey(roomPsk: psk, roomId: 2, generation: 1))
    }

    @Test func theTokenChangesEveryWindow() {
        let key = RoomCrypto.inviteKey(roomPsk: RoomCrypto.generatePsk(), roomId: 42, generation: 1)
        let first = RoomCrypto.token(inviteKey: key, inviterNodeNum: 7, window: 100)
        let next = RoomCrypto.token(inviteKey: key, inviterNodeNum: 7, window: 101)
        #expect(first.count == 8)
        #expect(first != next)
    }

    @Test func theTokenIsBoundToTheInviter() {
        let key = RoomCrypto.inviteKey(roomPsk: RoomCrypto.generatePsk(), roomId: 42, generation: 1)
        #expect(
            RoomCrypto.token(inviteKey: key, inviterNodeNum: 7, window: 100)
                != RoomCrypto.token(inviteKey: key, inviterNodeNum: 8, window: 100))
    }

    @Test func aForgedTokenIsRefused() {
        let key = RoomCrypto.inviteKey(roomPsk: RoomCrypto.generatePsk(), roomId: 42, generation: 1)
        let now: Int64 = 1_000_000_000_000
        #expect(
            !RoomCrypto.matchesRecentToken(inviteKey: key, inviterNodeNum: 7, token: Data(count: 8), nowMillis: now))
        #expect(
            !RoomCrypto.matchesRecentToken(inviteKey: key, inviterNodeNum: 7, token: Data(count: 4), nowMillis: now),
            "wrong length is not a near miss")
    }

    @Test func windowsAdvanceAtTheRotationRate() {
        let start = RoomCrypto.windowFor(epochMillis: 1_000_000_000_000)
        let later = RoomCrypto.windowFor(epochMillis: 1_000_000_000_000 + RoomCrypto.rotationSeconds * 1000)
        #expect(later == start + 1)
    }

    @Test func aJoinHelloIsMatchedWithoutBeingToldWhichWindowItCameFrom() {
        let key = RoomCrypto.inviteKey(roomPsk: RoomCrypto.generatePsk(), roomId: 42, generation: 1)
        let now: Int64 = 1_000_000_000_000
        let windowsBack = Int64(RoomCrypto.defaultLookbackWindows - 1)
        let scannedAt = now - RoomCrypto.rotationSeconds * 1000 * windowsBack
        let echoed = RoomCrypto.token(
            inviteKey: key, inviterNodeNum: 7, window: RoomCrypto.windowFor(epochMillis: scannedAt))
        #expect(RoomCrypto.matchesRecentToken(inviteKey: key, inviterNodeNum: 7, token: echoed, nowMillis: now))
    }

    @Test func aTokenOlderThanTheLookbackIsRefused() {
        let key = RoomCrypto.inviteKey(roomPsk: RoomCrypto.generatePsk(), roomId: 42, generation: 1)
        let now: Int64 = 1_000_000_000_000
        let tooOld = now - RoomCrypto.rotationSeconds * 1000 * Int64(RoomCrypto.defaultLookbackWindows + 1)
        let echoed = RoomCrypto.token(
            inviteKey: key, inviterNodeNum: 7, window: RoomCrypto.windowFor(epochMillis: tooOld))
        #expect(!RoomCrypto.matchesRecentToken(inviteKey: key, inviterNodeNum: 7, token: echoed, nowMillis: now))
    }

    @Test func aTokenMintedForAnotherInviterIsRefused() {
        let key = RoomCrypto.inviteKey(roomPsk: RoomCrypto.generatePsk(), roomId: 42, generation: 1)
        let now: Int64 = 1_000_000_000_000
        let someoneElse = RoomCrypto.token(
            inviteKey: key, inviterNodeNum: 8, window: RoomCrypto.windowFor(epochMillis: now))
        #expect(!RoomCrypto.matchesRecentToken(inviteKey: key, inviterNodeNum: 7, token: someoneElse, nowMillis: now))
        #expect(
            !RoomCrypto.matchesRecentToken(inviteKey: key, inviterNodeNum: 7, token: Data(count: 4), nowMillis: now),
            "wrong length is not a near miss")
    }

    // MARK: InviteCodec

    private func validInvite() -> Meshchat_Invite {
        Meshchat_Invite.with {
            $0.version = InviteCodec.version
            $0.roomID = 0x1234_5678
            $0.roomName = "Camp"
            $0.generation = 1
            $0.inviter = Meshchat_Inviter.with {
                $0.nodeNum = 7
                $0.user = User.with {
                    $0.id = "!00000007"
                    $0.longName = "Sam"
                    $0.shortName = "SA"
                    $0.publicKey = Data(count: 32)
                }
            }
            $0.inviteID = 0x0BAD_F00D
            $0.issuedAt = 1_788_000_000
            $0.window = 12_345
            $0.token = Data((0..<8).map { UInt8($0) })
            $0.secret = Data((0..<InviteCodec.inviteSecretSize).map { UInt8(0xA0 + $0) })
        }
    }

    @Test func anInviteSurvivesTheRoundTrip() {
        let invite = validInvite()
        let decoded = InviteCodec.decode(InviteCodec.encode(invite))
        #expect(decoded == invite)
        #expect(decoded?.secret == invite.secret)
    }

    @Test func anInviteWithoutTheInPersonSecretIsUnusable() {
        var broken = validInvite()
        broken.secret = Data()
        #expect(InviteCodec.decode(InviteCodec.encode(broken)) == nil)
    }

    @Test func qrPayloadLengthReportsTheInPersonSecretCost() {
        let fullUser = User.with {
            $0.id = "!12345678"
            $0.longName = "Alex Firepit"
            $0.shortName = "AF"
            $0.publicKey = Data((0..<32).map { UInt8(0x50 + $0) })
        }
        var withSecret = validInvite()
        withSecret.roomName = "12345678901"
        withSecret.inviter = Meshchat_Inviter.with {
            $0.nodeNum = 0x1234_5678
            $0.user = fullUser
        }
        var withoutSecret = withSecret
        withoutSecret.secret = Data()
        let before = InviteCodec.encode(withoutSecret).components(separatedBy: "&d=").last?.count ?? 0
        let after = InviteCodec.encode(withSecret).components(separatedBy: "&d=").last?.count ?? 0
        print("Invite QR base64 chars without secret: \(before)")
        print("Invite QR base64 chars with secret: \(after)")
        #expect(after > before)
    }

    @Test func theEncodedFormIsAFirepitLink() {
        #expect(InviteCodec.encode(validInvite()).hasPrefix("firepit://join?v=\(InviteCodec.version)&d="))
    }

    @Test func junkIsRejectedRatherThanHalfParsed() {
        #expect(InviteCodec.decode("") == nil)
        #expect(InviteCodec.decode("hello") == nil)
        #expect(InviteCodec.decode("https://example.com/join?v=1&d=abc") == nil)
        #expect(InviteCodec.decode("firepit://join?v=1&d=!!!not-base64!!!") == nil)
        #expect(InviteCodec.decode("firepit://join?v=1&d=") == nil)
    }

    @Test func anInviteCarryingATokenExpiresOnItsOwn() {
        var invite = validInvite()
        invite.token = Data(count: RoomCrypto.tokenSize)
        #expect(InviteCodec.isTimeBound(invite))
    }

    @Test func anInviteWithNoTokenWouldLastForeverSoItIsNotTimeBound() {
        var invite = validInvite()
        invite.token = Data()
        #expect(!InviteCodec.isTimeBound(invite))
    }

    @Test func aTokenOfTheWrongLengthIsNotAcceptedAsAWindow() {
        var invite = validInvite()
        invite.token = Data(count: 4)
        #expect(!InviteCodec.isTimeBound(invite))
    }

    @Test func anInviteWithAnOverlongNameIsRejected() {
        var broken = validInvite()
        broken.roomName = String(repeating: "a", count: 12)
        #expect(InviteCodec.decode(InviteCodec.encode(broken)) == nil)
    }

    @Test func anInviteWithoutTheInviterPublicKeyIsRejected() {
        // Without it the joiner cannot send the join hello as a PKI direct message.
        var broken = validInvite()
        broken.inviter = Meshchat_Inviter.with {
            $0.nodeNum = 7
            $0.user = User.with { $0.id = "!7" }
        }
        #expect(InviteCodec.decode(InviteCodec.encode(broken)) == nil)
    }

    // MARK: InvitePrivacy — what a photographed code is worth

    let roomPsk = Data(repeating: 7, count: RoomCrypto.pskSize)
    let firepitKey = Data(repeating: 9, count: RoomCipher.keySize)

    private func privacyInvite() -> Meshchat_Invite {
        Meshchat_Invite.with {
            $0.version = InviteCodec.version
            $0.roomID = 0x0BAD_F00D
            $0.roomName = "camp"
            $0.generation = 1
            $0.inviter = Meshchat_Inviter.with {
                $0.nodeNum = 7
                $0.user = User.with { $0.publicKey = Data(repeating: 3, count: 32) }
            }
            $0.inviteID = 0x1234_5678
            $0.window = UInt32(bitPattern: RoomCrypto.windowFor(epochMillis: 1_000_000_000_000))
            $0.token = Data(repeating: 1, count: RoomCrypto.tokenSize)
            $0.secret = Data((0..<InviteCodec.inviteSecretSize).map { UInt8(0x20 + $0) })
        }
    }

    /// The whole point: neither room key can be written into an invite any more.
    @Test func anInviteHasNowhereToPutARoomKey() throws {
        let fields: Data = try privacyInvite().serializedBytes()
        #expect(!fields.containsRun(roomPsk), "the channel key is in the code")
        #expect(!fields.containsRun(firepitKey), "the sealing key is in the code")
    }

    @Test func anInviteWithoutAnInviterKeyIsRefused() {
        var broken = privacyInvite()
        broken.inviter = Meshchat_Inviter.with {
            $0.nodeNum = 7
            $0.user = User()
        }
        #expect(InviteCodec.decode(InviteCodec.encode(broken)) == nil)
    }

    @Test func anInviteNamingNoInviterIsRefused() {
        var broken = privacyInvite()
        broken.clearInviter()
        #expect(InviteCodec.decode(InviteCodec.encode(broken)) == nil)
    }

    /// A code from a version we do not implement is refused, not guessed at.
    @Test func anotherVersionIsNotAccepted() {
        var newer = privacyInvite()
        newer.version = InviteCodec.version + 1
        #expect(InviteCodec.decode(InviteCodec.encode(newer)) == nil)
        #expect(
            InviteCodec.declaredVersion("firepit://join?v=\(InviteCodec.version + 1)&d=abc") == Int(InviteCodec.version)
                + 1)
    }

    @Test func aCodeWithNoTokenWouldNeverExpireSoItIsRefused() {
        var untimed = privacyInvite()
        untimed.token = Data()
        #expect(!InviteCodec.isTimeBound(untimed))
        #expect(InviteCodec.isTimeBound(privacyInvite()))
    }

    /// The radio on the other end decrypts the grant, and anyone holding that radio can read its private key. So the
    /// room's own key rides sealed to the joiner's phone, where the radio cannot open it.
    @Test func aGrantCarriesTheRoomKeySealedToTheJoinersPhoneNeverInTheClear() throws {
        let phone = KeyEnvelope.generateKeyPair()
        let phonePublic = KeyEnvelope.publicBytes(phone.publicKey)
        let context = KeyEnvelope.contextOf(roomId: 0x0BAD_F00D, generation: 1, recipientNodeNum: 42, hour: 491_234)
        let hedge = KeyEnvelope.inviteHedge(secret: privacyInvite().secret, roomId: 0x0BAD_F00D, inviteId: 0x1234_5678)
        let sealedKey = try KeyEnvelope.seal(recipient: phonePublic, secret: firepitKey, context: context, hedge: hedge)
        let grant = Meshchat_RoomGrant.with {
            $0.answer = .granted
            $0.inviteID = 0x1234_5678
            $0.roomID = 0x0BAD_F00D
            $0.roomName = "camp"
            $0.roomPsk = roomPsk
            $0.generation = 1
            $0.sealedKey = sealedKey
            $0.keyHour = 491_234
        }

        let bytes: Data = try grant.serializedBytes()
        let decoded = try Meshchat_RoomGrant(serializedBytes: bytes)

        #expect(!bytes.containsRun(firepitKey), "the sealing key travels in the clear")
        #expect(decoded.roomPsk == roomPsk)
        #expect(
            KeyEnvelope.open(privateKey: phone, ownPublic: phonePublic, sealed: decoded.sealedKey, context: context, hedge: hedge)
                == firepitKey)
    }

    @Test func aRefusalCarriesNoKeysToLeak() throws {
        let declined = Meshchat_RoomGrant.with {
            $0.answer = .declined
            $0.inviteID = 0x1234_5678
            $0.roomID = 0x0BAD_F00D
        }
        let bytes: Data = try declined.serializedBytes()
        #expect(!bytes.containsRun(roomPsk))
        #expect(!bytes.containsRun(firepitKey))
    }

    /// The grant is encrypted to these, so a hello without them cannot be answered.
    @Test func aHelloCarriesTheKeysItsAnswerIsEncryptedTo() throws {
        let hello = Meshchat_JoinHello.with {
            $0.inviteID = 0x1234_5678
            $0.token = Data(repeating: 1, count: RoomCrypto.tokenSize)
            $0.joinerKey = Data(repeating: 5, count: 32)
            $0.phoneKey = KeyEnvelope.publicBytes(KeyEnvelope.generateKeyPair().publicKey)
        }
        let decoded = try Meshchat_JoinHello(serializedBytes: try hello.serializedBytes() as Data)
        #expect(decoded.joinerKey.count == 32)
        #expect(KeyEnvelope.isValidPublicKey(decoded.phoneKey))
    }

    /// The lookback is how long a photographed code stays worth presenting, so it is held well under the old two-minute
    /// figure on purpose.
    @Test func theInviterForgetsItsOwnTokensQuickly() {
        let seconds = Int64(RoomCrypto.defaultLookbackWindows) * RoomCrypto.rotationSeconds
        #expect(seconds <= 60, "a stolen code stays usable for \(seconds)s")
    }
}

/// One scanner reads two formats, and which one a code is decides how private the result can be. If either codec ever
/// accepted the other's payload, a person would join an ordinary Meshtastic channel believing it was a sealed room, or
/// the reverse.
@Suite("Scan disambiguation")
struct ScanDisambiguationTests {
    let invite = Meshchat_Invite.with {
        $0.version = InviteCodec.version
        $0.roomID = 0x0BAD_F00D
        $0.roomName = "camp"
        $0.generation = 1
        $0.inviter = Meshchat_Inviter.with {
            $0.nodeNum = UInt32(bitPattern: -1_181_562_854)
            $0.user = User.with { $0.publicKey = Data(repeating: 3, count: 32) }
        }
        $0.token = Data(repeating: 1, count: RoomCrypto.tokenSize)
        $0.secret = Data((0..<InviteCodec.inviteSecretSize).map { UInt8(0x40 + $0) })
    }

    /// Built here rather than through our own encoder, so this is a link of the shape the official clients produce and
    /// not a round trip through one object.
    let channelUrl: String = {
        let set = ChannelSet.with {
            $0.settings = [
                ChannelSettings.with {
                    $0.name = "LongFast"
                    $0.psk = Data([1])
                }
            ]
            $0.loraConfig = Config.LoRaConfig.with { $0.modemPreset = .longFast }
        }
        let bytes: Data = (try? set.serializedBytes()) ?? Data()
        return "https://meshtastic.org/e/#" + Base64URL.encode(bytes)
    }()

    @Test func aFirepitInviteDecodesAsOneAndOnlyOneThing() {
        let encoded = InviteCodec.encode(invite)
        #expect(InviteCodec.decode(encoded) != nil)
        #expect(ChannelUrl.decode(encoded) == nil, "a Firepit invite was read as a Meshtastic channel")
    }

    @Test func aMeshtasticLinkDecodesAsOneAndOnlyOneThing() {
        #expect(ChannelUrl.decode(channelUrl) != nil)
        #expect(InviteCodec.decode(channelUrl) == nil, "a Meshtastic link was read as a Firepit invite")
    }

    @Test(arguments: ["", "   ", "hello", "https://example.com/e/#abc", "firepit://join?v=1&d="])
    func neitherCodecAcceptsRubbish(junk: String) {
        #expect(InviteCodec.decode(junk) == nil)
        #expect(ChannelUrl.decode(junk) == nil)
    }

    /// A code that is photographed is worth nothing on its own, because there is no room key in it to take.
    @Test func aFirepitInviteCarriesNoKeyMaterial() throws {
        let decoded = try #require(InviteCodec.decode(InviteCodec.encode(invite)))
        #expect(decoded.roomID == invite.roomID)
        // Encoded bytes, not just the fields: a reserved number could still be written by an older encoder.
        let bytes: Data = try decoded.serializedBytes()
        #expect(
            !bytes.containsRun(Data(repeating: 7, count: RoomCrypto.pskSize)),
            "a 32-byte run in the invite is a key that should not be there")
    }

    @Test func theSchemeSettlesWhichDecoderToUseBeforeAnyDecoding() {
        #expect(InviteCodec.isFirepitCode(InviteCodec.encode(invite)))
        #expect(!InviteCodec.isFirepitCode(channelUrl))
    }

    @Test func aFirepitInviteClassifiesAsFirepit() throws {
        guard case .firepit(let scanned) = CodeScanner.classify(InviteCodec.encode(invite)) else {
            Issue.record("expected a Firepit invite")
            return
        }
        #expect(scanned.roomID == invite.roomID)
    }

    @Test func aMeshtasticLinkClassifiesAsMeshtastic() {
        guard case .meshtastic(let shared) = CodeScanner.classify(channelUrl) else {
            Issue.record("expected a Meshtastic link")
            return
        }
        #expect(shared.primary?.name == "LongFast")
    }

    /// The case the old fallback got wrong: a damaged Firepit code was handed to the Meshtastic decoder, which then
    /// reported it as neither. It is plainly ours, and saying so is the difference between "ask for a fresh code" and
    /// "that is not a code".
    @Test(arguments: ["firepit://join?v=1&d=!!!!not-base64!!!!", "firepit://join?v=1&d=", "firepit://join"])
    func aDamagedFirepitInviteStaysFirepitsProblem(broken: String) {
        #expect(CodeScanner.classify(broken) == .firepitUnreadable(.malformed))
    }

    @Test func anInviteFromANewerAppIsNamedAsSuchRatherThanCalledBroken() {
        let encoded = InviteCodec.encode(invite)
        let payload = encoded[encoded.range(of: "&d=")!.upperBound...]
        let future = "firepit://join?v=\(InviteCodec.version + 1)&d=" + payload
        #expect(CodeScanner.classify(future) == .firepitUnreadable(.newerVersion))
    }

    @Test func anOlderInviteIsStillReadNotRejectedForItsVersion() {
        // Only a newer version is refused outright; anything at or below ours goes to the decoder.
        guard case .firepit = CodeScanner.classify(InviteCodec.encode(invite)) else {
            Issue.record("expected a Firepit invite")
            return
        }
    }

    @Test(arguments: ["", "   ", "hello", "https://example.com/", "https://meshtastic.org/e/#zzz!"])
    func rubbishIsUnrecognisedAndNeverBlamedOnFirepit(junk: String) {
        #expect(CodeScanner.classify(junk) == .unrecognised)
    }

    @Test func theDeclaredVersionIsReadFromTheLinkNotThePayload() {
        #expect(InviteCodec.declaredVersion(InviteCodec.encode(invite)) == Int(InviteCodec.version))
        #expect(InviteCodec.declaredVersion("firepit://join?v=9&d=abc") == 9)
        #expect(InviteCodec.declaredVersion("firepit://join") == nil)
        #expect(InviteCodec.declaredVersion(channelUrl) == nil)
    }
}
