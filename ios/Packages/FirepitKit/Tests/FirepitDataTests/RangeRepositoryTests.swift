import FirepitProtocol
import FirepitProtos
import Foundation
import Testing

@testable import FirepitData

private func rangeRepo(_ h: Harness, defaults: UserDefaults? = nil, backup: PrimaryBackup? = nil) -> RangeRepository {
    let admin = NodeAdminClient(link: h.link, repository: h.mesh)
    return RangeRepository(
        mesh: h.mesh, admin: admin, backup: backup ?? PrimaryBackup(store: InMemorySecretStore()),
        defaults: defaults ?? UserDefaults(suiteName: UUID().uuidString)!)
}

private func publicPrimary(name: String = "", psk: Data = Data([1])) -> Channel {
    var settings = ChannelSettings()
    settings.name = name
    settings.psk = psk
    var channel = Channel()
    channel.index = 0
    channel.role = .primary
    channel.settings = settings
    return channel
}

private func privatePrimary() -> Channel {
    var channel = publicPrimary(name: "Friends", psk: Data(repeating: 9, count: 16))
    channel.settings.id = 123
    return channel
}

private func replyToGetChannel(on link: FakeRadioLink, channel: Channel, after sentCount: Int = 0) async throws {
    for _ in 0..<100 {
        if let packet = link.sent.dropFirst(sentCount).last(where: { sent in
            guard let admin = try? AdminMessage(serializedBytes: sent.packet.decoded.payload) else {
                return false
            }
            return admin.getChannelRequest != 0
        })?.packet {
            var admin = AdminMessage()
            admin.getChannelResponse = channel
            var data = DataMessage()
            data.portnum = .adminApp
            data.requestID = packet.id
            data.payload = try admin.serializedData()
            var replyPacket = MeshPacket()
            replyPacket.from = packet.to
            replyPacket.decoded = data
            var from = FromRadio()
            from.packet = replyPacket
            link.push(from)
            return
        }
        try await Task.sleep(for: .milliseconds(10))
    }
}

@Test func rangeInitialStateComesFromStoredModeAndRadioPrivacy() throws {
    let h = try Harness(myNodeNum: 111)
    let defaults = UserDefaults(suiteName: UUID().uuidString)!
    defaults.set(RangeMode.publicRelay.name, forKey: "range_mode")
    defaults.set(RadioPrivacy.open.name, forKey: "radio_privacy.111")
    let range = rangeRepo(h, defaults: defaults)
    #expect(range.mode.value == .publicRelay)
    #expect(range.privacy.value == .open)
}

@Test func rangeAdoptsLegacySinglePrivacyChoiceForCurrentRadio() throws {
    let h = try Harness(myNodeNum: 111)
    let defaults = UserDefaults(suiteName: UUID().uuidString)!
    defaults.set(RadioPrivacy.firepit.name, forKey: "radio_privacy")
    let range = rangeRepo(h, defaults: defaults)
    #expect(range.privacy.value == .firepit)
    #expect(defaults.string(forKey: "radio_privacy") == nil)
    #expect(defaults.string(forKey: "radio_privacy.111") == RadioPrivacy.firepit.name)
}

@Test func rangeStartReportsPublicPrimaryNeedsChoice() async throws {
    let h = try Harness(myNodeNum: 111)
    let range = rangeRepo(h)
    h.mesh.start()
    range.start()
    await h.connect(makeSnapshot())
    try await replyToGetChannel(on: h.link, channel: publicPrimary())
    #expect(await eventually { range.primaryIsPublic.value })
    #expect(range.needsChoice.value)
}

@Test func rangeChooseWritesFirepitPrimaryChannel() async throws {
    let h = try Harness(myNodeNum: 111)
    let range = rangeRepo(h)
    h.mesh.start()
    await h.connect(makeSnapshot())
    async let choice: Void = range.choose(choice: .groupOnly)
    try await replyToGetChannel(on: h.link, channel: publicPrimary())
    _ = try await choice
    let sent = try AdminMessage(serializedBytes: h.link.sent.last!.packet.decoded.payload)
    #expect(sent.setChannel.settings.name == PrimaryChannel.groupName)
    #expect(sent.setChannel.settings.psk == PrimaryChannel.key)
}

@Test func rangeChoosePublicRelayWritesEmptyPrimaryName() async throws {
    let h = try Harness(myNodeNum: 111)
    let range = rangeRepo(h)
    h.mesh.start()
    await h.connect(makeSnapshot())
    async let choice: Void = range.choose(choice: .publicRelay)
    try await replyToGetChannel(on: h.link, channel: publicPrimary())
    _ = try await choice
    let sent = try AdminMessage(serializedBytes: h.link.sent.last!.packet.decoded.payload)
    #expect(sent.setChannel.settings.name == PrimaryChannel.publicName)
}

@Test func rangeChooseSavesOriginalPrimaryExactlyOnce() async throws {
    let h = try Harness(myNodeNum: 111)
    let backup = PrimaryBackup(store: InMemorySecretStore())
    let range = rangeRepo(h, backup: backup)
    h.mesh.start()
    await h.connect(makeSnapshot())
    async let first: Void = range.choose(choice: .groupOnly)
    try await replyToGetChannel(on: h.link, channel: privatePrimary())
    _ = try await first
    let afterFirst = h.link.sent.count
    async let second: Void = range.choose(choice: .publicRelay)
    try await replyToGetChannel(on: h.link, channel: PrimaryChannel.channelFor(mode: .groupOnly), after: afterFirst)
    _ = try await second
    #expect(backup.saved(nodeNum: 111)?.settings.name == "Friends")
}

@Test func rangeKeepPublicRestoresSavedPrimaryAndForgetsBackup() async throws {
    let h = try Harness(myNodeNum: 111)
    let backup = PrimaryBackup(store: InMemorySecretStore())
    try backup.remember(nodeNum: 111, channel: privatePrimary())
    let range = rangeRepo(h, backup: backup)
    h.mesh.start()
    await h.connect(makeSnapshot())
    try await range.keepPublic()
    let sent = try AdminMessage(serializedBytes: h.link.sent.last!.packet.decoded.payload)
    #expect(sent.setChannel.settings.name == "Friends")
    #expect(!backup.has(nodeNum: 111))
    #expect(range.privacy.value == .open)
}

@Test func rangeCanRestoreFollowsConnectedFirepitWithBackup() async throws {
    let h = try Harness(myNodeNum: 111)
    let backup = PrimaryBackup(store: InMemorySecretStore())
    try backup.remember(nodeNum: 111, channel: privatePrimary())
    let defaults = UserDefaults(suiteName: UUID().uuidString)!
    defaults.set(RadioPrivacy.firepit.name, forKey: "radio_privacy.111")
    let range = rangeRepo(h, defaults: defaults, backup: backup)
    h.mesh.start()
    await h.connect(makeSnapshot())
    range.start()
    try await replyToGetChannel(on: h.link, channel: PrimaryChannel.channelFor(mode: .groupOnly))
    #expect(await eventually { range.canRestore.value })
}

@Test func rangeMakePrivateUsesCurrentMode() async throws {
    let h = try Harness(myNodeNum: 111)
    let defaults = UserDefaults(suiteName: UUID().uuidString)!
    defaults.set(RangeMode.publicRelay.name, forKey: "range_mode")
    let range = rangeRepo(h, defaults: defaults)
    h.mesh.start()
    await h.connect(makeSnapshot())
    async let write: Void = range.makePrivate()
    try await replyToGetChannel(on: h.link, channel: publicPrimary())
    _ = try await write
    let sent = try AdminMessage(serializedBytes: h.link.sent.last!.packet.decoded.payload)
    #expect(sent.setChannel.settings.name == "")
}

@Test func rangeAlignWithSkipsWhenAlreadyAligned() async throws {
    let h = try Harness(myNodeNum: 111)
    let defaults = UserDefaults(suiteName: UUID().uuidString)!
    defaults.set(RangeMode.groupOnly.name, forKey: "range_mode")
    defaults.set(RadioPrivacy.firepit.name, forKey: "radio_privacy.111")
    let range = rangeRepo(h, defaults: defaults)
    range.primaryIsPublic.set(false)
    try await range.alignWith(choice: .groupOnly)
    #expect(h.link.sent.isEmpty)
}

@Test func rangeAlignWithWritesWhenInviteModeDiffers() async throws {
    let h = try Harness(myNodeNum: 111)
    let range = rangeRepo(h)
    h.mesh.start()
    await h.connect(makeSnapshot())
    async let write: Void = range.alignWith(choice: .publicRelay)
    try await replyToGetChannel(on: h.link, channel: publicPrimary())
    _ = try await write
    let sent = try AdminMessage(serializedBytes: h.link.sent.last!.packet.decoded.payload)
    #expect(sent.setChannel.settings.name == "")
}
