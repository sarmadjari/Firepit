import FirepitProtos
import Testing

@testable import FirepitProtocol

@Suite struct PhoneApiSessionTests {
    @Test func handshakeDownloadsConfigAndReachesReady() async {
        let transport = FakeRadioTransport { configId in configDownload(configId: configId) }
        let session = PhoneApiSession(transport: transport)

        let job = await launchSession(session)
        let ready = await awaitReady(session)

        #expect(await transport.notificationsEnabled)
        if case .ready(let snapshot) = ready {
            #expect(0x1234 == snapshot.myNodeNum)
            #expect(8 == snapshot.channels.count)
            #expect("2.7.26.54e0d8d" == snapshot.metadata?.firmwareVersion)
        } else {
            Issue.record("session did not become ready")
        }

        await cancelSession(job)
    }

    @Test func handshakeSendsANonZeroConfigId() async {
        let transport = FakeRadioTransport { configId in configDownload(configId: configId) }
        let session = PhoneApiSession(transport: transport)
        let job = await launchSession(session)
        _ = await awaitReady(session)

        let request = (await transport.written).first { message in
            if case .wantConfigID? = message.payloadVariant {
                return true
            }
            return false
        }?.wantConfigID
        #expect(request != nil)
        #expect(request != 0)

        await cancelSession(job)
    }

    @Test func capabilitiesAreParsedFromTheDownloadedMetadata() async {
        let transport = FakeRadioTransport { configId in configDownload(configId: configId) }
        let session = PhoneApiSession(transport: transport)
        let job = await launchSession(session)

        let ready = await awaitReady(session)
        guard case .ready(let snapshot) = ready else {
            Issue.record("session did not become ready")
            await cancelSession(job)
            return
        }
        let capabilities = snapshot.capabilities

        #expect(FirmwareVersion(major: 2, minor: 7, patch: 26, raw: "2.7.26.54e0d8d") == capabilities.firmwareVersion)
        #expect(capabilities.supportsPki)
        #expect(!capabilities.supportsSigning)
        #expect(capabilities.isSupported)

        await cancelSession(job)
    }

    @Test func rebootTriggersAFreshConfigDownload() async {
        let transport = FakeRadioTransport { configId in configDownload(configId: configId) }
        let session = PhoneApiSession(transport: transport)
        let job = await launchSession(session)
        _ = await awaitReady(session)

        var rebooted = FromRadio()
        rebooted.rebooted = true
        await transport.deliver(rebooted)
        await Task.yield()
        _ = await awaitReady(session)

        let downloads = await transport.written.compactMap { message -> UInt32? in
            if case .wantConfigID(let id)? = message.payloadVariant {
                return id
            }
            return nil
        }
        #expect(2 == downloads.count)
        #expect(downloads[0] != downloads[1])

        await cancelSession(job)
    }

    @Test func steadyStatePacketsReachSubscribers() async {
        let transport = FakeRadioTransport { configId in configDownload(configId: configId) }
        let session = PhoneApiSession(transport: transport)
        let job = await launchSession(session)
        _ = await awaitReady(session)

        var first = FromRadio()
        first.id = 7
        var second = FromRadio()
        second.id = 8
        let pairTask = Task { await awaitInboundPair(session) }
        try? await Task.sleep(for: .milliseconds(1))
        await transport.deliver(first, second)
        let (firstRead, secondRead) = await pairTask.value
        #expect(7 == firstRead?.id)
        #expect(8 == secondRead?.id)

        await cancelSession(job)
    }

    @Test func aMalformedFrameIsSkippedWithoutEndingTheDrain() async {
        let transport = FakeRadioTransport { configId in configDownload(configId: configId) }
        let session = PhoneApiSession(transport: transport)
        let job = await launchSession(session)
        _ = await awaitReady(session)

        var message = FromRadio()
        message.id = 99
        let readTask = Task { await awaitInbound(session) }
        try? await Task.sleep(for: .milliseconds(1))
        await transport.deliverGarbageThen(message)
        let read = await readTask.value
        #expect(99 == read?.id)

        await cancelSession(job)
    }

    @Test func sendingTriggersADrainSoResponsesAreNotStranded() async throws {
        let transport = FakeRadioTransport { configId in configDownload(configId: configId) }
        let session = PhoneApiSession(transport: transport)
        let job = await launchSession(session)
        _ = await awaitReady(session)

        var queued = FromRadio()
        queued.id = 55
        await transport.deliverSilently(queued)
        let readTask = Task { await awaitInbound(session) }
        try? await Task.sleep(for: .milliseconds(1))
        var heartbeat = ToRadio()
        heartbeat.heartbeat = Heartbeat()
        try await session.send(heartbeat)
        let read = await readTask.value
        #expect(55 == read?.id)

        await cancelSession(job)
    }

    @Test func disconnectIsAnnouncedSoTheRadioResetsItsStateImmediately() async throws {
        let transport = FakeRadioTransport { configId in configDownload(configId: configId) }
        let session = PhoneApiSession(transport: transport)
        let job = await launchSession(session)
        _ = await awaitReady(session)

        try await session.sendDisconnect()

        #expect((await transport.written).contains { $0.disconnect == true })
        await cancelSession(job)
    }
}

private func configDownload(configId: Int32) -> [FromRadio] {
    var messages: [FromRadio] = []
    var myInfo = MyNodeInfo()
    myInfo.myNodeNum = 0x1234
    myInfo.minAppVersion = 30200
    var myInfoMessage = FromRadio()
    myInfoMessage.myInfo = myInfo
    messages.append(myInfoMessage)

    var metadata = DeviceMetadata()
    metadata.firmwareVersion = "2.7.26.54e0d8d"
    metadata.hasPkc_p = true
    metadata.hasXeddsa_p = false
    var metadataMessage = FromRadio()
    metadataMessage.metadata = metadata
    messages.append(metadataMessage)

    for index in 0..<8 {
        var channel = Channel()
        channel.index = Int32(index)
        if index == 0 {
            channel.role = .primary
        } else {
            channel.role = .disabled
        }
        channel.settings = ChannelSettings()
        var channelMessage = FromRadio()
        channelMessage.channel = channel
        messages.append(channelMessage)
    }

    var lora = Config.LoRaConfig()
    lora.region = .eu868
    var config = Config()
    config.lora = lora
    var configMessage = FromRadio()
    configMessage.config = config
    messages.append(configMessage)

    var complete = FromRadio()
    complete.configCompleteID = UInt32(bitPattern: configId)
    messages.append(complete)
    return messages
}
