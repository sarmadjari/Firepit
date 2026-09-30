import Testing

@testable import FirepitProtocol

@Suite struct ShareDurationTests {
    private let now: Int64 = 1_757_000_000_000

    @Test func everyTimedChoiceEndsAfterExactlyWhatItSays() {
        for entry in ShareDuration.allCases {
            if let duration = entry.duration {
                #expect(entry.endsAt(nowMillis: now) == now + duration.inWholeMilliseconds)
            }
        }
    }

    @Test func untilOffNeverEndsOnItsOwn() {
        #expect(ShareDuration.untilOff.endsAt(nowMillis: now) == nil)
        #expect(ShareDuration.untilOff.duration == nil)
    }

    @Test func theDefaultIsBounded() throws {
        let duration = try #require(ShareDuration.default.duration)
        #expect(duration <= .hours(24))
    }

    @Test func anUnknownNameFallsBackToTheBoundedDefault() {
        #expect(ShareDuration.named(name: nil) == .default)
        #expect(ShareDuration.named(name: "FOREVER") == .default)
        #expect(ShareDuration.named(name: "") == .default)
    }

    @Test func aStoredNameRoundTrips() {
        for entry in ShareDuration.allCases {
            #expect(ShareDuration.named(name: entry.name) == entry)
        }
    }
}
