import CoreLocation
import FirepitCrypto
import FirepitModel
import FirepitProtocol
import FirepitProtos
import FirepitTransport
import Foundation
import GRDB
import Testing

@testable import FirepitData

@Suite("Radio safety net data layer", .serialized)
struct RadioSafetyNetTests {
    @Test func handlePositionAcceptsOnlyRoomMemberOnSharedFirepitSlot() async throws {
        let h = try Harness()
        try h.roomKeys.generate(roomId: 42)
        try await h.memberDao.record(roomId: 42, nodeNum: 222, now: 1)
        try await h.nodeDao.save(node: MeshNode(nodeNum: 222), now: 1)
        h.mesh.start()
        await h.connect(safetySnapshot(roomPrecision: 0))

        h.link.push(fromRadio(packet: try radioPositionPacket(from: 222, channel: 1, latitudeI: 10, timestamp: nowSeconds())))
        #expect(try await eventually { try await h.nodeDao.find(nodeNum: 222)?.positionFromRadio == true })
        #expect(try await h.nodeDao.find(nodeNum: 222)?.latitudeI == 10)

        let cases: [(String, MeshPacket, Int32)] = [
            ("primary", try radioPositionPacket(from: 333, channel: 0, latitudeI: 30, timestamp: nowSeconds()), 333),
            ("pki", try radioPositionPacket(from: 334, channel: 1, latitudeI: 31, timestamp: nowSeconds(), pkiEncrypted: true), 334),
            ("wrong-room", try radioPositionPacket(from: 335, channel: 2, latitudeI: 32, timestamp: nowSeconds()), 335),
        ]
        try await h.memberDao.record(roomId: 42, nodeNum: 333, now: 1)
        try await h.memberDao.record(roomId: 42, nodeNum: 334, now: 1)
        try await h.memberDao.record(roomId: 55, nodeNum: 335, now: 1)
        for (_, _, node) in cases { try await h.nodeDao.save(node: MeshNode(nodeNum: node), now: 1) }
        for (_, packet, _) in cases { h.link.push(fromRadio(packet: packet)) }
        h.link.push(fromRadio(packet: try radioPositionPacket(from: 222, channel: 1, latitudeI: 11, timestamp: nowSeconds() + 1)))
        #expect(try await eventually { try await h.nodeDao.find(nodeNum: 222)?.latitudeI == 11 })
        for (name, _, node) in cases {
            #expect(try await h.nodeDao.find(nodeNum: node)?.latitudeI == nil, "\(name) was stored")
        }

        // A node outside our rooms keeps showing, as it always did, and is not marked as a member's radio.
        try await h.nodeDao.save(node: MeshNode(nodeNum: 336), now: 1)
        h.link.push(fromRadio(packet: try radioPositionPacket(from: 336, channel: 0, latitudeI: 33, timestamp: nowSeconds())))
        #expect(try await eventually { try await h.nodeDao.find(nodeNum: 336)?.latitudeI == 33 })
        #expect(try await h.nodeDao.find(nodeNum: 336)?.positionFromRadio == false)
    }

    @Test func radioPositionWaitsForSealedTrackToGoQuietAndThenUsesGpsTimestamp() async throws {
        let h = try Harness()
        try h.roomKeys.generate(roomId: 42)
        try await h.memberDao.record(roomId: 42, nodeNum: 222, now: 1)
        h.mesh.start()
        await h.connect(safetySnapshot(roomPrecision: 0))
        let base = nowSeconds()
        try await h.nodeDao.save(node: MeshNode(nodeNum: 222), now: 1)
        var sealed = Position()
        sealed.latitudeI = 1
        sealed.longitudeI = 2
        sealed.time = UInt32(base)
        await h.mesh.storeSealedPosition(nodeNum: 222, position: sealed)
        #expect(try await h.nodeDao.find(nodeNum: 222)?.positionFromRadio == false)

        h.link.push(fromRadio(packet: try radioPositionPacket(from: 222, channel: 1, latitudeI: 10, time: UInt32(base + 100), timestamp: nil)))
        try await packetBarrier(h, from: 222, battery: 10)
        #expect(try await h.nodeDao.find(nodeNum: 222)?.latitudeI == 1)

        h.link.push(fromRadio(packet: try radioPositionPacket(from: 222, channel: 1, latitudeI: 11, timestamp: UInt32(base + 10))))
        try await packetBarrier(h, from: 222, battery: 11)
        #expect(try await h.nodeDao.find(nodeNum: 222)?.latitudeI == 1)

        sealed.time = UInt32(base - 601)
        await h.mesh.storeSealedPosition(nodeNum: 222, position: sealed)
        h.link.push(fromRadio(packet: try radioPositionPacket(from: 222, channel: 1, latitudeI: 15, timestamp: UInt32(base - 700))))
        try await packetBarrier(h, from: 222, battery: 15)
        #expect(try await h.nodeDao.find(nodeNum: 222)?.latitudeI == 1)

        sealed.time = UInt32(base - 601)
        await h.mesh.storeSealedPosition(nodeNum: 222, position: sealed)
        h.link.push(fromRadio(packet: try radioPositionPacket(from: 222, channel: 1, latitudeI: 11, timestamp: UInt32(base + 10))))
        #expect(try await eventually { try await h.nodeDao.find(nodeNum: 222)?.latitudeI == 11 })
        #expect(try await h.nodeDao.find(nodeNum: 222)?.positionFromRadio == true)

        let future = UInt32(currentEpochMillis() / 1_000 + 60)
        h.link.push(fromRadio(packet: try radioPositionPacket(from: 222, channel: 1, latitudeI: 14, timestamp: future)))
        #expect(try await eventually { try await h.nodeDao.find(nodeNum: 222)?.latitudeI == 14 })
        #expect((try await h.nodeDao.find(nodeNum: 222)?.positionTime ?? 0) <= currentEpochMillis())

        h.link.push(fromRadio(packet: try radioPositionPacket(from: 222, channel: 1, latitudeI: 12, timestamp: UInt32(base - 10))))
        try await packetBarrier(h, from: 222, battery: 12)
        #expect(try await h.nodeDao.find(nodeNum: 222)?.latitudeI == 14)

        sealed.latitudeI = 13
        sealed.time = UInt32(base + 20)
        await h.mesh.storeSealedPosition(nodeNum: 222, position: sealed)
        let node = try await h.nodeDao.find(nodeNum: 222)
        #expect(node?.latitudeI == 13)
        #expect(node?.positionFromRadio == false)
    }

    @Test func ownNodePositionUpdatesRadioFixFromTimestampAndIsNotStoredAsMemberPosition() async throws {
        let h = try Harness()
        try await h.memberDao.record(roomId: 42, nodeNum: 111, now: 1)
        try await h.nodeDao.save(node: MeshNode(nodeNum: 111), now: 1)
        h.mesh.start()
        // Connected first, as the other tests are: a packet pushed before the app listens is lost under load.
        await h.connect(safetySnapshot(roomPrecision: 0))
        let stamp = nowSeconds() - 12
        h.link.push(fromRadio(packet: try radioPositionPacket(from: 111, channel: 1, latitudeI: 99, timestamp: UInt32(stamp), source: .locInternal)))
        #expect(await eventually { h.mesh.radioFix.value?.latitudeI == 99 })
        #expect(h.mesh.radioFix.value?.timeMillis == Int64(stamp) * 1_000)
        #expect(try await h.nodeDao.find(nodeNum: 111)?.latitudeI == nil)

        h.link.push(fromRadio(packet: try radioPositionPacket(from: 111, channel: 1, latitudeI: 100, time: UInt32(stamp - 1), timestamp: nil, source: .locInternal)))
        #expect(await eventually { h.mesh.radioFix.value?.latitudeI == 100 })
        #expect(h.mesh.radioFix.value?.timeMillis == Int64(stamp - 1) * 1_000)
    }

    @Test func strangerPositionIsClearedWhenTheNodeJoinsARoom() async throws {
        let h = try Harness()
        try await h.nodeDao.save(node: MeshNode(nodeNum: 222), now: 1)
        h.mesh.start()
        await h.connect(safetySnapshot(roomPrecision: 0))
        h.link.push(fromRadio(packet: try radioPositionPacket(from: 222, channel: 0, latitudeI: 33, timestamp: nowSeconds())))
        #expect(try await eventually { try await h.nodeDao.find(nodeNum: 222)?.latitudeI == 33 })

        try await h.memberDao.record(roomId: 42, nodeNum: 222, now: 2)
        #expect(try await h.nodeDao.find(nodeNum: 222)?.latitudeI == nil)
    }

    @Test func prepareRadioSafetyNetWritesPositionConfigOnlyWhenNeeded() async throws {
        let unset = positionConfig(gps: .notPresent)
        let missing = try SafetyNetHarness(position: unset)
        await missing.connect()
        await #expect(throws: Error.self) { try await missing.location.shareWith(roomId: 42, choice: .hour, radioSafetyNet: true) }
        #expect(missing.link.positionWrites.isEmpty)

        let fixed = positionConfig(gps: .disabled, fixed: true, secs: 300, smart: true, flags: 0, smartDistance: 250, smartInterval: 45)
        let needs = try SafetyNetHarness(position: fixed)
        await needs.connect()
        try await needs.location.shareWith(roomId: 42, choice: .hour, radioSafetyNet: true)
        let written = try #require(needs.link.positionWrites.first)
        #expect(needs.link.positionWrites.count == 1)
        #expect(written.gpsMode == .enabled)
        #expect(written.fixedPosition == false)
        #expect(written.positionBroadcastSecs == 86_400)
        #expect(written.positionBroadcastSmartEnabled == false)
        #expect((written.positionFlags & UInt32(Config.PositionConfig.PositionFlags.timestamp.rawValue)) != 0)
        #expect(written.broadcastSmartMinimumDistance == fixed.broadcastSmartMinimumDistance)
        #expect(written.broadcastSmartMinimumIntervalSecs == fixed.broadcastSmartMinimumIntervalSecs)

        let ready = positionConfig(gps: .enabled, fixed: false, secs: 86_400, smart: false, flags: UInt32(Config.PositionConfig.PositionFlags.timestamp.rawValue))
        let already = try SafetyNetHarness(position: ready)
        await already.connect()
        try await already.location.shareWith(roomId: 42, choice: .hour, radioSafetyNet: true)
        #expect(already.link.positionWrites.isEmpty)
    }

    @Test func keepOnConnectKeepsOnlyPreparedRoomWhenEveryGuardHolds() async throws {
        let ready = positionConfig(gps: .enabled, fixed: false, secs: 86_400, smart: false, flags: UInt32(Config.PositionConfig.PositionFlags.timestamp.rawValue))
        let h = try SafetyNetHarness(position: ready, channels: safetyChannels(roomPrecision: 0, otherPrecision: 32, primaryPrecision: 32))
        h.sharing.remember(roomId: 42, choice: .hour, nowMillis: 1_000, radioSafetyNet: true, safetyNetNodeNum: 111)
        h.location.start()
        await h.connect()
        #expect(await eventually { h.link.channelWrites.count >= 3 })
        let writes = h.link.channelWrites.reduce(into: [Int: Int]()) { result, channel in
            result[Int(channel.index)] = Int(channel.settings.moduleSettings.positionPrecision)
        }
        #expect(writes[0] == 0)
        #expect(writes[1] == 32)
        #expect(writes[2] == 0)
    }

    @Test func keepOnConnectSilencesEverySlotWhenAnyGuardFails() async throws {
        let ready = positionConfig(gps: .enabled, fixed: false, secs: 86_400, smart: false, flags: UInt32(Config.PositionConfig.PositionFlags.timestamp.rawValue))
        let badConfigs = [
            positionConfig(gps: .enabled, fixed: false, secs: 300, smart: false, flags: UInt32(Config.PositionConfig.PositionFlags.timestamp.rawValue)),
            positionConfig(gps: .enabled, fixed: false, secs: 86_400, smart: true, flags: UInt32(Config.PositionConfig.PositionFlags.timestamp.rawValue)),
            positionConfig(gps: .enabled, fixed: true, secs: 86_400, smart: false, flags: UInt32(Config.PositionConfig.PositionFlags.timestamp.rawValue)),
        ]
        for (name, position, node, endsAt) in [
            ("expired", ready, Int32(111), Int64(500)),
            ("other-node", ready, Int32(999), Int64(3_601_000)),
        ] {
            let h = try SafetyNetHarness(position: position, channels: safetyChannels(roomPrecision: 32, otherPrecision: 32, primaryPrecision: 32))
            h.sharing.remember(roomId: 42, choice: .hour, nowMillis: 1_000, radioSafetyNet: true, safetyNetNodeNum: node)
            if name == "expired" { h.sharing.deadline.set(SharingDeadline(roomId: 42, choice: .hour, endsAt: endsAt, radioSafetyNet: true, safetyNetNodeNum: node)) }
            h.location.start()
            await h.connect()
            #expect(await eventually { h.link.channelWrites.contains { $0.settings.moduleSettings.positionPrecision == 0 } }, "\(name)")
            #expect(!h.link.channelWrites.contains { Int($0.index) == 1 && $0.settings.moduleSettings.positionPrecision == 32 }, "\(name)")
        }
        for bad in badConfigs {
            let h = try SafetyNetHarness(position: bad, channels: safetyChannels(roomPrecision: 32, otherPrecision: 32, primaryPrecision: 32))
            h.sharing.remember(roomId: 42, choice: .hour, nowMillis: 1_000, radioSafetyNet: true, safetyNetNodeNum: 111)
            h.location.start()
            await h.connect()
            #expect(await eventually { h.link.channelWrites.contains { Int($0.index) == 1 && $0.settings.moduleSettings.positionPrecision == 0 } })
            #expect(!h.link.channelWrites.contains { Int($0.index) == 1 && $0.settings.moduleSettings.positionPrecision == 32 })
        }
    }

    @Test func stopFailureIsSurfacedAndNextConnectionRetriesSilencing() async throws {
        let ready = positionConfig(gps: .enabled, fixed: false, secs: 86_400, smart: false, flags: UInt32(Config.PositionConfig.PositionFlags.timestamp.rawValue))
        let h = try SafetyNetHarness(position: ready, channels: safetyChannels(roomPrecision: 32, otherPrecision: 0, primaryPrecision: 0))
        h.sharing.remember(roomId: 42, choice: .hour, nowMillis: 1_000, radioSafetyNet: true, safetyNetNodeNum: 111)
        await h.connect()
        h.link.failSetChannel = true
        await #expect(throws: Error.self) { try await h.location.stopSharingAndSilence() }
        #expect(h.sharing.deadline.value == nil)

        h.link.failSetChannel = false
        h.location.start()
        await h.connect()
        #expect(await eventually { h.link.channelWrites.contains { Int($0.index) == 1 && $0.settings.moduleSettings.positionPrecision == 0 } })
        #expect(await eventually { h.sharing.deadline.value == nil })
    }

    @Test func fallbackSendsOnePositionRequestOnCurrentRoomSlotOnlyAfterSealedSilence() async throws {
        let h = try d4Harness()
        try await rememberMembers(h, nodes: [222])
        let location = LocationRepository(mesh: h.base.mesh, admin: h.admin, phoneLocation: ScriptedLocationSource(), rooms: h.repository, sharingStore: h.sharing)
        location.start()
        let answer = await location.requestPosition(nodeNum: 222, timeout: .milliseconds(40))
        #expect(answer == .asked)
        let fallback = h.base.link.sent.filter { $0.packet.decoded.portnum == .positionApp }
        #expect(fallback.count == 1)
        #expect(fallback.first?.packet.to == UInt32(bitPattern: Int32(222)))
        #expect(fallback.first?.packet.channel == 1)
        #expect(fallback.first?.packet.decoded.wantResponse == true)
        #expect(fallback.first?.packet.hopLimit == UInt32(MeshConstants.defaultHopLimit))
    }

    @Test func fallbackUsesRoomCurrentSlotAfterTheWait() async throws {
        let h = try d4Harness()
        try await rememberMembers(h, nodes: [222])
        let location = LocationRepository(mesh: h.base.mesh, admin: h.admin, phoneLocation: ScriptedLocationSource(), rooms: h.repository, sharingStore: h.sharing)
        location.start()
        let task = Task { await location.requestPosition(nodeNum: 222, timeout: .milliseconds(80)) }
        #expect(await waitUntil { h.base.link.sent.contains { $0.packet.decoded.portnum == .privateApp } })
        h.base.mesh.channels.set([RoomChannel(index: 2, name: "Camp", role: .secondary, id: 42, positionPrecision: 0, kind: .firepit)])
        let answer = await task.value
        #expect(answer == .asked)
        let fallback = try #require(h.base.link.sent.last(where: { $0.packet.decoded.portnum == .positionApp }))
        #expect(fallback.packet.channel == 2)
    }

    @Test func sealedPositionAnswerSuppressesFallback() async throws {
        let h = try d4Harness()
        try await rememberMembers(h, nodes: [222])
        let location = LocationRepository(mesh: h.base.mesh, admin: h.admin, phoneLocation: ScriptedLocationSource(), rooms: h.repository, sharingStore: h.sharing)
        location.start()
        let task = Task { await location.requestPosition(nodeNum: 222, timeout: .milliseconds(200)) }
        #expect(await waitUntil { h.base.link.sent.contains { $0.packet.decoded.portnum == .privateApp } })
        h.repository.openedInRooms.send(positionPacket(from: 222, to: 111))
        let result = await task.value
        #expect(result == .answered)
        #expect(!h.base.link.sent.contains { $0.packet.decoded.portnum == .positionApp })
    }

    @Test func simulatedMeshRadioSafetyNetAnswersWhenPhoneIsGone() async throws {
        let (mesh, phones) = try await makeSafetyMesh(2)
        let (room, invite) = try await createSafetyRoom(phones[0])
        try await joinSafety(phones[1], invite: invite, approver: phones[0])
        phones[1].radio.radioPosition = position(latitudeI: 77, longitudeI: 88, timestamp: UInt32(nowSeconds()))
        let targetLocation = LocationRepository(mesh: phones[1].meshRepository, admin: phones[1].admin, phoneLocation: ScriptedLocationSource(), rooms: phones[1].roomRepository, sharingStore: phones[1].sharing)
        targetLocation.start()
        try await targetLocation.shareWith(roomId: room.id, choice: .hour, radioSafetyNet: true)
        var reconnectedRoom = try #require(phones[1].channel(roomId: room.id))
        reconnectedRoom.settings.name = "Camp again"
        reconnectedRoom.settings.moduleSettings.positionPrecision = 0
        phones[1].radio.setChannel(reconnectedRoom)
        #expect(await eventually { phones[1].channel(roomId: room.id)?.settings.moduleSettings.positionPrecision == 32 })
        mesh.loseAtApp(phones[1].radio.nodeNum, true)
        let askerLocation = LocationRepository(mesh: phones[0].meshRepository, admin: phones[0].admin, phoneLocation: ScriptedLocationSource(), rooms: phones[0].roomRepository, sharingStore: phones[0].sharing)
        askerLocation.start()
        let answer = await askerLocation.requestPosition(nodeNum: phones[1].radio.nodeNum, timeout: .milliseconds(80))
        #expect(answer == .asked)
        #expect(try await eventually { try await phones[0].nodeDao.find(nodeNum: phones[1].radio.nodeNum)?.positionFromRadio == true })
        #expect(try await phones[0].nodeDao.find(nodeNum: phones[1].radio.nodeNum)?.latitudeI == 77)
    }

    @Test func simulatedMeshWithoutSafetyNetDoesNotAnswerRadioFallback() async throws {
        let (mesh, phones) = try await makeSafetyMesh(2)
        let (_, invite) = try await createSafetyRoom(phones[0])
        try await joinSafety(phones[1], invite: invite, approver: phones[0])
        phones[1].radio.radioPosition = position(latitudeI: 77, longitudeI: 88, timestamp: UInt32(nowSeconds()))
        mesh.loseAtApp(phones[1].radio.nodeNum, true)
        let askerLocation = LocationRepository(mesh: phones[0].meshRepository, admin: phones[0].admin, phoneLocation: ScriptedLocationSource(), rooms: phones[0].roomRepository, sharingStore: phones[0].sharing)
        askerLocation.start()
        let answer = await askerLocation.requestPosition(nodeNum: phones[1].radio.nodeNum, timeout: .milliseconds(80))
        #expect(answer == .asked)
        try await simulatedPacketBarrier(mesh, from: phones[1], to: phones[0], battery: 19)
        #expect(try await phones[0].nodeDao.find(nodeNum: phones[1].radio.nodeNum)?.latitudeI == nil)
    }

    @Test func cadenceInitializationWaitsForKnownOfferedRadioRateAndNeverAdoptsSafetyNetDay() {
        let defaults = UserDefaults(suiteName: "firepit-cadence-\(UUID().uuidString)")!
        let store = LocationSettingsStore(defaults: defaults)
        store.initializeFromRadio(nil)
        #expect(store.settings.value.rateSeconds == BeaconRate.firmwareDefaultSeconds)
        store.initializeFromRadio(positionConfig(gps: .enabled, secs: 86_400))
        #expect(store.settings.value.rateSeconds == BeaconRate.firmwareDefaultSeconds)
        var offered = positionConfig(gps: .enabled, secs: UInt32(BeaconRate.brisk.seconds), smart: true, smartDistance: 321, smartInterval: 44)
        store.initializeFromRadio(offered)
        #expect(store.settings.value.rateSeconds == BeaconRate.brisk.seconds)
        #expect(store.settings.value.whenMoved)
        #expect(store.settings.value.smartDistanceMetres == 321)
        #expect(store.settings.value.smartIntervalSeconds == 44)
        offered.positionBroadcastSecs = UInt32(BeaconRate.sparing.seconds)
        store.initializeFromRadio(offered)
        #expect(store.settings.value.rateSeconds == BeaconRate.brisk.seconds)
    }
}

private final class SafetyNetLink: RadioLinking, @unchecked Sendable {
    let state = CurrentValue<LinkState>(.disconnected)
    let inbound = Broadcast<FromRadio>()
    let nodeNum: Int32
    let roomPsk: Data
    var failSetChannel = false
    private let lock: Mutex<State>

    struct State {
        var channels: [Int: Channel]
        var position: Config.PositionConfig
        var sent: [ToRadio] = []
        var positionWrites: [Config.PositionConfig] = []
        var channelWrites: [Channel] = []
    }

    init(nodeNum: Int32 = 111, position: Config.PositionConfig, channels: [Channel]) {
        self.nodeNum = nodeNum
        self.roomPsk = channels.first { $0.index == 1 }?.settings.psk ?? Data(repeating: 7, count: RoomCrypto.pskSize)
        self.lock = Mutex(State(channels: Dictionary(uniqueKeysWithValues: channels.map { (Int($0.index), $0) }), position: position))
    }

    var positionWrites: [Config.PositionConfig] { lock.withLock { $0.positionWrites } }
    var channelWrites: [Channel] { lock.withLock { $0.channelWrites } }

    func send(_ message: ToRadio) async throws {
        lock.withLock { $0.sent.append(message) }
        let packet = message.packet
        guard packet.decoded.portnum == .adminApp,
            let admin = try? AdminMessage(serializedBytes: packet.decoded.payload)
        else { return }
        if admin.getConfigRequest == .sessionkeyConfig {
            var reply = AdminMessage()
            reply.sessionPasskey = Data()
            sendAdminReply(to: packet, admin: reply)
            return
        }
        if admin.getChannelRequest != 0 {
            var reply = AdminMessage()
            if let channel = lock.withLock({ $0.channels[Int(admin.getChannelRequest) - 1] }) {
                reply.getChannelResponse = channel
            }
            sendAdminReply(to: packet, admin: reply)
            return
        }
        if case .setChannel(let channel)? = admin.payloadVariant {
            if failSetChannel { throw RadioLinkError.notConnected }
            lock.withLock { state in
                state.channels[Int(channel.index)] = channel
                state.channelWrites.append(channel)
            }
            return
        }
        if case .setConfig(let config)? = admin.payloadVariant, case .position(let position)? = config.payloadVariant {
            lock.withLock { state in
                state.position = position
                state.positionWrites.append(position)
            }
        }
    }

    func connect() {
        var my = MyNodeInfo()
        my.myNodeNum = UInt32(bitPattern: nodeNum)
        var lora = Config.LoRaConfig()
        lora.hopLimit = UInt32(MeshConstants.defaultHopLimit)
        var loraConfig = Config()
        loraConfig.lora = lora
        var positionConfig = Config()
        positionConfig.position = lock.withLock { $0.position }
        state.set(.ready(RadioSnapshot(
            myInfo: my,
            channels: Dictionary(uniqueKeysWithValues: lock.withLock { $0.channels }.map { (Int32($0.key), $0.value) }),
            configs: [loraConfig, positionConfig],
            nodes: [:]
        )))
    }

    private func sendAdminReply(to packet: MeshPacket, admin: AdminMessage) {
        var data = DataMessage()
        data.portnum = .adminApp
        data.requestID = packet.id
        data.payload = (try? admin.serializedData()) ?? Data()
        var reply = MeshPacket()
        reply.from = UInt32(bitPattern: nodeNum)
        reply.to = UInt32(bitPattern: nodeNum)
        reply.id = UInt32.random(in: 1...UInt32.max)
        reply.decoded = data
        inbound.send(fromRadio(packet: reply))
    }
}

private struct SafetyNetHarness: Sendable {
    let db: DatabaseQueue
    let link: SafetyNetLink
    let mesh: MeshRepository
    let admin: NodeAdminClient
    let sharing: SharingStore
    let location: LocationRepository
    let roomKeys: RoomKeyStore
    let roomPsk: Data

    init(position: Config.PositionConfig, channels: [Channel] = safetyChannels()) throws {
        db = try FirepitDatabase.inMemory()
        link = SafetyNetLink(position: position, channels: channels)
        roomKeys = RoomKeyStore(store: InMemorySecretStore())
        try roomKeys.generate(roomId: 42)
        let phoneKeys = PhoneKeyStore(store: InMemorySecretStore(), source: SoftwarePhoneKeySource())
        let messageDao = MessageDao(db)
        let nodeDao = NodeDao(db)
        let peerKeyDao = PeerKeyDao(db)
        let memberDao = RoomMemberDao(db)
        let roomActivity = RoomActivityDao(db)
        let session = SessionStore(defaults: UserDefaults(suiteName: "firepit-safety-session-\(UUID().uuidString)")!)
        session.myNodeNum = 111
        mesh = MeshRepository(
            link: link,
            messageDao: messageDao,
            nodeDao: nodeDao,
            sessionStore: session,
            roomKeys: roomKeys,
            peerKeyDao: peerKeyDao,
            memberDao: memberDao,
            phoneKeys: phoneKeys,
            roomActivity: roomActivity,
            handovers: PendingHandoverDao(db),
            receiptDao: ReceiptDao(db)
        )
        admin = NodeAdminClient(link: link, repository: mesh)
        sharing = SharingStore(defaults: UserDefaults(suiteName: "firepit-safety-\(UUID().uuidString)")!)
        let pinDao = MapPinDao(db)
        let receipts = ReceiptRepository(
            link: link,
            mesh: mesh,
            roomKeys: roomKeys,
            receiptDao: ReceiptDao(db),
            messageDao: messageDao,
            memberDao: memberDao,
            phoneKeys: phoneKeys
        )
        let range = RangeRepository(
            mesh: mesh,
            admin: admin,
            backup: PrimaryBackup(store: InMemorySecretStore()),
            defaults: UserDefaults(suiteName: "firepit-safety-range-\(UUID().uuidString)")!
        )
        let history = RoomHistory(
            mesh: mesh,
            messageDao: messageDao,
            pinDao: pinDao,
            channelState: ChannelStateDao(db),
            roomActivity: roomActivity,
            sessionStore: session
        )
        let rooms = RoomRepository(
            link: link,
            mesh: mesh,
            admin: admin,
            memberDao: memberDao,
            messageDao: messageDao,
            receipts: receipts,
            roomKeys: roomKeys,
            range: range,
            personCardDao: PersonCardDao(db),
            phoneKeys: phoneKeys,
            peerKeyDao: peerKeyDao,
            pinDao: pinDao,
            roomActivity: roomActivity,
            handovers: PendingHandoverDao(db),
            history: history,
            sharingStore: sharing
        )
        location = LocationRepository(mesh: mesh, admin: admin, phoneLocation: ScriptedLocationSource(), rooms: rooms, sharingStore: sharing, nowMillis: { 1_000 })
        roomPsk = link.roomPsk
        mesh.start()
    }

    func connect() async {
        link.connect()
        _ = await eventually { mesh.isConnected.value && !mesh.channels.value.isEmpty }
    }
}

private func safetyChannels(roomPrecision: UInt32 = 0, otherPrecision: UInt32 = 0, primaryPrecision: UInt32 = 0) -> [Channel] {
    var primarySettings = ChannelSettings()
    primarySettings.name = "MeshChat"
    primarySettings.moduleSettings.positionPrecision = primaryPrecision
    var primary = Channel()
    primary.index = 0
    primary.role = .primary
    primary.settings = primarySettings

    var roomSettings = ChannelSettings()
    roomSettings.name = "Camp"
    roomSettings.id = UInt32(bitPattern: Int32(42))
    roomSettings.psk = Data(repeating: 7, count: RoomCrypto.pskSize)
    roomSettings.moduleSettings.positionPrecision = roomPrecision
    var room = Channel()
    room.index = 1
    room.role = .secondary
    room.settings = roomSettings

    var otherSettings = ChannelSettings()
    otherSettings.name = "Side"
    otherSettings.id = UInt32(bitPattern: Int32(55))
    otherSettings.psk = Data(repeating: 8, count: RoomCrypto.pskSize)
    otherSettings.moduleSettings.positionPrecision = otherPrecision
    var other = Channel()
    other.index = 2
    other.role = .secondary
    other.settings = otherSettings
    return [primary, room, other]
}

private func positionConfig(
    gps: Config.PositionConfig.GpsMode,
    fixed: Bool = false,
    secs: UInt32 = 300,
    smart: Bool = false,
    flags: UInt32 = 0,
    smartDistance: UInt32 = 0,
    smartInterval: UInt32 = 0
) -> Config.PositionConfig {
    var position = Config.PositionConfig()
    position.gpsMode = gps
    position.fixedPosition = fixed
    position.positionBroadcastSecs = secs
    position.positionBroadcastSmartEnabled = smart
    position.positionFlags = flags
    position.broadcastSmartMinimumDistance = smartDistance
    position.broadcastSmartMinimumIntervalSecs = smartInterval
    return position
}

private func safetySnapshot(roomPrecision: UInt32) -> RadioSnapshot {
    var snapshot = makeSnapshot()
    var channel = snapshot.channels[1]!
    channel.settings.moduleSettings.positionPrecision = roomPrecision
    snapshot.channels[1] = channel
    return snapshot
}

private func radioPositionPacket(
    from node: Int32,
    channel: Int,
    latitudeI: Int32,
    time: UInt32 = 0,
    timestamp: UInt32? = nil,
    pkiEncrypted: Bool = false,
    source: Position.LocSource = .locUnset
) throws -> MeshPacket {
    var position = Position()
    position.latitudeI = latitudeI
    position.longitudeI = 20
    position.time = time
    if let timestamp { position.timestamp = timestamp }
    position.precisionBits = UInt32(PositionPrecision.full)
    position.locationSource = source
    var data = DataMessage()
    data.portnum = .positionApp
    data.payload = try position.serializedData()
    var packet = MeshPacket()
    packet.id = UInt32.random(in: 1...UInt32.max)
    packet.from = UInt32(bitPattern: node)
    packet.channel = UInt32(channel)
    packet.pkiEncrypted = pkiEncrypted
    packet.decoded = data
    return packet
}

private func position(latitudeI: Int32, longitudeI: Int32, timestamp: UInt32) -> Position {
    var position = Position()
    position.latitudeI = latitudeI
    position.longitudeI = longitudeI
    position.timestamp = timestamp
    position.locationSource = .locInternal
    return position
}

private func radioTelemetryPacket(from node: Int32, battery: UInt32, channel: Int = 0) throws -> MeshPacket {
    var metrics = DeviceMetrics()
    metrics.batteryLevel = battery
    var telemetry = Telemetry()
    telemetry.deviceMetrics = metrics
    var data = DataMessage()
    data.portnum = .telemetryApp
    data.payload = try telemetry.serializedData()
    var packet = MeshPacket()
    packet.id = UInt32.random(in: 1...UInt32.max)
    packet.from = UInt32(bitPattern: node)
    packet.channel = UInt32(channel)
    packet.decoded = data
    return packet
}

private func packetBarrier(_ h: Harness, from node: Int32, battery: UInt32) async throws {
    h.link.push(fromRadio(packet: try radioTelemetryPacket(from: node, battery: battery)))
    #expect(try await eventually { try await h.nodeDao.find(nodeNum: node)?.batteryLevel == Int(battery) })
}

private func simulatedPacketBarrier(_ mesh: SimulatedMesh, from sender: SimulatedPhone, to recipient: SimulatedPhone, battery: UInt32) async throws {
    let room = try #require(sender.radio.channels.values.first { $0.role == .secondary })
    let packet = try radioTelemetryPacket(from: sender.radio.nodeNum, battery: battery, channel: Int(room.index))
    await mesh.transmit(from: sender.radio, packet: packet)
    #expect(try await eventually { try await recipient.nodeDao.find(nodeNum: sender.radio.nodeNum)?.batteryLevel == Int(battery) })
}

private func nowSeconds() -> UInt32 { UInt32(currentEpochMillis() / 1_000) }

private let liveSafetyMeshes = Mutex<[SimulatedMesh]>([])

private func makeSafetyMesh(_ count: Int) async throws -> (SimulatedMesh, [SimulatedPhone]) {
    let mesh = SimulatedMesh()
    liveSafetyMeshes.withLock { $0.append(mesh) }
    let phones = try (0..<count).map { try mesh.addPhone(Int32(100 + $0)) }
    for phone in phones {
        _ = await eventually { phone.meshRepository.isConnected.value && !phone.meshRepository.channels.value.isEmpty }
    }
    return (mesh, phones)
}

private func createSafetyRoom(_ phone: SimulatedPhone) async throws -> (RoomChannel, Meshchat_Invite) {
    let room = try await phone.roomRepository.createRoom(name: "Camp")
    let invite = try await phone.roomRepository.buildInvite(roomId: room.id)
    return (room, invite)
}

private func joinSafety(_ joiner: SimulatedPhone, invite: Meshchat_Invite, approver: SimulatedPhone) async throws {
    try await seedSafetyPublicKeys([joiner, approver])
    try await joiner.roomRepository.joinRoom(invite: invite)
    #expect(await eventually { !approver.roomRepository.pendingJoins.value.isEmpty })
    try await approver.roomRepository.approveJoin(nodeNum: joiner.radio.nodeNum)
    let roomId = Int32(bitPattern: invite.roomID)
    #expect(await eventually { joiner.roomKeys.holds(roomId: roomId) })
}

private func seedSafetyPublicKeys(_ phones: [SimulatedPhone]) async throws {
    for phone in phones {
        for other in phones where other.radio.nodeNum != phone.radio.nodeNum {
            try await phone.nodeDao.save(
                node: MeshNode(
                    nodeNum: other.radio.nodeNum,
                    userId: MeshConstants.formatNodeId(other.radio.nodeNum),
                    longName: "Node",
                    shortName: "ND",
                    publicKey: other.radio.radioKey.base64EncodedString()
                ),
                now: currentEpochMillis()
            )
        }
    }
}
