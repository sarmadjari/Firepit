import Testing

@testable import FirepitProtocol

@Suite struct RoomLifetimeTests {
    private let now: Int64 = 1_800_000_000_000

    private func daysAgo(_ n: Int64) -> Int64 {
        now - Duration.days(n).inWholeMilliseconds
    }

    @Test func keepingRoomsForeverForgetsNothing() {
        let rooms: [Int32: Int64] = [1: daysAgo(400), 2: daysAgo(1000)]
        #expect(RoomLifetime.silentRooms(lastActivity: rooms, lifetime: .forever, nowMillis: now).isEmpty)
    }

    @Test func aRoomStillBeingTalkedInIsKept() {
        let rooms: [Int32: Int64] = [1: daysAgo(2)]
        #expect(RoomLifetime.silentRooms(lastActivity: rooms, lifetime: .month, nowMillis: now).isEmpty)
    }

    @Test func aRoomSilentPastTheLimitIsForgotten() {
        let rooms: [Int32: Int64] = [1: daysAgo(31), 2: daysAgo(2)]
        #expect(RoomLifetime.silentRooms(lastActivity: rooms, lifetime: .month, nowMillis: now) == [1])
    }

    @Test func theBoundaryItselfIsNotPastIt() {
        let rooms: [Int32: Int64] = [1: daysAgo(30)]
        #expect(RoomLifetime.silentRooms(lastActivity: rooms, lifetime: .month, nowMillis: now).isEmpty)
    }

    @Test func aRoomWhoseLastWordIsInTheFutureIsLeftAlone() {
        let rooms: [Int32: Int64] = [1: now + Duration.days(10).inWholeMilliseconds]
        #expect(RoomLifetime.silentRooms(lastActivity: rooms, lifetime: .month, nowMillis: now).isEmpty)
    }

    @Test func aRoomWithNoRecordedActivityAtAllIsLeftAlone() {
        let rooms: [Int32: Int64] = [1: 0]
        #expect(RoomLifetime.silentRooms(lastActivity: rooms, lifetime: .month, nowMillis: now).isEmpty)
    }

    @Test func threeMonthsKeepsWhatOneMonthWouldNot() {
        let rooms: [Int32: Int64] = [1: daysAgo(45)]
        #expect(RoomLifetime.silentRooms(lastActivity: rooms, lifetime: .month, nowMillis: now) == [1])
        #expect(RoomLifetime.silentRooms(lastActivity: rooms, lifetime: .quarter, nowMillis: now).isEmpty)
    }

    @Test func anUnknownStoredNameFallsBackToKeepingRooms() {
        #expect(RoomLifetime.named(name: "WEEKLY") == .forever)
        #expect(RoomLifetime.named(name: nil) == .forever)
        #expect(RoomLifetime.named(name: "QUARTER") == .quarter)
    }
}
