#if DEBUG
    import CoreLocation
    import CryptoKit
    import FirepitCrypto
    import FirepitData
    import FirepitModel
    import FirepitProtocol
    import FirepitProtos
    import Foundation

    /// Debug builds only: the invented world `-demo` shows — two private rooms, a few people camping in Yosemite
    /// Valley, a day and a half of conversation, pins and a live share — so every screen can be seen populated in the
    /// simulator. It lives only in memory, next to a pretend radio; nothing is stored or transmitted.
    nonisolated enum DemoWorld {
        static let me: Int32 = 0x5A3C_91E2
        static let maya: Int32 = 0x1B7D_40A6
        static let jonas: Int32 = 0x2C91_7F35
        static let priya: Int32 = 0x3E0A_5C18
        static let base: Int32 = 0x4F26_A3D9

        static let camp: Int32 = 0x2F6A_11C4
        static let crew: Int32 = 0x6D13_B08E

        /// Yosemite Valley, near Lower Pines campground.
        static let latitude = 37.7396
        static let longitude = -119.5655

        /// A preferences suite that starts empty on every launch, so a demo never inherits anything.
        static func freshDefaults() -> UserDefaults {
            let suite = "com.getfirepit.app.demo"
            let defaults = UserDefaults(suiteName: suite) ?? .standard
            defaults.removePersistentDomain(forName: suite)
            return defaults
        }

        /// Keys first, then the radio: a room is only recognised as Firepit's while its key is held.
        @MainActor
        static func prepare(_ app: AppContainer) {
            for room in [camp, crew] {
                _ = try? app.roomKeys.generate(roomId: room)
            }
            (app.radio as? DemoRadio)?.publish(snapshot())
        }

        @MainActor
        static func run(_ app: AppContainer) {
            Task {
                try? app.people.save(name: "Sam Rivera", tag: "SR")
                await seed(app)
            }
        }

        // MARK: The radio

        static func snapshot() -> RadioSnapshot {
            var my = MyNodeInfo()
            my.myNodeNum = UInt32(bitPattern: me)
            var metadata = DeviceMetadata()
            metadata.firmwareVersion = "2.7.26.54e0d8d"
            metadata.hasPkc_p = true
            metadata.hasBluetooth_p = true

            var lora = Config.LoRaConfig()
            lora.region = .us
            lora.hopLimit = 3
            lora.usePreset = true
            lora.modemPreset = .longFast
            var loraConfig = Config()
            loraConfig.lora = lora
            var device = Config.DeviceConfig()
            device.role = .client
            var deviceConfig = Config()
            deviceConfig.device = device
            var position = Config.PositionConfig()
            position.positionBroadcastSecs = 900
            position.positionBroadcastSmartEnabled = true
            var positionConfig = Config()
            positionConfig.position = position

            let channels: [Int32: Channel] = [
                0: PrimaryChannel.channelFor(mode: .default),
                1: room(index: 1, name: "Camp", id: camp),
                2: room(index: 2, name: "Trail crew", id: crew),
            ]
            let people: [(Int32, String, String, Double, Double, Int64)] = [
                (me, "Sam Rivera", "SR", 0, 0, 0),
                (maya, "Maya Chen", "MC", 0.0021, -0.0034, 60),
                (jonas, "Jonas Weber", "JW", -0.0048, 0.0061, 540),
                (priya, "Priya Nair", "PN", 0.0112, 0.0158, 4_200),
                (base, "Camp base", "CB", 0.0006, 0.0012, 120),
            ]
            var nodes: [Int32: NodeInfo] = [:]
            for (num, longName, shortName, north, east, secondsAgo) in people {
                nodes[num] = node(
                    num, longName: longName, shortName: shortName, north: north, east: east,
                    heardSecondsAgo: secondsAgo)
            }
            return RadioSnapshot(
                myInfo: my,
                metadata: metadata,
                channels: channels,
                configs: [loraConfig, deviceConfig, positionConfig],
                nodes: nodes
            )
        }

        private static func room(index: Int32, name: String, id: Int32) -> Channel {
            var settings = ChannelSettings()
            settings.name = name
            settings.psk = RoomCrypto.generatePsk()
            settings.id = UInt32(bitPattern: id)
            settings.moduleSettings.positionPrecision = 32
            var channel = Channel()
            channel.index = index
            channel.role = .secondary
            channel.settings = settings
            return channel
        }

        private static func node(
            _ num: Int32,
            longName: String,
            shortName: String,
            north: Double,
            east: Double,
            heardSecondsAgo: Int64
        ) -> NodeInfo {
            var user = User()
            user.id = MeshConstants.formatNodeId(num)
            user.longName = longName
            user.shortName = shortName
            user.hwModel = num == base ? .rak4631 : .tbeam
            user.publicKey = Data((0..<32).map { UInt8(truncatingIfNeeded: Int(num) &+ $0) })
            var position = Position()
            position.latitudeI = Int32((latitude + north) * 1e7)
            position.longitudeI = Int32((longitude + east) * 1e7)
            position.altitude = 1_220
            position.time = UInt32(Date.now.timeIntervalSince1970) - UInt32(heardSecondsAgo)
            var metrics = DeviceMetrics()
            metrics.batteryLevel = num == base ? 101 : UInt32(40 + abs(Int(num) % 55))
            var info = NodeInfo()
            info.num = UInt32(bitPattern: num)
            info.user = user
            info.position = position
            info.deviceMetrics = metrics
            info.lastHeard = UInt32(Date.now.timeIntervalSince1970) - UInt32(heardSecondsAgo)
            info.snr = 6.5
            info.hopsAway = num == priya ? 2 : 0
            return info
        }

        // MARK: History

        @MainActor
        private static func seed(_ app: AppContainer) async {
            let now = Int64(Date.now.timeIntervalSince1970 * 1000)
            let minute: Int64 = 60_000
            let yesterday = now - 22 * 60 * minute

            // Members first: the retention sweep forgets cards and keys of anyone outside every room, and it may run
            // between these writes.
            for member in [me, maya, jonas, priya] {
                try? await app.roomMemberDao.record(roomId: camp, nodeNum: member, now: yesterday)
            }
            for member in [me, jonas, priya] {
                try? await app.roomMemberDao.record(roomId: crew, nodeNum: member, now: yesterday)
            }
            for (num, name, tag, slot) in [
                (maya, "Maya Chen", "MC", 7), (jonas, "Jonas Weber", "JW", 3), (priya, "Priya Nair", "PN", 10),
            ] {
                try? await app.personCardDao.upsert(
                    card: PersonCardEntity(nodeNum: num, name: name, tag: tag, colourSlot: slot, updatedAt: now))
                let phoneKey = KeyEnvelope.publicBytes(P256.KeyAgreement.PrivateKey().publicKey)
                try? await app.peerKeyDao.upsert(
                    key: PeerKeyEntity(nodeNum: num, phoneKey: phoneKey.base64EncodedString(), learnedAt: now))
            }
            try? await app.roomActivityDao.joined(roomId: camp, now: yesterday)
            try? await app.roomActivityDao.joined(roomId: crew, now: yesterday)

            let campTexts: [(Int32, Int32, Int64, String, Int32?)] = [
                (101, maya, yesterday, "Made it to Lower Pines, site 42. Bear boxes are huge 🐻", nil),
                (102, jonas, yesterday + 3 * minute, "Nice! We're 20 minutes out", nil),
                (103, me, yesterday + 5 * minute, "Grabbing firewood in the village first", nil),
                (104, priya, yesterday + 50 * minute, "Coverage is patchy up here but the mesh is holding", nil),
                (105, maya, now - 95 * minute, "Morning! Coffee's on 🔥", nil),
                (106, jonas, now - 90 * minute, "Mist Trail at 9?", nil),
                (107, me, now - 88 * minute, "I'm in. Bringing the water filter", 106),
                (108, priya, now - 40 * minute, "Ranger says the upper trail closes at 4", nil),
                (109, maya, now - 12 * minute, "Just passed Vernal Fall, about an hour behind you", nil),
                (110, me, now - 3 * minute, "Waiting at the footbridge 👋", nil),
            ]
            for (id, from, at, text, reply) in campTexts {
                let mine = from == me
                let message = ChatMessage(
                    id: id, channel: 1, fromNodeNum: from, toNodeNum: broadcastNodeNum, text: text, sentAt: at,
                    rxTime: mine ? nil : at, status: mine ? .reachedMesh : .received, isOutgoing: mine,
                    hopsAway: from == priya ? 2 : 0, replyId: reply, signed: true, roomId: camp)
                try? await app.messageDao.save(message: message, myNodeNum: me)
            }
            try? await app.receiptDao.recordRead(messageId: 107, nodeNum: jonas, at: now - 80 * minute)
            try? await app.receiptDao.recordRead(messageId: 107, nodeNum: maya, at: now - 70 * minute)
            try? await app.receiptDao.recordReceived(messageId: 110, nodeNum: jonas, at: now - 2 * minute)

            // Reactions are messages of their own, shown under the one they answer (UX §5.4).
            let reactions: [(Int32, Int32, Int64, String, Int32)] = [
                (111, jonas, now - 94 * minute, "❤️", 105),
                (112, me, now - 93 * minute, "❤️", 105),
                (113, priya, now - 92 * minute, "😂", 105),
                (114, jonas, now - 87 * minute, "👍", 107),
            ]
            for (id, from, at, emoji, target) in reactions {
                let mine = from == me
                let reaction = ChatMessage(
                    id: id, channel: 1, fromNodeNum: from, toNodeNum: broadcastNodeNum, text: emoji, sentAt: at,
                    rxTime: mine ? nil : at, status: mine ? .reachedMesh : .received, isOutgoing: mine,
                    replyId: target, emoji: 1, signed: true, roomId: camp)
                try? await app.messageDao.save(message: reaction, myNodeNum: me)
            }

            let crewTexts: [(Int32, Int32, Int64, String)] = [
                (201, jonas, now - 30 * minute, "Headlamps for tonight?"),
                (202, priya, now - 25 * minute, "Two spares in my pack"),
                (203, jonas, now - 6 * minute, "Meet at the base radio at 7"),
            ]
            for (id, from, at, text) in crewTexts {
                let message = ChatMessage(
                    id: id, channel: 2, fromNodeNum: from, toNodeNum: broadcastNodeNum, text: text, sentAt: at,
                    rxTime: at, status: .received, signed: true, roomId: crew)
                try? await app.messageDao.save(message: message, myNodeNum: me)
            }
            // Camp is read up to now; Trail crew still has its last two unread.
            try? await app.channelStateDao.markRead(channel: 1, now: now, roomId: camp)
            try? await app.channelStateDao.markRead(channel: 2, now: now - 28 * minute, roomId: crew)

            let direct: [(Int32, Int32, Int64, String, MessageStatus)] = [
                (301, maya, now - 20 * minute, "Did you pack the spare stove canister?", .received),
                (302, me, now - 18 * minute, "Yes, in the blue bin", .delivered),
                (303, maya, now - 17 * minute, "Legend 🙏", .received),
            ]
            for (id, from, at, text, status) in direct {
                let mine = from == me
                let message = ChatMessage(
                    id: id, channel: 0, fromNodeNum: from, toNodeNum: mine ? maya : me, text: text, sentAt: at,
                    rxTime: mine ? nil : at, status: status, isOutgoing: mine, signed: true)
                try? await app.messageDao.save(message: message, myNodeNum: me)
            }

            let pins: [(Int32, String, String, Double, Double)] = [
                (401, "Trailhead", "Mist Trail starts here", 0.0035, 0.0142),
                (402, "Water", "Tap by the restrooms", -0.0012, -0.0021),
            ]
            for (id, name, description, north, east) in pins {
                try? await app.mapPinDao.save(
                    pin: MapPin(
                        id: id, channel: 1, latitudeI: Int32((latitude + north) * 1e7),
                        longitudeI: Int32((longitude + east) * 1e7), name: name, description: description,
                        createdBy: maya, receivedAt: now - 60 * minute, roomId: camp))
            }

            // Members' positions only count when they arrive sealed from their phones, so they are written as a sealed
            // one is: full precision, stamped when it was taken. Priya's is an hour old, to show a stale marker.
            let fixes: [(Int32, Double, Double, Int64)] = [
                (maya, 0.0021, -0.0034, 1), (jonas, -0.0048, 0.0061, 9), (priya, 0.0112, 0.0158, 70),
            ]
            for (num, north, east, minutesAgo) in fixes {
                try? await app.nodeDao.updatePosition(
                    nodeNum: num, latitudeI: Int32((latitude + north) * 1e7),
                    longitudeI: Int32((longitude + east) * 1e7),
                    altitude: 1_220, positionTime: now - minutesAgo * minute, positionPrecision: 32, groundSpeed: nil,
                    groundTrack: nil)
            }

            app.sharingStore.remember(roomId: camp, choice: .fourHours, nowMillis: now - 25 * minute)
        }
    }

    /// The demo's own GPS: a fixed spot in the valley, so a demo never asks Core Location (or the person) for anything.
    nonisolated final class DemoLocationSource: PhoneLocationProviding {
        func updates(interval: Duration) -> AsyncStream<CLLocation> {
            AsyncStream { continuation in
                let task = Task {
                    while !Task.isCancelled {
                        continuation.yield(
                            CLLocation(
                                coordinate: CLLocationCoordinate2D(
                                    latitude: DemoWorld.latitude, longitude: DemoWorld.longitude),
                                altitude: 1_220, horizontalAccuracy: 8, verticalAccuracy: 12, timestamp: .now))
                        try? await Task.sleep(for: interval)
                    }
                    continuation.finish()
                }
                continuation.onTermination = { _ in
                    task.cancel()
                }
            }
        }

        func hasAnyProvider() -> Bool {
            true
        }
    }
#endif
