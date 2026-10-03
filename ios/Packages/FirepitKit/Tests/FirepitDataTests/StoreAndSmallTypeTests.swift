import FirepitCrypto
import FirepitData
import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation
import Testing

/// A clock the test moves by hand.
private final class HandClock: Sendable {
    static let startHour = 491_234
    private let millis = Mutex<Int64>(Int64(startHour) * RoomRatchet.hourMillis + 60_000)

    func now() -> Int64 { millis.withLock { $0 } }

    func advance(hours: Int) {
        millis.withLock { $0 += Int64(hours) * RoomRatchet.hourMillis }
    }
}

private func keyStore(_ clock: HandClock, secrets: InMemorySecretStore = InMemorySecretStore()) -> RoomKeyStore {
    RoomKeyStore(store: secrets, clock: { clock.now() })
}

private let room: Int32 = 4242
private let sender: Int32 = 7
private let hour = HandClock.startHour

private func payload(of sealed: Meshchat_SealedMessage?) throws -> Data {
    try #require(sealed).ciphertext
}

@Test func roomKeyGenerationNeverWalksBackward() throws {
    let store = RoomKeyStore(store: InMemorySecretStore())
    try store.remember(roomId: 1, key: hourKey(), generation: 3)
    try store.remember(roomId: 1, key: hourKey(), generation: 2)
    #expect(store.generationOf(roomId: 1) == 3)
}

@Test func roomKeyForgetRemovesGenerationsAndMetadata() throws {
    let store = RoomKeyStore(store: InMemorySecretStore())
    try store.remember(roomId: 1, key: hourKey(), generation: 1)
    try store.markSuperseded(roomId: 1, generation: 2)
    try store.forget(roomId: 1)
    #expect(!store.holds(roomId: 1))
    #expect(store.currentKey(roomId: 1, generation: 1) == nil)
    #expect(!store.isSuperseded(roomId: 1))
}

@Test func aRoomWithNoKeyIsNotSealed() {
    let store = keyStore(HandClock())
    #expect(!store.holds(roomId: room))
    #expect(!store.canSeal(roomId: room))
    #expect(store.currentKey(roomId: room) == nil)
    #expect(store.seal(roomId: room, sender: sender, plaintext: Data("hello".utf8)) == nil)
}

@Test func aGeneratedKeyStartsThisHour() throws {
    let store = keyStore(HandClock())
    let key = try store.generate(roomId: room)
    #expect(key.hour == hour)
    #expect(store.holds(roomId: room))
    #expect(store.currentKey(roomId: room) == key)
}

@Test func aMemberReadsWhatAnotherSealedOnce() throws {
    let clock = HandClock()
    let mine = keyStore(clock)
    let theirs = keyStore(clock)
    let key = try mine.generate(roomId: room)
    try theirs.remember(roomId: room, key: key)
    let sealed = try payload(of: mine.seal(roomId: room, sender: sender, plaintext: Data("north gate".utf8)))

    #expect(
        theirs.open(roomId: room, generation: 1, sender: sender, payload: sealed)
            == .read(plain: Data("north gate".utf8)))
    #expect(theirs.open(roomId: room, generation: 1, sender: sender, payload: sealed) == .replayed)
}

@Test func aSealedMessageCannotBeReattributed() throws {
    let store = keyStore(HandClock())
    try store.generate(roomId: room)
    let sealed = try payload(of: store.seal(roomId: room, sender: sender, plaintext: Data("on my way".utf8)))
    #expect(store.open(roomId: room, generation: 1, sender: sender + 1, payload: sealed) == .unreadable)
}

@Test func theHourGoneStillOpensAndTheOneBeforeItDoesNot() throws {
    let clock = HandClock()
    let store = keyStore(clock)
    try store.generate(roomId: room)
    let first = try payload(of: store.seal(roomId: room, sender: sender, plaintext: Data("one".utf8)))
    let second = try payload(of: store.seal(roomId: room, sender: sender, plaintext: Data("two".utf8)))

    clock.advance(hours: 1)
    #expect(store.open(roomId: room, generation: 1, sender: sender, payload: first) == .read(plain: Data("one".utf8)))

    clock.advance(hours: 1)
    #expect(
        store.open(roomId: room, generation: 1, sender: sender, payload: second)
            == .outOfHours(hour: hour, now: hour + 2))
}

@Test func aKeyTakenLaterOpensNothingFromBefore() throws {
    let clock = HandClock()
    let secrets = InMemorySecretStore()
    let store = keyStore(clock, secrets: secrets)
    try store.generate(roomId: room)
    let early = try payload(of: store.seal(roomId: room, sender: sender, plaintext: Data("before".utf8)))

    clock.advance(hours: 5)
    store.erase()

    // What is left on disk, read by somebody who took the phone, opens nothing from before.
    let taken = keyStore(clock, secrets: secrets)
    #expect(taken.currentKey(roomId: room)?.hour == hour + 5)
    #expect(
        taken.open(roomId: room, generation: 1, sender: sender, payload: early)
            == .outOfHours(hour: hour, now: hour + 5))
}

@Test func somebodyLetInThisHourReadsNothingFromTheHourBefore() throws {
    let clock = HandClock()
    let member = keyStore(clock)
    try member.generate(roomId: room)
    let before = try payload(of: member.seal(roomId: room, sender: sender, plaintext: Data("before".utf8)))

    clock.advance(hours: 1)
    let joiner = keyStore(clock)
    try joiner.remember(roomId: room, key: try #require(member.currentKey(roomId: room)))
    let after = try payload(of: member.seal(roomId: room, sender: sender, plaintext: Data("after".utf8)))

    #expect(
        joiner.open(roomId: room, generation: 1, sender: sender, payload: before)
            == .outOfHours(hour: hour, now: hour + 1))
    #expect(
        joiner.open(roomId: room, generation: 1, sender: sender, payload: after) == .read(plain: Data("after".utf8)))
}

@Test func aKeyHandedOnIsThisHoursAndMovesOnAsTheirsWould() throws {
    let clock = HandClock()
    let store = keyStore(clock)
    let start = try store.generate(roomId: room)

    clock.advance(hours: 3)
    let handed = try #require(store.currentKey(roomId: room))

    #expect(handed.hour == hour + 3)
    #expect(handed.key == RoomRatchet.forward(key: start.key, roomId: room, generation: 1, from: hour, to: hour + 3))
}

@Test func aKeyStoredBeforeTheRatchetCountsFromTheFixedHour() throws {
    let secrets = InMemorySecretStore()
    let legacy = RoomCipher.generateKey()
    secrets.set(legacy, for: "\(room)/1")
    secrets.set(Data("1".utf8), for: "\(room).generation")

    let current = try #require(keyStore(HandClock(), secrets: secrets).currentKey(roomId: room))

    #expect(current.hour == hour)
    #expect(
        current.key
            == RoomRatchet.forward(key: legacy, roomId: room, generation: 1, from: RoomRatchet.legacyHour, to: hour))
}

@Test func aGenerationMovedOnFromGoesOnceLatePacketsHaveArrived() throws {
    let clock = HandClock()
    let store = keyStore(clock)
    try store.generate(roomId: room)
    try store.remember(roomId: room, key: HourKey(hour: hour, key: RoomCipher.generateKey()), generation: 2)
    #expect(store.seal(roomId: room, sender: sender, plaintext: Data("late".utf8), generation: 1) != nil)

    clock.advance(hours: RoomKeyStore.retiredGraceHours)
    store.erase()

    #expect(store.seal(roomId: room, sender: sender, plaintext: Data("later".utf8), generation: 1) == nil)
    #expect(store.seal(roomId: room, sender: sender, plaintext: Data("now".utf8)) != nil)
}

@Test func aGenerationStillOwedToSomebodyIsKept() throws {
    let clock = HandClock()
    let store = keyStore(clock)
    try store.generate(roomId: room)
    try store.remember(roomId: room, key: HourKey(hour: hour, key: RoomCipher.generateKey()), generation: 2)

    clock.advance(hours: 10)
    store.erase(owed: [RoomGeneration(roomId: room, generation: 1)])

    #expect(store.seal(roomId: room, sender: sender, plaintext: Data("your key".utf8), generation: 1) != nil)
}

@Test func nothingNewIsSealedOnceTheRoomMovedOnWithoutUs() throws {
    let store = keyStore(HandClock())
    try store.generate(roomId: room)
    try store.markSuperseded(roomId: room, generation: 2)

    #expect(!store.canSeal(roomId: room))
    #expect(store.seal(roomId: room, sender: sender, plaintext: Data("hello".utf8)) == nil)
    #expect(store.seal(roomId: room, sender: sender, plaintext: Data("rotation".utf8), generation: 1) != nil)
}

@Test func phoneKeySameStoreRestoresSamePublicKey() throws {
    let secrets = InMemorySecretStore()
    let first = PhoneKeyStore(store: secrets, source: SoftwarePhoneKeySource())
    let second = PhoneKeyStore(store: secrets, source: SoftwarePhoneKeySource())
    #expect(try first.publicKey() == second.publicKey())
}

@Test func phoneKeyInvalidStoredDataRegenerates() throws {
    let secrets = InMemorySecretStore()
    secrets.set(Data([1, 2, 3]), for: "private")
    secrets.set(Data([4, 5, 6]), for: "public")
    let store = PhoneKeyStore(store: secrets, source: SoftwarePhoneKeySource())
    #expect(try store.publicKey().count == KeyEnvelope.publicKeySize)
}

@Test func primaryBackupRecordedOnceNeverOverwritten() throws {
    let backup = PrimaryBackup(store: InMemorySecretStore())
    var first = Channel()
    first.settings.name = "first"
    var second = Channel()
    second.settings.name = "second"
    try backup.remember(nodeNum: 1, channel: first)
    try backup.remember(nodeNum: 1, channel: second)
    #expect(backup.saved(nodeNum: 1)?.settings.name == "first")
}

@Test func primaryBackupCarriesAKeyFalseForFactoryChannel() {
    let channel = Channel()
    #expect(!channel.carriesAKey())
}

@Test func meshTextRemovesControlsAndTrims() {
    #expect(sanitizeMeshText(" \u{0}hello\n ") == "hello")
}

@Test func meshTextCapsLength() {
    #expect(sanitizeMeshText(String(repeating: "a", count: 10), maxChars: 3) == "aaa")
}

@Test func chatPresenceWatchesOnlyForegroundOpenChannel() {
    let presence = ChatPresence()
    presence.setOpenChannel(2)
    presence.setForeground(true)
    #expect(presence.isWatching(channel: 2))
    #expect(!presence.isWatching(channel: 3))
}

@Test func pendingJoinFormatsNodeAndFingerprint() {
    let join = PendingJoin(
        nodeNum: Int32(bitPattern: 0xAABBCCDD),
        roomId: 1,
        inviteId: 2,
        generation: 1,
        joinerKey: Data([1]),
        phoneKey: Data([2]),
        askedAt: 3
    )
    #expect(join.nodeId == "!aabbccdd")
    #expect(join.fingerprint != nil)
}
