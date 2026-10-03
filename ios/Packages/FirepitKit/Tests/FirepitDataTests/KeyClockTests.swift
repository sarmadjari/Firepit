import FirepitData
import FirepitModel
import Foundation
import Testing

/// Ported from android/core/data KeyClockTest.
@Suite("Key clock")
struct KeyClockTests {
    private static let minute: Int64 = 60_000
    private static let hour: Int64 = 60 * minute
    private static let day: Int64 = 24 * hour

    /// The phone's clocks and the stored anchor, all moved by hand.
    private final class Phone: ClockAnchors {
        let wall = Mutex<Int64>(1_767_225_600_000 + 30 * KeyClockTests.minute)
        let monotonic = Mutex<Int64>(5_000_000)
        let boot = Mutex<String?>("1")
        private let stored = Mutex<ClockAnchor?>(nil)

        func load() -> ClockAnchor? { stored.withLock { $0 } }

        func save(_ anchor: ClockAnchor) { stored.withLock { $0 = anchor } }

        func clock() -> KeyClock {
            KeyClock(
                wall: { [wall] in wall.withLock { $0 } },
                monotonic: { [monotonic] in monotonic.withLock { $0 } },
                boot: { [boot] in boot.withLock { $0 } },
                anchors: self)
        }

        var now: Int64 { wall.withLock { $0 } }

        func pass(_ millis: Int64) {
            wall.withLock { $0 += millis }
            monotonic.withLock { $0 += millis }
        }

        func setWall(by millis: Int64) { wall.withLock { $0 += millis } }
    }

    @Test func whileTheClockKeepsStepErasingFollowsIt() {
        let phone = Phone()
        let clock = phone.clock()
        #expect(clock.eraseMillis() == phone.now)

        phone.pass(3 * Self.hour)
        #expect(clock.eraseMillis() == phone.now)
    }

    @Test func aClockSetAheadDoesNotEraseAheadOfTheTimeReallyPassed() {
        let phone = Phone()
        let clock = phone.clock()
        _ = clock.eraseMillis()
        let real = phone.now

        phone.setWall(by: 5 * Self.hour)
        #expect(clock.eraseMillis() == real)

        phone.pass(Self.hour)
        #expect(clock.eraseMillis() == real + Self.hour)
    }

    @Test func aClockPutRightAgainIsFollowedFromWhereRealTimeHasGotTo() {
        let phone = Phone()
        let clock = phone.clock()
        _ = clock.eraseMillis()
        phone.setWall(by: 5 * Self.hour)
        _ = clock.eraseMillis()

        phone.setWall(by: -5 * Self.hour)
        phone.pass(10 * Self.minute)
        #expect(clock.eraseMillis() == phone.now)
    }

    @Test func aClockSetBackHoldsErasingBackWithIt() {
        let phone = Phone()
        let clock = phone.clock()
        _ = clock.eraseMillis()

        phone.setWall(by: -2 * Self.hour)
        #expect(clock.eraseMillis() == phone.now)
    }

    @Test func aSmallCorrectionCountsAsKeepingStep() {
        let phone = Phone()
        let clock = phone.clock()
        _ = clock.eraseMillis()

        phone.setWall(by: 2 * Self.minute)
        #expect(clock.eraseMillis() == phone.now)
    }

    @Test func sealingFollowsThePhonesClockEvenAWrongOne() {
        let phone = Phone()
        let clock = phone.clock()
        _ = clock.eraseMillis()

        phone.setWall(by: 5 * Self.hour)
        #expect(clock.wallMillis() == phone.now)
    }

    @Test func restartingTheAppDoesNotForgetWhatRealTimeItIs() {
        let phone = Phone()
        _ = phone.clock().eraseMillis()
        let real = phone.now

        // The clock jumps while the app is not running.
        phone.setWall(by: 5 * Self.hour)
        #expect(phone.clock().eraseMillis() == real)
    }

    @Test func afterARestartOfThePhoneTheClockIsTakenAsItIs() {
        let phone = Phone()
        _ = phone.clock().eraseMillis()

        phone.boot.withLock { $0 = "2" }
        phone.monotonic.withLock { $0 = 1_000 }
        phone.setWall(by: 3 * Self.day)
        #expect(phone.clock().eraseMillis() == phone.now)
    }

    @Test func aNewBootsAnchorReplacesTheLastOneAtOnce() {
        let phone = Phone()
        _ = phone.clock().eraseMillis()

        phone.boot.withLock { $0 = "2" }
        phone.monotonic.withLock { $0 = 1_000 }
        _ = phone.clock().eraseMillis()
        #expect(phone.load()?.boot == "2")
        #expect(phone.load()?.monotonicMillis == 1_000)
    }

    @Test func withoutABootIdARunningAppStillGuardsAgainstAClockSetAhead() {
        let phone = Phone()
        phone.boot.withLock { $0 = nil }
        let clock = phone.clock()
        _ = clock.eraseMillis()
        let real = phone.now

        phone.setWall(by: 5 * Self.hour)
        #expect(clock.eraseMillis() == real)
    }

    @Test func withoutABootIdNothingOutlivesTheAppSoNoAnchorFromBeforeARestartCanHoldErasingBack() {
        let phone = Phone()
        phone.boot.withLock { $0 = nil }
        _ = phone.clock().eraseMillis()
        #expect(phone.load() == nil)

        phone.monotonic.withLock { $0 += 3 * Self.day }
        phone.setWall(by: 5 * Self.day)
        #expect(phone.clock().eraseMillis() == phone.now)
    }

    @Test func aClockTheRoomAgreesWithIsTakenAsRight() {
        let phone = Phone()
        let clock = phone.clock()
        _ = clock.eraseMillis()

        // Put right after the phone started, say: ahead of what the anchor thinks.
        phone.setWall(by: 3 * Self.hour)
        _ = clock.eraseMillis()
        clock.agreed()
        #expect(clock.eraseMillis() == phone.now)
    }

    @Test func thePhonesOwnClocksAreReadable() {
        let clock = KeyClock.system(defaults: UserDefaults(suiteName: "firepit-key-clock-\(UUID().uuidString)")!)
        let wall = clock.wallMillis()
        #expect(abs(wall - Int64(Date().timeIntervalSince1970 * 1_000)) < 5_000)
        #expect(clock.eraseMillis() >= wall)
    }
}
