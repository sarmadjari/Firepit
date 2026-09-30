import FirepitCrypto
import FirepitData
import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation
import Testing

@Test func roomKeyGenerationNeverWalksBackward() throws {
    let store = RoomKeyStore(store: InMemorySecretStore())
    try store.remember(roomId: 1, key: RoomCipher.generateKey(), generation: 3)
    try store.remember(roomId: 1, key: RoomCipher.generateKey(), generation: 2)
    #expect(store.generationOf(roomId: 1) == 3)
}

@Test func roomKeyForgetRemovesGenerationsAndMetadata() throws {
    let store = RoomKeyStore(store: InMemorySecretStore())
    try store.remember(roomId: 1, key: RoomCipher.generateKey(), generation: 1)
    try store.markSuperseded(roomId: 1, generation: 2)
    try store.forget(roomId: 1)
    #expect(store.keyFor(roomId: 1, generation: 1) == nil)
    #expect(!store.isSuperseded(roomId: 1))
}

@Test func roomKeyOldGenerationsStillOpen() throws {
    let store = RoomKeyStore(store: InMemorySecretStore())
    let old = RoomCipher.generateKey()
    let new = RoomCipher.generateKey()
    try store.remember(roomId: 1, key: old, generation: 1)
    try store.remember(roomId: 1, key: new, generation: 2)
    #expect(store.keyFor(roomId: 1, generation: 1) == old)
    #expect(store.keyFor(roomId: 1) == new)
}

@Test func roomKeyRejectsWrongKeySize() throws {
    let store = RoomKeyStore(store: InMemorySecretStore())
    #expect(throws: RoomKeyStoreError.wrongKeySize(31)) {
        try store.remember(roomId: 1, key: Data(repeating: 1, count: 31))
    }
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
