import FirepitModel
import Foundation

/// Allocates and re-packs the radio's 8 channel slots.
///
/// Slot 0 is the primary and is never a room. Rooms live in 1..7 and the docs call for them to stay consecutive, so
/// leaving a room shifts the later ones down rather than leaving a hole.
///
/// Everything here is keyed by [RoomChannel.id] — the stable room identifier — never by slot index, which moves.
public enum ChannelSlotManager {
    public static let primarySlot = 0
    public static let firstRoomSlot = 1
    public static let lastRoomSlot = 7
    public static let maxRooms = lastRoomSlot - firstRoomSlot + 1

    /// A slot that must be written to the radio to reach the desired layout.
    public struct SlotWrite: Hashable, Sendable {
        public var index: Int
        public var channel: RoomChannel?

        public init(index: Int, channel: RoomChannel?) {
            self.index = index
            self.channel = channel
        }
    }

    public static func rooms(channels: [RoomChannel]) -> [RoomChannel] {
        channels
            .filter { $0.index >= firstRoomSlot && $0.index <= lastRoomSlot && $0.role == .secondary }
            .sorted { $0.index < $1.index }
    }

    /// Lowest free room slot, or nil when all seven are taken.
    public static func nextFreeSlot(channels: [RoomChannel]) -> Int? {
        let used = Set(rooms(channels: channels).map { $0.index })
        for slot in firstRoomSlot...lastRoomSlot {
            if !used.contains(slot) { return slot }
        }
        return nil
    }

    public static func isFull(channels: [RoomChannel]) -> Bool {
        nextFreeSlot(channels: channels) == nil
    }

    public static func findByRoomId(channels: [RoomChannel], roomId: Int32) -> RoomChannel? {
        rooms(channels: channels).first { $0.id == roomId }
    }

    /// Writes needed to remove [roomId] and close the gap it leaves.
    ///
    /// Returns the shifted rooms followed by a disable for the slot that falls off the end. Empty when the room is not
    /// present, so a repeated leave is harmless.
    public static func writesForLeaving(channels: [RoomChannel], roomId: Int32) -> [SlotWrite] {
        guard let slot = slotOf(channels: channels, roomId: roomId) else { return [] }
        return writesForLeavingSlot(channels: channels, slot: slot)
    }

    /// The same, naming the channel by its slot. A Meshtastic channel has no id of its own, so two of them both answer
    /// to id 0 and only the slot says which one is meant.
    public static func writesForLeavingSlot(channels: [RoomChannel], slot: Int) -> [SlotWrite] {
        let current = rooms(channels: channels)
        if !current.contains(where: { $0.index == slot }) { return [] }

        let remaining = current.filter { $0.index != slot }
        let writes = remaining.enumerated().compactMap { position, room -> SlotWrite? in
            let target = firstRoomSlot + position
            // Only rewrite the ones that actually move.
            if room.index == target { return nil }
            var moved = room
            moved.index = target
            return SlotWrite(index: target, channel: moved)
        }

        let lastUsed = firstRoomSlot + remaining.count
        return writes + [SlotWrite(index: lastUsed, channel: nil)]
    }

    /// Where each remaining room ends up, as oldSlot to newSlot.
    ///
    /// Ascending, and the leaving room's slot is freed first, so applying these in order never writes onto a slot still
    /// holding something.
    public static func slotMovesForLeaving(channels: [RoomChannel], roomId: Int32) -> [(Int, Int)] {
        guard let slot = slotOf(channels: channels, roomId: roomId) else { return [] }
        return slotMovesForLeavingSlot(channels: channels, slot: slot)
    }

    /// The same, naming the channel by its slot.
    public static func slotMovesForLeavingSlot(channels: [RoomChannel], slot: Int) -> [(Int, Int)] {
        let current = rooms(channels: channels)
        if !current.contains(where: { $0.index == slot }) { return [] }
        return current.filter { $0.index != slot }
            .enumerated()
            .compactMap { position, room -> (Int, Int)? in
                let target = firstRoomSlot + position
                if room.index == target { return nil }
                return (room.index, target)
            }
    }

    /// The slot a room occupies, or null when it is not one of ours.
    public static func slotOf(channels: [RoomChannel], roomId: Int32) -> Int? {
        rooms(channels: channels).first { $0.id == roomId }?.index
    }

    /// The layout [writesForLeaving] produces, used to assert the result.
    public static func layoutAfterLeaving(channels: [RoomChannel], roomId: Int32) -> [RoomChannel] {
        guard let slot = slotOf(channels: channels, roomId: roomId) else { return rooms(channels: channels) }
        return rooms(channels: channels)
            .filter { $0.index != slot }
            .enumerated()
            .map { position, room in
                var moved = room
                moved.index = firstRoomSlot + position
                return moved
            }
    }
}
