import Testing

@testable import FirepitProtocol

@Suite struct MessageRetentionTests {
    private let now: Int64 = 1_700_000_000_000

    @Test func aDayOfHistoryDropsAnythingOlderThanADay() {
        #expect(now - MessageRetention.day.cutoff(nowMillis: now) == 24 * 60 * 60 * 1_000)
    }

    @Test func longerSettingsKeepMore() {
        #expect(MessageRetention.week.cutoff(nowMillis: now) < MessageRetention.day.cutoff(nowMillis: now))
        #expect(MessageRetention.month.cutoff(nowMillis: now) < MessageRetention.week.cutoff(nowMillis: now))
    }

    @Test func everySettingDeletesSomethingSoNothingIsKeptForever() {
        for entry in MessageRetention.allCases {
            #expect(entry.cutoff(nowMillis: now) < now)
        }
    }

    @Test func aWeekIsWhatYouGetWithoutChoosing() {
        #expect(MessageRetention.default == .week)
        #expect(MessageRetention.named(name: nil) == .week)
    }

    @Test func aSettingThatNoLongerExistsFallsBackToTheDefaultNotToKeeping() {
        #expect(MessageRetention.named(name: "FOREVER") == .week)
    }

    @Test func aStoredSettingIsReadBackAsItself() {
        for entry in MessageRetention.allCases {
            #expect(MessageRetention.named(name: entry.name) == entry)
        }
    }
}
