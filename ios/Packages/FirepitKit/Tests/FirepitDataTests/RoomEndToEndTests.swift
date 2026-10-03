import CryptoKit
import FirepitCrypto
import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation
import GRDB
import Testing

@testable import FirepitData

private func waitUntil(_ timeoutMillis: Int64 = 2_000, _ predicate: @escaping () async -> Bool) async -> Bool {
    let deadline = currentEpochMillis() + timeoutMillis
    while currentEpochMillis() < deadline {
        if await predicate() { return true }
        try? await Task.sleep(for: .milliseconds(20))
    }
    return await predicate()
}

/// Waits until nothing new has gone over the air for `quietMillis`: joins and rotations finish with greetings and
/// cards that arrive a moment later, and a test that changes state before they land races them.
private func settle(_ mesh: SimulatedMesh, quietMillis: Int64 = 250, timeoutMillis: Int64 = 5_000) async {
    let deadline = currentEpochMillis() + timeoutMillis
    var last = mesh.airPackets.count
    var quietSince = currentEpochMillis()
    while currentEpochMillis() < deadline {
        try? await Task.sleep(for: .milliseconds(25))
        let now = mesh.airPackets.count
        if now != last {
            last = now
            quietSince = currentEpochMillis()
        } else if currentEpochMillis() - quietSince >= quietMillis {
            return
        }
    }
}

/// Every mesh a test builds, kept alive until the test process ends. Radios hold their mesh weakly, and Swift does not
/// promise to keep an otherwise unused local alive to the end of its scope.
private let liveMeshes = Mutex<[SimulatedMesh]>([])

private func makeMesh(_ count: Int) async throws -> (SimulatedMesh, [SimulatedPhone]) {
    let mesh = SimulatedMesh()
    liveMeshes.withLock { $0.append(mesh) }
    let phones = try (0..<count).map { try mesh.addPhone(Int32(100 + $0)) }
    // Every phone has applied its radio's configuration before a scenario starts acting on it.
    for phone in phones {
        _ = await waitUntil(5_000) {
            phone.meshRepository.isConnected.value && !phone.meshRepository.channels.value.isEmpty
                && phone.meshRepository.myNodeNum.value == phone.radio.nodeNum
        }
    }
    return (mesh, phones)
}

private func seedPublicKeys(_ phones: [SimulatedPhone]) async throws {
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

private func createAndInvite(_ a: SimulatedPhone) async throws -> (RoomChannel, Meshchat_Invite) {
    let room = try await a.roomRepository.createRoom(name: "Camp")
    let invite = try await a.roomRepository.buildInvite(roomId: room.id)
    return (room, invite)
}

private func join(_ joiner: SimulatedPhone, invite: Meshchat_Invite, approver: SimulatedPhone) async throws {
    try await seedPublicKeys([joiner, approver])
    try await joiner.roomRepository.joinRoom(invite: invite)
    #expect(
        await waitUntil {
            approver.roomRepository.pendingJoins.value.contains { $0.nodeNum == joiner.radio.nodeNum }
        })
    try await approver.roomRepository.approveJoin(nodeNum: joiner.radio.nodeNum)
    #expect(await waitUntil { joiner.roomRepository.awaiting.value == nil })
    // "No longer waiting" is also what a decline or a timeout looks like, so prove the join landed.
    let roomId = Int32(bitPattern: invite.roomID)
    #expect(await waitUntil { joiner.roomKeys.holds(roomId: roomId) })
    #expect(joiner.radio.channel(roomId: roomId) != nil)
}

private func storedTexts(_ phone: SimulatedPhone, channel: Int) async throws -> [ChatMessage] {
    try await firstValue(phone.messageDao.observeChannel(channel: channel))
}

@Suite("RoomRepository end-to-end simulated mesh", .serialized)
struct RoomEndToEndTests {
    @Test func createdRoomAppearsOnTheRadioAndInRooms() async throws {
        let (_, phones) = try await makeMesh(2)
        let room = try await phones[0].roomRepository.createRoom(name: "Camp")
        #expect(phones[0].radio.channel(roomId: room.id) != nil)
        #expect(phones[0].roomRepository.rooms().contains { $0.id == room.id })
        #expect(phones[0].roomKeys.holds(roomId: room.id))
    }

    @Test func joiningThroughAnInviteNeedsTheInvitersApproval() async throws {
        let (_, phones) = try await makeMesh(2)
        let (_, invite) = try await createAndInvite(phones[0])
        try await phones[1].roomRepository.joinRoom(invite: invite)
        #expect(await waitUntil { !phones[0].roomRepository.pendingJoins.value.isEmpty })
        #expect(phones[1].roomRepository.awaiting.value?.roomId == Int32(bitPattern: invite.roomID))
        #expect(phones[1].radio.channel(roomId: Int32(bitPattern: invite.roomID)) == nil)
    }

    @Test func anApprovedJoinerHoldsTheKeyAndBothSeeEachOther() async throws {
        let (_, phones) = try await makeMesh(2)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        #expect(phones[1].roomKeys.holds(roomId: room.id))
        #expect(phones[1].radio.channel(roomId: room.id) != nil)
        #expect(try await phones[0].memberDao.findEntity(roomId: room.id, nodeNum: phones[1].radio.nodeNum) != nil)
        #expect(try await phones[1].memberDao.findEntity(roomId: room.id, nodeNum: phones[0].radio.nodeNum) != nil)
    }

    @Test func aDeclinedJoinerGetsNoKey() async throws {
        let (_, phones) = try await makeMesh(2)
        let (room, invite) = try await createAndInvite(phones[0])
        try await phones[1].roomRepository.joinRoom(invite: invite)
        #expect(await waitUntil { !phones[0].roomRepository.pendingJoins.value.isEmpty })
        await phones[0].roomRepository.declineJoin(nodeNum: phones[1].radio.nodeNum)
        #expect(await waitUntil { phones[1].roomRepository.awaiting.value?.declined == true })
        #expect(!phones[1].roomKeys.holds(roomId: room.id))
    }

    @Test func roomTextsAreSealedOnTheAirAndReadOnTheOtherPhone() async throws {
        let (mesh, phones) = try await makeMesh(2)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        try await phones[0].meshRepository.sendText(channel: room.index, text: "secret hello")
        #expect(
            await waitUntil {
                (try? await storedTexts(phones[1], channel: room.index).contains { $0.text == "secret hello" }) == true
            })
        let air = mesh.airPackets.last { $0.portNum == .privateApp && $0.from == phones[0].radio.nodeNum }
        #expect(air?.payload.range(of: Data("secret hello".utf8)) == nil)
        #expect(air?.portNum == .privateApp)
    }

    @Test func aReplyCarriesItsQuoteAcross() async throws {
        let (_, phones) = try await makeMesh(2)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        try await phones[0].meshRepository.sendText(channel: room.index, text: "first")
        #expect(await waitUntil { (try? await storedTexts(phones[1], channel: room.index).isEmpty) == false })
        let original = try await storedTexts(phones[1], channel: room.index).first!
        try await phones[1].meshRepository.sendText(channel: room.index, text: "reply", replyId: original.id)
        #expect(
            await waitUntil {
                (try? await storedTexts(phones[0], channel: room.index).contains { $0.replyId == original.id }) == true
            })
    }

    @Test func directMessagesAreSealedBothWays() async throws {
        let (mesh, phones) = try await makeMesh(2)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        await phones[0].roomRepository.sharePersonCard(name: "A", tag: "AA", colourSlot: nil)
        await phones[1].roomRepository.sharePersonCard(name: "B", tag: "BB", colourSlot: nil)
        #expect(
            await waitUntil {
                (await phones[0].meshRepository.phoneKeyOf(nodeNum: phones[1].radio.nodeNum)) != nil
            })
        try await phones[0].meshRepository.sendText(channel: room.index, text: "dm one", to: phones[1].radio.nodeNum)
        try await phones[1].meshRepository.sendText(channel: room.index, text: "dm two", to: phones[0].radio.nodeNum)
        #expect(
            await waitUntil {
                let messages = try? await firstValue(phones[1].messageDao.observeDirect(peer: phones[0].radio.nodeNum))
                return messages?.contains { $0.text == "dm one" } == true
            })
        #expect(
            await waitUntil {
                let messages = try? await firstValue(phones[0].messageDao.observeDirect(peer: phones[1].radio.nodeNum))
                return messages?.contains { $0.text == "dm two" } == true
            })
        #expect(mesh.airPackets.contains { $0.pkiEncrypted && $0.portNum == .privateApp })
    }

    @Test func aDirectMessageThatCannotBeOpenedIsRefusedAndMarkedNotOpened() async throws {
        let (mesh, phones) = try await makeMesh(2)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        try await seedPublicKeys(phones)
        // A knows B's phone key; B has not learned A's, so A's sealed message cannot open on B's phone.
        try await phones[0].peerKeyDao.upsert(
            key: PeerKeyEntity(
                nodeNum: phones[1].radio.nodeNum,
                phoneKey: try phones[1].phoneKeys.publicKey().base64EncodedString(),
                learnedAt: currentEpochMillis())
        )
        let a = phones[0].radio.nodeNum
        // Let the greetings that follow a join land first, or A's card re-teaches B the key removed below.
        await settle(mesh)
        // With the recipient's ack lost, the refusal alone decides the status. (Android lets a later ack upgrade a
        // failure — MessageStatusRules.advance — and the simulated mesh can deliver it after the refusal.)
        mesh.directAcks = false
        try await phones[1].db.write { db in
            try db.execute(sql: "DELETE FROM peer_keys WHERE nodeNum = ?", arguments: [a])
        }
        try await phones[0].meshRepository.sendText(channel: room.index, text: "sealed", to: phones[1].radio.nodeNum)
        let sent = try #require(
            try await firstValue(phones[0].messageDao.observeDirect(peer: phones[1].radio.nodeNum))
                .first { $0.isOutgoing }
        )
        // B's own refusal, sent by its RoomRepository, is what marks the message: nothing here fakes it.
        #expect(await waitUntil { (try? await phones[0].messageDao.find(id: sent.id)?.status) == .failed })
        #expect(
            mesh.airPackets.contains { air in
                air.from == phones[1].radio.nodeNum && air.to == a && air.pkiEncrypted && air.portNum == .privateApp
            })
        let stored = try await firstValue(phones[1].messageDao.observeDirect(peer: a))
        #expect(stored.contains { $0.text == "sealed" } == false)
    }

    @Test func aSecondPhoneKeyForAKnownNodeIsNotAccepted() async throws {
        let (mesh, phones) = try await makeMesh(2)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        await phones[1].roomRepository.sharePersonCard(name: "B", tag: "BB", colourSlot: nil)
        #expect(
            await waitUntil {
                (await phones[0].meshRepository.phoneKeyOf(nodeNum: phones[1].radio.nodeNum)) != nil
            })
        let pinned = await phones[0].meshRepository.phoneKeyOf(nodeNum: phones[1].radio.nodeNum)
        var card = Meshchat_PersonCard()
        card.name = "B"
        card.tag = "BB"
        // A valid key, so the offer reaches the pinning rule instead of failing the validity check first.
        let rival = KeyEnvelope.publicBytes(P256.KeyAgreement.PrivateKey().publicKey)
        #expect(rival != pinned)
        card.phoneKey = rival
        var control = Meshchat_MeshChatControl()
        control.personCard = card
        let sealedMessage = try #require(
            phones[1].roomKeys.seal(
                roomId: room.id, sender: phones[1].radio.nodeNum, plaintext: try control.serializedData()))
        var outer = Meshchat_MeshChatControl()
        outer.sealedMessage = sealedMessage
        var data = DataMessage()
        data.portnum = .privateApp
        data.payload = try outer.serializedData()
        var packet = MeshPacket()
        packet.from = UInt32(bitPattern: phones[1].radio.nodeNum)
        packet.to = UInt32(bitPattern: broadcastNodeNum)
        packet.channel = UInt32(room.index)
        packet.id = 999
        packet.decoded = data
        mesh.inject(packet: packet, to: phones[0].radio.nodeNum)
        try? await Task.sleep(for: .milliseconds(200))
        #expect(await phones[0].meshRepository.phoneKeyOf(nodeNum: phones[1].radio.nodeNum) == pinned)
    }

    @Test func rotatingOutAMemberLocksThemOutOfNewTexts() async throws {
        let (mesh, phones) = try await makeMesh(3)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        let invite2 = try await phones[0].roomRepository.buildInvite(roomId: room.id)
        try await join(phones[2], invite: invite2, approver: phones[0])
        _ = try await phones[0].roomRepository.rotateRoom(roomId: room.id, remove: [phones[2].radio.nodeNum])
        // The radio acknowledges the handover before the phone behind it has taken the key and moved the radio to
        // the new channel; a text sent in that moment could not reach it on a real mesh either.
        #expect(await waitUntil { phones[1].roomKeys.generationOf(roomId: room.id) == 2 })
        await settle(mesh)
        try await phones[0].meshRepository.sendText(channel: room.index, text: "after rotate")
        #expect(
            await waitUntil {
                (try? await storedTexts(phones[1], channel: room.index).contains { $0.text == "after rotate" }) == true
            })
        try? await Task.sleep(for: .milliseconds(100))
        #expect((try await storedTexts(phones[2], channel: room.index)).contains { $0.text == "after rotate" } == false)
        #expect(phones[2].roomKeys.generationOf(roomId: room.id) == 1)
    }

    @Test func historyFromBeforeTheRotationStaysReadable() async throws {
        let (_, phones) = try await makeMesh(3)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        let invite2 = try await phones[0].roomRepository.buildInvite(roomId: room.id)
        try await join(phones[2], invite: invite2, approver: phones[0])
        try await phones[0].meshRepository.sendText(channel: room.index, text: "before rotate")
        #expect(
            await waitUntil {
                (try? await storedTexts(phones[2], channel: room.index).contains { $0.text == "before rotate" }) == true
            })
        _ = try await phones[0].roomRepository.rotateRoom(roomId: room.id, remove: [phones[2].radio.nodeNum])
        #expect((try await storedTexts(phones[2], channel: room.index)).contains { $0.text == "before rotate" })
    }

    @Test func aHandoverToAnAbsentMemberIsRetriedWhenTheyAreHeard() async throws {
        let (mesh, phones) = try await makeMesh(3)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        let invite2 = try await phones[0].roomRepository.buildInvite(roomId: room.id)
        try await join(phones[2], invite: invite2, approver: phones[0])
        mesh.takeOffline(phones[1].radio.nodeNum)
        _ = try await phones[0].roomRepository.rotateRoom(roomId: room.id, remove: [phones[2].radio.nodeNum])
        #expect(!(try await phones[0].handovers.forNode(nodeNum: phones[1].radio.nodeNum)).isEmpty)
        mesh.bringOnline(phones[1].radio.nodeNum)
        // Hearing B during the join started the ten-minute retry interval; let it pass.
        mesh.advanceClock(byMillis: 11 * 60 * 1000)
        let before = mesh.airPackets.count
        var data = DataMessage()
        data.portnum = .textMessageApp
        data.payload = Data("hi".utf8)
        var packet = MeshPacket()
        packet.from = UInt32(bitPattern: phones[1].radio.nodeNum)
        packet.to = UInt32(bitPattern: broadcastNodeNum)
        packet.channel = UInt32(room.index)
        packet.id = 12345
        packet.decoded = data
        mesh.inject(packet: packet, to: phones[0].radio.nodeNum)
        #expect(await waitUntil { mesh.airPackets.count > before })
        #expect(await waitUntil { phones[1].roomKeys.generationOf(roomId: room.id) == 2 })
        #expect(phones[1].roomKeys.currentKey(roomId: room.id) == phones[0].roomKeys.currentKey(roomId: room.id))
        #expect(
            await waitUntil {
                (try? await phones[0].handovers.forNode(nodeNum: phones[1].radio.nodeNum).isEmpty) == true
            })
    }

    @Test func leavingARoomForgetsItsKeysAndChannel() async throws {
        let (_, phones) = try await makeMesh(2)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        try await phones[1].roomRepository.leaveRoom(roomId: room.id, slot: room.index)
        #expect(!phones[1].roomKeys.holds(roomId: room.id))
        #expect(phones[1].radio.channel(roomId: room.id) == nil)
    }

    @Test func removingSomeoneFromAllRoomsRotatesEachRoom() async throws {
        let (_, phones) = try await makeMesh(2)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        let count = try await phones[0].roomRepository.removeFromAllRooms(nodeNum: phones[1].radio.nodeNum)
        #expect(count == 1)
        #expect(phones[0].roomKeys.generationOf(roomId: room.id) == 2)
        try? await Task.sleep(for: .milliseconds(200))
        #expect(phones[1].roomKeys.currentKey(roomId: room.id, generation: 2) == nil)
    }

    @Test func anInviteOutsideItsWindowIsRefused() async throws {
        let (_, phones) = try await makeMesh(2)
        var invite = Meshchat_Invite()
        var user = User()
        user.publicKey = SimulatedMesh.radioKey(222)
        var inviter = Meshchat_Inviter()
        inviter.nodeNum = UInt32(bitPattern: Int32(222))
        inviter.user = user
        invite.version = InviteCodec.version
        invite.roomID = 42
        invite.roomName = "Old"
        invite.generation = 1
        invite.inviter = inviter
        invite.inviteID = 9
        invite.window = 1
        invite.token = Data(repeating: 1, count: RoomCrypto.tokenSize)
        await #expect(throws: RoomError.inviteExpired) {
            try await phones[1].roomRepository.joinRoom(invite: invite, nowMillis: 1_000_000)
        }
    }

    @Test func tooManyJoinHellosAreIgnored() async throws {
        let (_, phones) = try await makeMesh(2)
        let (_, invite) = try await createAndInvite(phones[0])
        for _ in 0..<7 { try? await phones[1].roomRepository.joinRoom(invite: invite) }
        #expect(await waitUntil { !phones[0].roomRepository.pendingJoins.value.isEmpty })
        #expect(phones[0].roomRepository.pendingJoins.value.count == 1)
    }

    @Test func anUnsealedTextOnARoomSlotIsDropped() async throws {
        let (mesh, phones) = try await makeMesh(2)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        var data = DataMessage()
        data.portnum = .textMessageApp
        data.payload = Data("plain".utf8)
        var packet = MeshPacket()
        packet.from = UInt32(bitPattern: phones[0].radio.nodeNum)
        packet.to = UInt32(bitPattern: broadcastNodeNum)
        packet.channel = UInt32(room.index)
        packet.id = 4321
        packet.decoded = data
        mesh.inject(packet: packet, to: phones[1].radio.nodeNum)
        try? await Task.sleep(for: .milliseconds(100))
        #expect((try await storedTexts(phones[1], channel: room.index)).contains { $0.text == "plain" } == false)
    }

    @Test func receiptsTravelBackToTheSender() async throws {
        let (_, phones) = try await makeMesh(2)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        try await phones[0].meshRepository.sendText(channel: room.index, text: "receipt me")
        #expect(await waitUntil { (try? await storedTexts(phones[1], channel: room.index).isEmpty) == false })
        let message = try await storedTexts(phones[1], channel: room.index).first!
        await phones[1].receipts.read(channel: room.index, messageIds: [message.id])
        try await phones[1].receipts.flush()
        #expect(
            await waitUntil {
                (try? await firstValue(phones[0].receiptDao.observe(messageId: message.id)).isEmpty) == false
            })
    }

    @Test func personCardsReachRoomMembers() async throws {
        let (_, phones) = try await makeMesh(2)
        let (_, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        await phones[1].roomRepository.sharePersonCard(name: "Beta", tag: "BB", colourSlot: 3)
        #expect(
            await waitUntil {
                let cards = try? await firstValue(phones[0].personCardDao.observeAll())
                return cards?.values.contains { $0.name == "Beta" } == true
            })
    }

    // MARK: Keys that move on every hour

    @Test func phonesWhoseClocksAreMinutesApartStillReadEachOther() async throws {
        let (_, phones) = try await makeMesh(2)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        phones[1].clockSkewMillis.withLock { $0 = 40 * 60 * 1_000 }

        try await phones[0].meshRepository.sendText(channel: room.index, text: "on my way")
        #expect(await arrives("on my way", at: phones[1], channel: room.index))
        try await phones[1].meshRepository.sendText(channel: room.index, text: "see you there")
        #expect(await arrives("see you there", at: phones[0], channel: room.index))
    }

    @Test func aPhoneWhoseClockIsHoursAheadCannotReadTheRoom() async throws {
        let (_, phones) = try await makeMesh(2)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        phones[1].clockSkewMillis.withLock { $0 = 3 * hourMillis }

        try await phones[0].meshRepository.sendText(channel: room.index, text: "lost to the clock")
        try? await Task.sleep(for: .milliseconds(400))
        #expect(try await !storedTexts(phones[1], channel: room.index).contains { $0.text == "lost to the clock" })
    }

    @Test func aMemberBackAfterDaysAwayReadsTheRoomAgain() async throws {
        let (mesh, phones) = try await makeMesh(2)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])

        mesh.advanceClock(byMillis: 3 * 24 * hourMillis)
        try await phones[0].meshRepository.sendText(channel: room.index, text: "welcome back")
        #expect(await arrives("welcome back", at: phones[1], channel: room.index))
    }

    @Test func aRecordingPlayedBackIsIgnored() async throws {
        let (mesh, phones) = try await makeMesh(2)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        let before = mesh.airPackets.count
        try await phones[0].meshRepository.sendText(channel: room.index, text: "once")
        #expect(await arrives("once", at: phones[1], channel: room.index))
        let recording = try #require(
            mesh.airPackets[before...].first {
                $0.portNum == .privateApp && $0.from == phones[0].radio.nodeNum && $0.channel == room.index
            })
        // Read without touching either phone, so the check cannot itself count as a sighting.
        let recorded = try Meshchat_MeshChatControl(serializedBytes: recording.payload).sealedMessage
        let key = try #require(phones[0].roomKeys.currentKey(roomId: room.id))
        let plain = try #require(openSealed(recorded, key: key, roomId: room.id, sender: phones[0].radio.nodeNum))
        #expect(try Meshchat_MeshChatControl(serializedBytes: plain).roomText.text == "once")

        playBack(recording, asPacket: 777_001, on: mesh, to: phones[1])
        try await phones[0].meshRepository.sendText(channel: room.index, text: "after")
        #expect(await arrives("after", at: phones[1], channel: room.index))
        #expect(try await storedTexts(phones[1], channel: room.index).filter { $0.text == "once" }.count == 1)
    }

    @Test func aRecordingStaysClosedOnceItsHourHasGone() async throws {
        let (mesh, phones) = try await makeMesh(2)
        let (room, invite) = try await createAndInvite(phones[0])
        try await join(phones[1], invite: invite, approver: phones[0])
        let recording = try sealedText("recorded", by: phones[0], roomId: room.id, slot: room.index)

        mesh.advanceClock(byMillis: 2 * hourMillis)
        playBack(recording, asPacket: 777_002, on: mesh, to: phones[1])
        try await phones[0].meshRepository.sendText(channel: room.index, text: "fresh")
        #expect(await arrives("fresh", at: phones[1], channel: room.index))
        #expect(try await !storedTexts(phones[1], channel: room.index).contains { $0.text == "recorded" })
    }

    @Test func somebodyLetInReadsNothingSealedTheHourBeforeTheyJoined() async throws {
        let (mesh, phones) = try await makeMesh(2)
        let (room, invite) = try await createAndInvite(phones[0])
        let recording = try sealedText("before you came", by: phones[0], roomId: room.id, slot: room.index)

        // An hour on for the room's keys only; the invite's own half-minute window keeps to the wall clock.
        for phone in phones {
            phone.clockSkewMillis.withLock { $0 = hourMillis }
        }
        try await join(phones[1], invite: invite, approver: phones[0])
        playBack(recording, asPacket: 777_003, on: mesh, to: phones[1])
        try await phones[0].meshRepository.sendText(channel: room.index, text: "welcome")
        #expect(await arrives("welcome", at: phones[1], channel: room.index))
        #expect(try await !storedTexts(phones[1], channel: room.index).contains { $0.text == "before you came" })
    }
}

private let hourMillis = RoomRatchet.hourMillis

private func arrives(_ text: String, at phone: SimulatedPhone, channel: Int) async -> Bool {
    await waitUntil {
        (try? await storedTexts(phone, channel: channel).contains { $0.text == text }) == true
    }
}

/// Words a phone sealed for its room and never sent: what someone recording the air would hold.
private func sealedText(_ text: String, by phone: SimulatedPhone, roomId: Int32, slot: Int) throws -> AirPacket {
    var words = Meshchat_RoomText()
    words.text = text
    var inner = Meshchat_MeshChatControl()
    inner.roomText = words
    var outer = Meshchat_MeshChatControl()
    outer.sealedMessage = try #require(
        phone.roomKeys.seal(roomId: roomId, sender: phone.radio.nodeNum, plaintext: try inner.serializedData()))
    return AirPacket(
        from: phone.radio.nodeNum, to: broadcastNodeNum, channel: slot, portNum: .privateApp,
        payload: try outer.serializedData(), pkiEncrypted: false)
}

/// A recording of `air` played back as a new packet, the way somebody holding the room's channel key could.
private func playBack(_ air: AirPacket, asPacket id: UInt32, on mesh: SimulatedMesh, to phone: SimulatedPhone) {
    var data = DataMessage()
    data.portnum = air.portNum
    data.payload = air.payload
    var packet = MeshPacket()
    packet.from = UInt32(bitPattern: air.from)
    packet.to = UInt32(bitPattern: air.to)
    packet.channel = UInt32(air.channel)
    packet.id = id
    packet.decoded = data
    mesh.inject(packet: packet, to: phone.radio.nodeNum)
}

extension SimulatedRadio {
    fileprivate func channel(roomId: Int32) -> Channel? {
        channels.values.first { Int32(bitPattern: $0.settings.id) == roomId }
    }
}
