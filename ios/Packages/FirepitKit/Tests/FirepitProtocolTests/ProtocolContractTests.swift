import FirepitModel
import FirepitProtos
import Foundation
import Testing

@testable import FirepitProtocol

/// Canary for the vendored protobuf pin (Protos/UPSTREAM.md). Ported from android/core/protocol ProtocolContractTest.
///
/// Every number here is a wire-contract fact the app builds on. If a re-pin changes one, this fails immediately instead
/// of producing packets the mesh silently drops. Android writes uint32 fields from signed Kotlin ints, so its fixtures
/// such as `-1` and `Int.MIN_VALUE` are the same unsigned wire values here (`u(-1)` = 0xFFFFFFFF), which keeps every
/// encoded size identical to the Android test's.
@Suite struct ProtocolContractTests {
    private static let payloadLen = Int(Constants.dataPayloadLen.rawValue)

    @Test func payloadBudgetMatchesFirmware() {
        // 239 bytes of encrypted Data fit in a 255-byte frame after the 16-byte header; 233 of those are available to
        // Data.payload.
        #expect(Constants.dataPayloadLen.rawValue == 233)
        #expect(Self.payloadLen == MeshConstants.dataPayloadLen)
    }

    @Test func controlTrafficUsesThePortTheProtosSanctionForPrivateApps() {
        // portnums.proto: private apps may use PRIVATE_APP directly rather than claim a number, which would mean
        // diverging from the vendored protos.
        #expect(PortNum.privateApp.rawValue == 256)
        #expect(PortNum.privateApp.rawValue <= PortNum.max.rawValue)
    }

    @Test func textMessagesAndRoutingKeepTheirWellKnownPortNumbers() {
        #expect(PortNum.textMessageApp.rawValue == 1)
        #expect(PortNum.positionApp.rawValue == 3)
        #expect(PortNum.nodeinfoApp.rawValue == 4)
        #expect(PortNum.routingApp.rawValue == 5)
        #expect(PortNum.adminApp.rawValue == 6)
        #expect(PortNum.waypointApp.rawValue == 8)
        #expect(PortNum.alertApp.rawValue == 11)
        #expect(PortNum.telemetryApp.rawValue == 67)
    }

    @Test func meshPacketRoundTrips() throws {
        let packet = MeshPacket.with {
            $0.to = u(Self.broadcastNodeNum)
            $0.channel = 3
            $0.id = 0x1234_5678
            // Never 0: a phone-built packet with hop_limit 0 and no want_ack is transmitted but never rebroadcast.
            $0.hopLimit = 3
            $0.wantAck = true
            $0.decoded = DataMessage.with {
                $0.portnum = .textMessageApp
                $0.payload = Data("meet at the gate".utf8)
            }
        }

        let decoded = try MeshPacket(serializedBytes: try packet.serializedBytes() as Data)

        #expect(decoded == packet)
        #expect(decoded.hopLimit == 3)
        #expect(decoded.wantAck)
        #expect(decoded.decoded.portnum == .textMessageApp)
    }

    @Test func twoEightSigningFieldsDecodeAsDefaultsSoTwoSevenNodesStayCompatible() throws {
        // A 2.7 node never sets field 22; the app must read that as "unsigned", not as missing data, or the verified
        // badge would misfire.
        let fromOlderFirmware = MeshPacket.with {
            $0.from = 42
            $0.id = 7
        }

        let decoded = try MeshPacket(serializedBytes: try fromOlderFirmware.serializedBytes() as Data)

        #expect(!decoded.xeddsaSigned)
    }

    @Test func joinHelloStaysSmallEnoughToBeARoundingErrorOnAir() throws {
        let control = Meshchat_MeshChatControl.with {
            $0.version = 1
            $0.joinHello = Meshchat_JoinHello.with {
                $0.inviteID = 0x7F3A_11C2
                $0.token = Data((0..<8).map { UInt8($0) })
                $0.generation = 1
                $0.appVersion = 1
            }
        }

        let encoded = try encode(control)

        #expect(try Meshchat_MeshChatControl(serializedBytes: encoded) == control)
        #expect(encoded.count <= 40, "Control payload grew to \(encoded.count) bytes; port 300 messages must stay tiny")
    }

    @Test func aFullReceiptFitsInOnePacketSealed() throws {
        // Worst case: every id a large fixed32, both lists full to the cap.
        let half = ReceiptRules.maxIdsPerPacket / 2
        let control = Meshchat_MeshChatControl.with {
            $0.version = 1
            $0.receipt = Meshchat_Receipt.with {
                $0.roomID = u(-1)
                $0.delivered = (0..<half).map { u(Int32.min &+ Int32($0)) }
                $0.read = (0..<half).map { u(Int32.max &- Int32($0)) }
            }
        }

        let encoded = try encode(control)
        let onTheWire = encoded.count + Self.sealingOverhead

        #expect(try Meshchat_MeshChatControl(serializedBytes: encoded) == control)
        #expect(
            onTheWire <= Self.payloadLen,
            "A sealed receipt is \(onTheWire) bytes, over the \(Self.payloadLen)-byte payload")
    }

    /// The roster travels sealed under the room key inside a PKI direct message, so the envelope and the PKI tag both
    /// come out of the entries' share. Worst case on the wire: negative node numbers are large uint32 values, which
    /// take
    /// the full five bytes as a varint.
    @Test func aFullRosterSyncFitsInOneDirectMessageSealed() throws {
        let control = Meshchat_MeshChatControl.with {
            $0.version = 1
            $0.rosterSync = Meshchat_RosterSync.with {
                $0.roomID = u(-1)
                $0.entries = (0..<Self.maxRosterEntries).map { index in
                    Meshchat_RosterEntry.with {
                        $0.nodeNum = u(Int32.min &+ Int32(index))
                        $0.invitedBy = u(Int32.min &+ Int32(index))
                    }
                }
                $0.truncated = true
            }
        }
        let encoded = try encode(control)
        #expect(try Meshchat_MeshChatControl(serializedBytes: encoded) == control)

        assertFitsDirect(try sealedInRoom(encoded.count), "sealed roster sync")
    }

    /// Sent before the sender's own radio moves on, so members who miss the new key still hear.
    @Test func aSealedNoticeThatTheRoomMovedOnFitsOnePacket() throws {
        let notice = Meshchat_MeshChatControl.with {
            $0.version = 1
            $0.rosterEvent = Meshchat_RosterEvent.with {
                $0.kind = .keyRotated
                $0.nodeNum = u(-1)
                $0.generation = UInt32(Int32.max)
            }
        }

        assertFitsBroadcast(try sealedInRoom(try encode(notice).count), "sealed rotation notice")
    }

    // MARK: What the phone seals itself, rather than leaving to the radio

    /// Words at the composer's limit, sealed to the other phone, inside a PKI direct message.
    @Test func aSealedDirectMessageAtTheComposersLimitFitsADirectMessage() throws {
        let words = Meshchat_MeshChatControl.with {
            $0.version = 1
            $0.roomText = Meshchat_RoomText.with {
                $0.text = String(repeating: "a", count: MessagePrivacy.maxDirectSealedTextBytes)
                $0.replyID = u(-1)
            }
        }
        let sealedSize = try encode(words).count + MessagePrivacy.directSealedOverhead
        let carried = Meshchat_MeshChatControl.with {
            $0.version = 1
            $0.sealedDirect = Meshchat_SealedDirect.with { $0.ciphertext = bytes(sealedSize) }
        }

        assertFitsDirect(try encode(carried).count, "sealed direct message")
    }

    @Test func aFullReceiptForOnePersonFitsADirectMessageSealedToTheirPhone() throws {
        let half = ReceiptRules.maxIdsPerPacket / 2
        let receipt = Meshchat_MeshChatControl.with {
            $0.receipt = Meshchat_Receipt.with {
                $0.delivered = (0..<half).map { u(Int32.min &+ Int32($0)) }
                $0.read = (0..<half).map { u(-1 &- Int32($0)) }
            }
        }
        let sealedSize = try encode(receipt).count + MessagePrivacy.directSealedOverhead
        let carried = Meshchat_MeshChatControl.with {
            $0.sealedDirect = Meshchat_SealedDirect.with { $0.ciphertext = bytes(sealedSize) }
        }

        assertFitsDirect(try encode(carried).count, "sealed direct receipt")
    }

    /// A pin with every field at its limit, sealed under the room key.
    @Test func aPinAtItsLimitsFitsOneSealedPacket() throws {
        let pin = Meshchat_MeshChatControl.with {
            $0.version = 1
            $0.pin = Waypoint.with {
                $0.id = u(-1)
                $0.latitudeI = Int32.min
                $0.longitudeI = Int32.min
                $0.expire = u(-1)
                $0.lockedTo = u(-1)
                $0.name = String(repeating: "a", count: Self.pinNameBytes)
                $0.description_p = String(repeating: "a", count: Self.pinDescriptionBytes)
                $0.icon = u(-1)
            }
        }

        assertFitsBroadcast(try sealedInRoom(try encode(pin).count), "sealed pin")
    }

    @Test func aSealedPositionFitsOnePacketWithRoomToSpare() throws {
        let position = Meshchat_MeshChatControl.with {
            $0.version = 1
            $0.position = Position.with {
                $0.latitudeI = Int32.min
                $0.longitudeI = Int32.min
                $0.altitude = Int32.min
                $0.time = u(-1)
                $0.locationSource = .locExternal
                $0.groundSpeed = u(-1)
                $0.groundTrack = u(-1)
            }
        }
        let size = try sealedInRoom(try encode(position).count)

        assertFitsBroadcast(size, "sealed position")
        #expect(size <= 120, "A position is sent over and over, so it should stay small: \(size) bytes")
    }

    // MARK: Keys sealed to phones travel inside PKI direct messages

    @Test func aJoinHelloWithBothKeysFitsADirectMessage() throws {
        let control = Meshchat_MeshChatControl.with {
            $0.version = 1
            $0.joinHello = Meshchat_JoinHello.with {
                $0.inviteID = u(-1)
                $0.token = bytes(8)
                $0.generation = UInt32(Int32.max)
                $0.appVersion = UInt32(Int32.max)
                $0.joinerKey = bytes(32)
                $0.phoneKey = bytes(Self.phoneKey)
            }
        }

        assertFitsDirect(try encode(control).count, "join hello")
    }

    @Test func aGrantWithItsSealedKeyFitsADirectMessage() throws {
        let control = Meshchat_MeshChatControl.with {
            $0.version = 1
            $0.roomGrant = Meshchat_RoomGrant.with {
                $0.answer = .granted
                $0.inviteID = u(-1)
                $0.roomID = u(-1)
                $0.roomName = String(repeating: "a", count: Self.maxRoomNameBytes)
                $0.roomPsk = bytes(32)
                $0.generation = UInt32(Int32.max)
                $0.sealedKey = bytes(Self.sealedKey)
            }
        }

        assertFitsDirect(try encode(control).count, "room grant")
    }

    /// The heaviest thing Firepit sends: a new key sealed to the phone, the rotation sealed again under the key it
    /// replaces, all inside a PKI direct message. Rotations remove one member at a time.
    @Test func aRotationSealedUnderTheOldKeyFitsADirectMessage() throws {
        let rotation = Meshchat_MeshChatControl.with {
            $0.version = 1
            $0.keyRotation = Meshchat_KeyRotation.with {
                $0.roomID = u(-1)
                $0.generation = UInt32(Int32.max)
                $0.roomPsk = bytes(32)
                $0.roomName = String(repeating: "a", count: Self.maxRoomNameBytes)
                $0.removed = [u(Int32.min)]
                $0.sealedKey = bytes(Self.sealedKey)
            }
        }
        let sealedSize = try encode(rotation).count + Self.sealingOverhead
        let carried = Meshchat_MeshChatControl.with {
            $0.sealedMessage = Meshchat_SealedMessage.with {
                $0.roomID = u(-1)
                $0.generation = UInt32(Int32.max)
                $0.ciphertext = bytes(sealedSize)
            }
        }

        assertFitsDirect(try encode(carried).count, "sealed key rotation")
    }

    @Test func aPersonCardWithItsPhoneKeyFitsOneSealedPacket() throws {
        let card = Meshchat_MeshChatControl.with {
            $0.version = 1
            $0.personCard = Meshchat_PersonCard.with {
                $0.name = String(repeating: "a", count: 39)
                $0.tag = "abcd"
                $0.colourSlotPlusOne = UInt32(Int32.max)
                $0.phoneKey = bytes(Self.phoneKey)
            }
        }
        let sealedSize = try encode(card).count + Self.sealingOverhead
        let carried = Meshchat_MeshChatControl.with {
            $0.sealedMessage = Meshchat_SealedMessage.with {
                $0.roomID = u(-1)
                $0.generation = UInt32(Int32.max)
                $0.ciphertext = bytes(sealedSize)
            }
        }
        let size = try encode(carried).count

        #expect(
            size <= Self.payloadLen,
            "A sealed person card is \(size) bytes, over the \(Self.payloadLen)-byte payload")
    }

    // MARK: Helpers

    private func assertFitsDirect(_ size: Int, _ what: String) {
        let budget = Self.payloadLen - MeshConstants.pkcOverhead
        #expect(size <= budget, "A \(what) is \(size) bytes, over the \(budget) bytes a PKI direct message leaves")
    }

    private func assertFitsBroadcast(_ size: Int, _ what: String) {
        #expect(size <= Self.payloadLen, "A \(what) is \(size) bytes, over the \(Self.payloadLen)-byte payload")
    }

    /// What `plainSize` bytes of control message become once sealed in a room and wrapped to travel.
    private func sealedInRoom(_ plainSize: Int) throws -> Int {
        let carried = Meshchat_MeshChatControl.with {
            $0.sealedMessage = Meshchat_SealedMessage.with {
                $0.roomID = u(-1)
                $0.generation = UInt32(Int32.max)
                $0.ciphertext = bytes(plainSize + Self.sealingOverhead)
            }
        }
        return try encode(carried).count
    }

    private func encode(_ control: Meshchat_MeshChatControl) throws -> Data { try control.serializedBytes() }

    private func bytes(_ count: Int) -> Data { Data(repeating: 0x7F, count: count) }

    /// A Kotlin Int written into a uint32/fixed32 field: the same 32 bits, read unsigned.
    private func u(_ value: Int32) -> UInt32 { UInt32(bitPattern: value) }

    private static let broadcastNodeNum: Int32 = -1

    /// Mirrors RoomRepository.MAX_ROSTER_ENTRIES.
    private static let maxRosterEntries = 10

    /// Mirrors WaypointRepository's byte limits on a pin's name and description.
    private static let pinNameBytes = 30
    private static let pinDescriptionBytes = 100

    /// SealedText.overhead, restated so FirepitProtocol need not see FirepitCrypto.
    private static let sealingOverhead = 29

    /// KeyEnvelope.publicKeySize and sealedSize, restated for the same reason.
    private static let phoneKey = 33
    private static let sealedKey = 93

    /// InviteCodec.maxRoomNameBytes.
    private static let maxRoomNameBytes = 11
}
