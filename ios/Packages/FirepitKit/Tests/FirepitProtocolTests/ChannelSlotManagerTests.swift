import FirepitModel
import Foundation
import Testing

@testable import FirepitProtocol

@Suite struct ChannelSlotManagerTests {
    @Test func firstRoomTakesSlotOneNotThePrimary() {
        let channels = [primary()]

        #expect(ChannelSlotManager.nextFreeSlot(channels: channels) == 1)
    }

    @Test func roomsFillTheLowestFreeSlot() {
        let channels = [primary(), room(1, 100), room(2, 200)]

        #expect(ChannelSlotManager.nextFreeSlot(channels: channels) == 3)
    }

    @Test func aGapIsReusedBeforeExtending() {
        let channels = [primary(), room(1, 100), room(3, 300)]

        #expect(ChannelSlotManager.nextFreeSlot(channels: channels) == 2, "slot 2 is free and must be used first")
    }

    @Test func sevenRoomsIsTheCeiling() {
        let channels = [primary()] + (1...7).map { room($0, Int32($0 * 100)) }

        #expect(ChannelSlotManager.nextFreeSlot(channels: channels) == nil)
        #expect(ChannelSlotManager.isFull(channels: channels))
    }

    @Test func leavingTheLastRoomOnlyDisablesItsSlot() {
        let channels = [primary(), room(1, 100), room(2, 200)]

        let writes = ChannelSlotManager.writesForLeaving(channels: channels, roomId: 200)

        #expect(writes == [ChannelSlotManager.SlotWrite(index: 2, channel: nil)])
    }

    @Test func leavingAMiddleRoomShiftsTheLaterOnesDown() throws {
        let channels = [primary(), room(1, 100), room(2, 200), room(3, 300)]

        let writes = ChannelSlotManager.writesForLeaving(channels: channels, roomId: 200)

        #expect(writes.count == 2)
        #expect(writes[0].index == 2)
        #expect(writes[0].channel?.id == 300, "room 300 moves into the freed slot")
        #expect(writes[1] == ChannelSlotManager.SlotWrite(index: 3, channel: nil), "the tail slot is disabled")
    }

    @Test func leavingARoomThatIsNotPresentDoesNothing() {
        let channels = [primary(), room(1, 100)]

        #expect(ChannelSlotManager.writesForLeaving(channels: channels, roomId: 999).isEmpty)
    }

    @Test func thePrimaryIsNeverTreatedAsARoom() {
        let channels = [primary(), room(1, 100)]

        #expect(ChannelSlotManager.rooms(channels: channels).map(\.id) == [100])
        #expect(ChannelSlotManager.writesForLeaving(channels: channels, roomId: primaryId).isEmpty)
    }

    @Test func anySequenceOfLeavesKeepsSlotsConsecutiveAndPreservesRooms() {
        var random = SeededRandom(20260909)

        for iteration in 0..<200 {
            let count = random.nextInt(in: 1..<(ChannelSlotManager.maxRooms + 1))
            var channels = [primary()] + (1...count).map { room($0, Int32($0 * 1_000)) }
            var expected = ChannelSlotManager.rooms(channels: channels).map(\.id)

            while !expected.isEmpty {
                let leavingIndex = random.nextInt(in: 0..<expected.count)
                let leaving = expected[leavingIndex]
                let layout = ChannelSlotManager.layoutAfterLeaving(channels: channels, roomId: leaving)

                expected = expected.filter { $0 != leaving }

                let expectedSlots = expected.isEmpty ? [] : Array(1...expected.count)
                #expect(
                    layout.map(\.index) == expectedSlots,
                    "iteration \(iteration): slots must stay consecutive from 1"
                )
                #expect(
                    layout.map(\.id) == expected,
                    "iteration \(iteration): remaining rooms must survive, in order"
                )
                // Settings must travel with the room, never with the slot.
                for moved in layout {
                    #expect(moved.name == "room-\(moved.id)", "room \(moved.id) kept its name")
                    #expect(moved.positionPrecision == 32, "room \(moved.id) kept its precision")
                }

                channels = [primary()] + layout
            }
        }
    }

    @Test func historyFollowsEachRoomToTheSlotItMovesInto() {
        let channels = [room(1, 11), room(2, 22), room(3, 33)]

        #expect(
            ChannelSlotManager.slotMovesForLeaving(channels: channels, roomId: 11).map { [$0.0, $0.1] } == [
                [2, 1], [3, 2],
            ])
    }

    @Test func leavingTheLastRoomMovesNothing() {
        let channels = [room(1, 11), room(2, 22), room(3, 33)]

        #expect(ChannelSlotManager.slotMovesForLeaving(channels: channels, roomId: 33).isEmpty)
    }

    @Test func aRoomThatIsNotOursMovesNothing() {
        #expect(ChannelSlotManager.slotMovesForLeaving(channels: [room(1, 11)], roomId: 99).isEmpty)
    }

    @Test func movesNeverWriteOntoASlotStillInUse() {
        let channels = [room(1, 11), room(2, 22), room(3, 33), room(4, 44)]
        var occupied = Set(channels.map(\.index))
        if let slot = ChannelSlotManager.slotOf(channels: channels, roomId: 22) {
            occupied.remove(slot)
        }

        for (from, to) in ChannelSlotManager.slotMovesForLeaving(channels: channels, roomId: 22) {
            #expect(!occupied.contains(to), "slot \(to) was still taken")
            occupied.remove(from)
            occupied.insert(to)
        }
    }

    @Test func theSlotARoomSitsInIsReportedForItsHistory() {
        let channels = [room(1, 11), room(2, 22)]

        #expect(ChannelSlotManager.slotOf(channels: channels, roomId: 22) == 2)
        #expect(ChannelSlotManager.slotOf(channels: channels, roomId: 99) == nil)
    }

    /// A write names the slot a room is moving *to*, so its key has to be read from the slot it is leaving. Reading the
    /// destination instead gave every room above the one left the key of the room below it.
    @Test func everyRoomThatMovesCanBeTracedBackToTheSlotItHoldsNow() throws {
        let channels = [room(1, 11), room(2, 22), room(3, 33), room(4, 44)]
        let from = Dictionary(
            uniqueKeysWithValues: ChannelSlotManager.slotMovesForLeaving(channels: channels, roomId: 22).map {
                ($0.1, $0.0)
            })

        let moving = ChannelSlotManager.writesForLeaving(channels: channels, roomId: 22).compactMap {
            write -> (Int, RoomChannel)? in
            guard let channel = write.channel else { return nil }
            return (write.index, channel)
        }

        #expect(moving.count == 2)
        for (target, room) in moving {
            let source = try #require(from[target])
            #expect(room.id == channels.first { $0.index == source }?.id, "slot \(source) holds a different room")
        }
    }

    /// Meshtastic channels have no id of their own, so two of them both answer to 0. Leaving one by id used to take
    /// every id-less channel out of the layout; by slot, only the one meant goes.
    @Test func leavingOneOfTwoChannelsWithoutAnIdKeepsTheOther() {
        let channels = [primary(), room(1, 0), room(2, 0), room(3, 300)]

        let writes = ChannelSlotManager.writesForLeavingSlot(channels: channels, slot: 1)

        #expect(
            writes == [
                ChannelSlotManager.SlotWrite(index: 1, channel: withIndex(room(2, 0), 1)),
                ChannelSlotManager.SlotWrite(index: 2, channel: withIndex(room(3, 300), 2)),
                ChannelSlotManager.SlotWrite(index: 3, channel: nil),
            ]
        )
        #expect(
            ChannelSlotManager.slotMovesForLeavingSlot(channels: channels, slot: 1).map { [$0.0, $0.1] } == [
                [2, 1], [3, 2],
            ])
    }

    @Test func aSlotWithNothingInItLeavesNothingToWrite() {
        let channels = [primary(), room(1, 100)]

        #expect(ChannelSlotManager.writesForLeavingSlot(channels: channels, slot: 4).isEmpty)
        #expect(ChannelSlotManager.slotMovesForLeavingSlot(channels: channels, slot: 4).isEmpty)
    }

    private let primaryId: Int32 = 0x4D455348

    private func primary() -> RoomChannel {
        RoomChannel(
            index: 0,
            name: "MeshChat",
            role: .primary,
            id: primaryId,
            positionPrecision: 0
        )
    }

    private func room(_ index: Int, _ id: Int32) -> RoomChannel {
        RoomChannel(
            index: index,
            name: "room-\(id)",
            role: .secondary,
            id: id,
            positionPrecision: 32
        )
    }

    private func withIndex(_ channel: RoomChannel, _ index: Int) -> RoomChannel {
        var moved = channel
        moved.index = index
        return moved
    }
}
