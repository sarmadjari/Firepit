import Foundation
import Testing

@testable import FirepitCrypto

/// Ported from android/core/crypto RoomRatchetTest, with the same known answers.
@Suite("Room ratchet")
struct RoomRatchetTests {
    let key = Data((0..<32).map { UInt8($0) })
    let room: Int32 = 0x0BAD_F00D
    let generation = 3
    let hour = 491_234

    /// Worked out with nothing but HMAC-SHA256, from the construction as written down.
    @Test func eachStepIsTheHkdfExpandStepTheProtocolDescribes() {
        #expect(
            RoomRatchet.next(key: key, roomId: room, generation: generation, hour: hour).hex
                == "90df36133102758829f442dbd61819a32e04e31ef09acb473aaf404fe309d34c")
        #expect(
            RoomRatchet.forward(key: key, roomId: room, generation: generation, from: hour, to: hour + 3)?.hex
                == "e27d1cb016f0efae819ccc3ba6895d1d8b05cf67f678dc73def65434c84f5a3c")
        #expect(
            RoomRatchet.senderKey(hourKey: key, roomId: room, generation: generation, hour: hour, sender: 42).hex
                == "23df9036fc639fe2dada022b023458cc1c9554b134ceeb24ef66a950d4822a8b")
    }

    @Test func movingOnSeveralHoursAtOnceLandsWhereMovingOneAtATimeDoes() {
        var stepped = key
        for offset in 0..<5 {
            stepped = RoomRatchet.next(key: stepped, roomId: room, generation: generation, hour: hour + offset)
        }
        #expect(
            RoomRatchet.forward(key: key, roomId: room, generation: generation, from: hour, to: hour + 5) == stepped)
        #expect(RoomRatchet.forward(key: key, roomId: room, generation: generation, from: hour, to: hour) == key)
    }

    @Test func thereIsNoWayBackToAnEarlierHour() {
        #expect(RoomRatchet.forward(key: key, roomId: room, generation: generation, from: hour, to: hour - 1) == nil)
    }

    @Test func aClockDecadesOutIsRefusedRatherThanSpunThrough() {
        #expect(
            RoomRatchet.forward(key: key, roomId: room, generation: generation, from: hour, to: Int(Int32.max))
                == nil)
    }

    @Test func everyRoomGenerationAndHourHasItsOwnKey() {
        let base = RoomRatchet.next(key: key, roomId: room, generation: generation, hour: hour)
        #expect(base != RoomRatchet.next(key: key, roomId: room + 1, generation: generation, hour: hour))
        #expect(base != RoomRatchet.next(key: key, roomId: room, generation: generation + 1, hour: hour))
        #expect(base != RoomRatchet.next(key: key, roomId: room, generation: generation, hour: hour + 1))
    }

    @Test func noTwoSendersSealUnderTheSameKey() {
        let mine = RoomRatchet.senderKey(hourKey: key, roomId: room, generation: generation, hour: hour, sender: 42)
        #expect(
            mine != RoomRatchet.senderKey(hourKey: key, roomId: room, generation: generation, hour: hour, sender: 43))
        #expect(
            mine == RoomRatchet.senderKey(hourKey: key, roomId: room, generation: generation, hour: hour, sender: 42))
    }

    @Test func hoursAreCountedInUtcFrom1970() {
        #expect(RoomRatchet.hourOf(unixMillis: 0) == 0)
        #expect(RoomRatchet.hourOf(unixMillis: 3_599_999) == 0)
        #expect(RoomRatchet.hourOf(unixMillis: 3_600_000) == 1)
        #expect(RoomRatchet.hourOf(unixMillis: -1) == -1)
        #expect(RoomRatchet.hourOf(unixMillis: 1_767_225_600_000) == RoomRatchet.legacyHour)
    }

    @Test func theTwoBytesOnTheAirAreEnoughToFindTheHour() {
        #expect(RoomRatchet.hourNear(tag: RoomRatchet.tagOf(hour), near: hour) == hour)
        #expect(RoomRatchet.hourNear(tag: RoomRatchet.tagOf(hour - 1), near: hour) == hour - 1)
        #expect(RoomRatchet.hourNear(tag: RoomRatchet.tagOf(hour + 1), near: hour) == hour + 1)
    }

    @Test func theHourIsFoundAcrossThePointWhereTheTwoBytesWrap() {
        let justAfter = 8 * 65_536 + 1
        #expect(RoomRatchet.hourNear(tag: RoomRatchet.tagOf(justAfter - 3), near: justAfter) == justAfter - 3)
        #expect(RoomRatchet.hourNear(tag: RoomRatchet.tagOf(justAfter + 1), near: justAfter - 2) == justAfter + 1)
    }

    @Test func aMemberOpensTheHourGoneThisOneAndTheNext() {
        let held = hour - 1
        #expect(!RoomRatchet.opens(heldHour: held, nowHour: hour, hour: hour - 2))
        #expect(RoomRatchet.opens(heldHour: held, nowHour: hour, hour: hour - 1))
        #expect(RoomRatchet.opens(heldHour: held, nowHour: hour, hour: hour))
        #expect(RoomRatchet.opens(heldHour: held, nowHour: hour, hour: hour + 1))
        #expect(!RoomRatchet.opens(heldHour: held, nowHour: hour, hour: hour + 2))
    }

    @Test func somebodyWhoJoinedThisHourReadsNothingFromTheHourBefore() {
        #expect(!RoomRatchet.opens(heldHour: hour, nowHour: hour, hour: hour - 1))
        #expect(RoomRatchet.opens(heldHour: hour, nowHour: hour, hour: hour))
    }

    @Test func aClockSetBackNeitherReopensOldHoursNorSealsUnderOne() {
        let held = hour - 1
        let setBack = hour - 5
        #expect(RoomRatchet.currentHour(heldHour: held, nowHour: setBack) == held)
        #expect(!RoomRatchet.opens(heldHour: held, nowHour: setBack, hour: hour - 2))
        #expect(RoomRatchet.opens(heldHour: held, nowHour: setBack, hour: held))
        #expect(RoomRatchet.keepFrom(heldHour: held, nowHour: setBack) == held)
    }

    @Test func onlyTheHourJustGoneIsWorthKeeping() {
        #expect(RoomRatchet.keepFrom(heldHour: hour - 30, nowHour: hour) == hour - 1)
        #expect(RoomRatchet.keepFrom(heldHour: hour, nowHour: hour) == hour)
    }
}
