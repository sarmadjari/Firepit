import FirepitCrypto
import FirepitData
import FirepitModel
import FirepitProtocol
import FirepitProtos
import Foundation
import SwiftProtobuf
import Testing

@testable import Firepit
@testable import FirepitProtocol

@Suite("Rooms")
struct RoomsTests {
    @Test("Invite countdown clamps to the 8 second rotation window")
    func countdownClamps() {
        #expect(RoomsViewModel.remainingSeconds(nowMillis: 0) == 8)
        #expect(RoomsViewModel.remainingSeconds(nowMillis: 1) == 7)
        #expect(RoomsViewModel.remainingSeconds(nowMillis: 7_999) == 1)
        #expect(RoomsViewModel.remainingSeconds(nowMillis: 8_000) == 8)
    }

    @Test("Relative time matches Android wording")
    func relativeTimeWording() {
        let now: Int64 = 10_000_000
        #expect(relativeTime(epochMillis: now - 10_000, nowMillis: now) == "just now")
        #expect(relativeTime(epochMillis: now - 5 * 60_000, nowMillis: now) == "5m ago")
        #expect(relativeTime(epochMillis: now - 2 * 3_600_000, nowMillis: now) == "2h ago")
        #expect(relativeTime(epochMillis: now - 3 * 86_400_000, nowMillis: now) == "3d ago")
    }

    @Test("Hardware names are title-cased words")
    func prettyHardwareNames() {
        #expect(prettyHardware("WISMESH_TAG") == "Wismesh Tag")
        #expect(prettyHardware("T_ECHO") == "T Echo")
    }

    @Test("Member detail starts with the attested node id")
    func memberDetailStartsWithNodeId() {
        let row = memberRow(node: MeshNode(nodeNum: 0x1234, hwModel: "WISMESH_TAG", batteryLevel: 101))
        #expect(memberDetail(row, nowMillis: 1_000).hasPrefix("!00001234 · Wismesh Tag · powered"))
    }

    @Test("Member detail says when a member was heard")
    func memberDetailHeard() {
        let row = memberRow(lastHeard: 40_000, node: MeshNode(nodeNum: 5, batteryLevel: 51))
        #expect(memberDetail(row, nowMillis: 100_000) == "!00000005 · 51% · heard 1m ago")
    }

    @Test("Member detail says not heard for second-hand members")
    func memberDetailNotHeard() {
        #expect(memberDetail(memberRow(lastHeard: nil)) == "!00000005 · not heard yet")
    }

    @Test("Member rows prefer person cards over mesh names")
    func memberRowsPreferCards() {
        let rows = RoomsViewModel.memberRows(
            members: [member(nodeNum: 1)],
            nodes: [MeshNode(nodeNum: 1, longName: "Radio")],
            myNodeNum: nil,
            cards: [1: PersonCard(nodeNum: 1, name: "Sam", tag: "SA", colourSlot: nil, updatedAt: 0)]
        )
        #expect(rows.first?.displayName == "Sam")
        #expect(rows.first?.shortName == "SA")
    }

    @Test("Member rows pin ourselves first")
    func memberRowsPinSelfFirst() {
        let rows = RoomsViewModel.memberRows(
            members: [member(nodeNum: 1), member(nodeNum: 2)],
            nodes: [],
            myNodeNum: 2,
            cards: [:]
        )
        #expect(rows.map(\.member.nodeNum) == [2, 1])
        #expect(rows.first?.isSelf == true)
    }

    @Test("Trace summary explains no reply, direct and relayed paths")
    func traceSummary() {
        #expect(RoomsViewModel.traceSummary(result: nil, name: "Sam") == "No reply from Sam. They may be out of range.")
        #expect(
            RoomsViewModel.traceSummary(result: TraceRouteResult(target: 1, towards: [], back: []), name: "Sam")
                == "Sam answered directly, no relay.")
        let relayed = TraceRouteResult(target: 1, towards: [TraceHop(nodeNum: 2, snr: 1.25)], back: [])
        #expect(
            RoomsViewModel.traceSummary(result: relayed, name: "Sam") == "Sam is 2 hops away, via !00000002 (1.2 dB)")
    }

    @Test("Room errors map to user-facing messages")
    func roomErrorMessages() {
        #expect(RoomsViewModel.errorMessage(RoomError.notConnected, fallback: "x") == "Connect your node first")
        #expect(RoomsViewModel.errorMessage(RoomError.alreadyInRoom, fallback: "x") == "You're already in this room")
    }

    @Test("A Firepit scan becomes a checked invite")
    func firepitScanBecomesCheckedInvite() throws {
        let invite = validInvite()
        guard case .ask(let scanned) = RoomsViewModel.handleScan(InviteCodec.encode(invite)) else {
            Issue.record("scan did not become invite")
            return
        }
        #expect(scanned.roomName == "Camp")
        #expect(scanned.inviterId == "!00000007")
        #expect(scanned.fingerprint != nil)
    }

    @Test("A Meshtastic scan is routed as a shared channel")
    func meshtasticScanIsSharedChannel() {
        var settings = ChannelSettings()
        settings.name = "camp"
        settings.psk = Data(repeating: 9, count: 32)
        let url = ChannelUrl.encode([settings], lora: nil)
        guard case .joinMeshtastic(let shared) = RoomsViewModel.handleScan(url) else {
            Issue.record("scan did not become Meshtastic link")
            return
        }
        #expect(shared.channels.first?.name == "camp")
    }

    @Test("Garbage scans get Android's error wording")
    func garbageScanError() {
        #expect(
            RoomsViewModel.handleScan("not a code")
                == .error("That isn't a Firepit invite or a Meshtastic channel link"))
    }

    @Test("Newer Firepit links ask for an update")
    func newerFirepitLinksAskForUpdate() {
        #expect(
            RoomsViewModel.handleScan("firepit://join?v=99&d=abc")
                == .error("This Firepit invite was made by a newer version of the app. Update to join."))
    }

    @Test("Meshtastic join notices preserve Android wording")
    func meshtasticJoinNotices() {
        let room = RoomChannel(index: 1, name: "camp", role: .secondary, id: 4, positionPrecision: 32)
        #expect(RoomsViewModel.meshtasticAddedMessage(count: 1, first: room).contains("Added camp"))
        #expect(RoomsViewModel.meshtasticAddedMessage(count: 2, first: nil).contains("Added 2 Meshtastic channels"))
    }

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
                    $0.publicKey = Data(repeating: 1, count: 32)
                }
            }
            $0.inviteID = 0x0BAD_F00D
            $0.issuedAt = 1_788_000_000
            $0.window = 12_345
            $0.token = Data((0..<8).map { UInt8($0) })
        }
    }

    private func memberRow(lastHeard: Int64? = nil, node: MeshNode? = nil) -> MemberRow {
        MemberRow(
            member: member(nodeNum: node?.nodeNum ?? 5, lastHeard: lastHeard),
            node: node,
            card: nil,
            invitedByName: nil,
            isSelf: false
        )
    }

    private func member(nodeNum: Int32, lastHeard: Int64? = nil) -> RoomMember {
        RoomMember(roomId: 9, nodeNum: nodeNum, invitedBy: 3, firstSeen: 1, lastHeard: lastHeard)
    }
}
