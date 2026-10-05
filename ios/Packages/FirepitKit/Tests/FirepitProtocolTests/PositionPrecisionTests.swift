import FirepitModel
import FirepitProtos
import Foundation
import Testing

@testable import FirepitProtocol

@Suite struct PositionPrecisionTests {
    @Test func fullPrecisionIsLeftAlone() {
        let coordinate: Int32 = 512_345_678

        #expect(PositionPrecision.truncate(coordinate: coordinate, precisionBits: PositionPrecision.full) == coordinate)
    }

    @Test func outOfRangePrecisionNeverMovesThePoint() {
        let coordinate: Int32 = 512_345_678

        // 0 means "do not send", and anything above 32 is not a real setting.
        #expect(PositionPrecision.truncate(coordinate: coordinate, precisionBits: 0) == coordinate)
        #expect(PositionPrecision.truncate(coordinate: coordinate, precisionBits: 33) == coordinate)
        #expect(PositionPrecision.truncate(coordinate: coordinate, precisionBits: -1) == coordinate)
    }

    @Test func truncationLandsInTheMiddleOfItsCellNotTheCorner() {
        // 16 bits keeps the top half of the coordinate and re-centres.
        let coordinate = Int32(bitPattern: 0x1234_ABCD)
        let expected = (coordinate & Int32(bitPattern: UInt32.max << 16)) &+ Int32(1 << 15)

        #expect(PositionPrecision.truncate(coordinate: coordinate, precisionBits: 16) == expected)
    }

    @Test func everyPointInACellTruncatesToTheSamePlace() {
        var random = SeededRandom(20260909)
        for _ in 0..<500 {
            let precision = random.nextInt(in: 1..<32)
            let cell = PositionPrecision.cellSize(precisionBits: precision)
            let base = random.nextInt32()

            let first = PositionPrecision.truncate(coordinate: base, precisionBits: precision)
            // Another coordinate inside the same cell, chosen without crossing the boundary.
            let offset = Int64(base) & (cell - 1)
            let sameCell = Int32(truncatingIfNeeded: Int64(base) - offset)
            let second = PositionPrecision.truncate(coordinate: sameCell, precisionBits: precision)

            #expect(first == second, "precision=\(precision) base=\(base)")
        }
    }

    @Test func thePointNeverMovesFurtherThanOneCell() {
        var random = SeededRandom(1)
        for _ in 0..<500 {
            let precision = random.nextInt(in: 1..<32)
            let coordinate = random.nextInt32()

            let moved = PositionPrecision.truncate(coordinate: coordinate, precisionBits: precision)
            let distance = abs(Int64(moved) - Int64(coordinate))

            #expect(
                distance <= PositionPrecision.cellSize(precisionBits: precision),
                "precision=\(precision) moved \(distance) which is over one cell"
            )
        }
    }

    @Test func theResultAlwaysSitsAtTheCentreOfItsCell() {
        var random = SeededRandom(7)
        for _ in 0..<500 {
            let precision = random.nextInt(in: 1..<32)
            let coordinate = random.nextInt32()
            let cell = PositionPrecision.cellSize(precisionBits: precision)

            let moved = Int64(
                UInt32(bitPattern: PositionPrecision.truncate(coordinate: coordinate, precisionBits: precision)))

            #expect(
                moved % cell == cell / 2,
                "precision=\(precision) must land on a cell midpoint, not a corner"
            )
        }
    }

    @Test func coarserPrecisionMeansALargerUncertaintyArea() {
        var previous: Int64 = 0
        for precision in stride(from: 31, through: 1, by: -1) {
            let cell = PositionPrecision.cellSize(precisionBits: precision)
            #expect(cell > previous, "precision \(precision) should be coarser than \(precision + 1)")
            previous = cell
        }
    }
}

@Suite struct PositionSharingTests {
    @Test func aRadioThatBroadcastsNowhereIsCorrect() {
        #expect(PositionSharing.isSilent(channels: [channel(0, 0), channel(1, 0), channel(2, 0)]))
    }

    /// Positions travel sealed by the phone, so the radio's own broadcast is never wanted: it goes out under a channel
    /// key anyone holding a member's radio can read, and it keeps going with the phone away.
    @Test func aRoomBroadcastingFromTheRadioIsNotCorrectEither() {
        #expect(!PositionSharing.isSilent(channels: [channel(0, 0), channel(1, 32)]))
    }

    @Test func aBroadcastingPrimaryIsNotCorrect() {
        #expect(!PositionSharing.isSilent(channels: [channel(0, 13), channel(1, 0)]))
    }

    @Test func silencingZeroesEveryBroadcastingChannelAndTouchesNothingElse() {
        let channels = [
            channel(0, 13, id: 0),
            channel(1, 0, id: 111),
            channel(2, 32, id: 222),
            channel(3, 16, id: 333, kind: .meshtasticPrivate),
        ]

        #expect(
            PositionSharing.writesToSilence(channels: channels) == [
                PrecisionWrite(index: 0, precision: 0),
                PrecisionWrite(index: 2, precision: 0),
                PrecisionWrite(index: 3, precision: 0),
            ]
        )
    }

    @Test func anAlreadySilentRadioNeedsNoWrites() {
        let channels = [channel(0, 0, id: 0), channel(1, 0, id: 111)]

        #expect(
            PositionSharing.writesToSilence(channels: channels).isEmpty,
            "a reconnect check must be silent when nothing is wrong")
    }

    @Test func silencingKeepsOnlyTheActiveSafetyNetRoomPrecision() {
        let writes = PositionSharing.writesToSilence(
            channels: [
                channel(0, 32, id: 0),
                channel(1, 0, id: 111),
                channel(2, 32, id: 222),
                channel(3, 32, id: 333, kind: .meshtasticPrivate),
            ],
            keepSlot: 1)

        #expect(
            writes == [
                PrecisionWrite(index: 0, precision: 0),
                PrecisionWrite(index: 2, precision: 0),
                PrecisionWrite(index: 3, precision: 0),
                PrecisionWrite(index: 1, precision: 32),
            ])
    }

    @Test func silencingWritesAllZeroesBeforeTheSingleFullPrecisionWrite() {
        let writes = PositionSharing.writesToSilence(
            channels: [
                channel(0, 13, id: 0),
                channel(1, 16, id: 111),
                channel(2, 32, id: 222),
            ],
            keepSlot: 1)

        #expect(
            writes == [
                PrecisionWrite(index: 0, precision: 0),
                PrecisionWrite(index: 1, precision: 0),
                PrecisionWrite(index: 2, precision: 0),
                PrecisionWrite(index: 1, precision: 32),
            ])
    }

    /// The trap behind a real bug: a radio we cannot see produces the same empty answer as a radio that is already
    /// correct. Callers must establish that the channels are known *before* reading anything into an empty result.
    @Test func anUnknownRadioIsIndistinguishableFromACorrectOne() {
        let unknown = PositionSharing.writesToSilence(channels: [])
        let correct = PositionSharing.writesToSilence(channels: [channel(0, 0, id: 0)])

        #expect(unknown.isEmpty)
        #expect(correct == unknown, "no write list can tell these apart")
    }

    @Test func onlyAFirepitRoomWhoseKeyWeHoldMayReceiveAPosition() {
        #expect(PositionSharing.canShare(channel: channel(1, 0, kind: .firepit)))
        // The primary sets the frequency and carries NodeInfo; never a position.
        #expect(!PositionSharing.canShare(channel: channel(0, 0, kind: .firepit)))
        // A shared Meshtastic channel reaches people the group never chose.
        #expect(!PositionSharing.canShare(channel: channel(1, 0, kind: .meshtasticPrivate)))
        #expect(!PositionSharing.canShare(channel: channel(2, 0, kind: .meshtasticPublic)))
        // No key to seal with, or a key the rest of the room has moved on from.
        #expect(!PositionSharing.canShare(channel: channel(3, 0, kind: .firepitKeyMissing)))
        #expect(!PositionSharing.canShare(channel: channel(4, 0, kind: .firepitMovedOn)))
    }

    @Test func safetyNetSlotRequiresEveryGuardIncludingNegativeRoomIdAndPrivateKey() {
        let roomId: Int32 = -123_456_789
        let share = SafetyNetShare(roomId: roomId, endsAt: 2_000, radioSafetyNet: true, safetyNetNodeNum: 111)
        let ready = readyPosition()
        let primary = Data(repeating: 9, count: 32)
        let roomKey = Data(repeating: 7, count: 32)
        let room = channel(1, 0, id: roomId, psk: roomKey)

        #expect(
            PositionSharing.safetyNetSlot(
                channels: [room],
                share: share,
                nowMillis: 1_000,
                connectedNodeNum: 111,
                positionConfig: ready,
                heldRoomIds: [roomId],
                primaryKey: primary) == 1)

        #expect(PositionSharing.safetyNetSlot(channels: [room], share: nil, nowMillis: 1_000, connectedNodeNum: 111, positionConfig: ready, heldRoomIds: [roomId], primaryKey: primary) == nil)
        #expect(PositionSharing.safetyNetSlot(channels: [room], share: SafetyNetShare(roomId: roomId, endsAt: 2_000, radioSafetyNet: false, safetyNetNodeNum: 111), nowMillis: 1_000, connectedNodeNum: 111, positionConfig: ready, heldRoomIds: [roomId], primaryKey: primary) == nil)
        #expect(PositionSharing.safetyNetSlot(channels: [room], share: SafetyNetShare(roomId: roomId, endsAt: 1_000, radioSafetyNet: true, safetyNetNodeNum: 111), nowMillis: 1_000, connectedNodeNum: 111, positionConfig: ready, heldRoomIds: [roomId], primaryKey: primary) == nil)
        #expect(PositionSharing.safetyNetSlot(channels: [room], share: SafetyNetShare(roomId: roomId, endsAt: 2_000, radioSafetyNet: true, safetyNetNodeNum: 222), nowMillis: 1_000, connectedNodeNum: 111, positionConfig: ready, heldRoomIds: [roomId], primaryKey: primary) == nil)
        #expect(PositionSharing.safetyNetSlot(channels: [room], share: share, nowMillis: 1_000, connectedNodeNum: 111, positionConfig: readyPosition(secs: 1), heldRoomIds: [roomId], primaryKey: primary) == nil)
        #expect(PositionSharing.safetyNetSlot(channels: [room], share: share, nowMillis: 1_000, connectedNodeNum: 111, positionConfig: ready, heldRoomIds: [roomId], primaryKey: primary, licensedMode: true) == nil)
        #expect(PositionSharing.safetyNetSlot(channels: [channel(1, 0, id: roomId, psk: Data())], share: share, nowMillis: 1_000, connectedNodeNum: 111, positionConfig: ready, heldRoomIds: [roomId], primaryKey: primary) == nil)
        #expect(PositionSharing.safetyNetSlot(channels: [channel(1, 0, id: roomId, psk: Data(repeating: 1, count: 16))], share: share, nowMillis: 1_000, connectedNodeNum: 111, positionConfig: ready, heldRoomIds: [roomId], primaryKey: primary) == nil)
        #expect(PositionSharing.safetyNetSlot(channels: [channel(1, 0, id: roomId, psk: primary)], share: share, nowMillis: 1_000, connectedNodeNum: 111, positionConfig: ready, heldRoomIds: [roomId], primaryKey: primary) == nil)
        #expect(PositionSharing.safetyNetSlot(channels: [channel(1, 0, id: roomId, psk: roomKey, kind: .firepitKeyMissing)], share: share, nowMillis: 1_000, connectedNodeNum: 111, positionConfig: ready, heldRoomIds: [roomId], primaryKey: primary) == nil)
        #expect(PositionSharing.safetyNetSlot(channels: [room], share: share, nowMillis: 1_000, connectedNodeNum: 111, positionConfig: ready, heldRoomIds: [], primaryKey: primary) == nil)
        #expect(PositionSharing.safetyNetSlot(channels: [room, channel(2, 0, id: roomId, psk: roomKey)], share: share, nowMillis: 1_000, connectedNodeNum: 111, positionConfig: ready, heldRoomIds: [roomId], primaryKey: primary) == nil)
    }

    private func channel(
        _ index: Int,
        _ precision: Int,
        id: Int32? = nil,
        psk: Data? = Data(repeating: 7, count: 32),
        kind: RoomKind = .firepit
    ) -> RoomChannel {
        RoomChannel(
            index: index,
            name: "room\(index)",
            role: index == 0 ? .primary : .secondary,
            id: id ?? Int32(index * 100),
            positionPrecision: precision,
            psk: psk,
            kind: kind
        )
    }

    private func readyPosition(secs: UInt32 = 86_400) -> Config.PositionConfig {
        var position = Config.PositionConfig()
        position.gpsMode = .enabled
        position.fixedPosition = false
        position.positionBroadcastSecs = secs
        position.positionBroadcastSmartEnabled = false
        position.positionFlags = UInt32(Config.PositionConfig.PositionFlags.timestamp.rawValue)
        return position
    }
}
