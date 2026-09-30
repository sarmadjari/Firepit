import FirepitModel
import Foundation
import Testing

@testable import Firepit

/// Port of android/app/src/test/…/chat/ChatItemsTest.kt.
@Suite("Chat items")
struct ChatItemsTests {
    private let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()
    private let locale = Locale(identifier: "en_GB")
    private let today = LocalDay(year: 2026, month: 9, day: 9)

    /// Midday on `dayOfMonth`, plus `minutes`.
    private func at(_ dayOfMonth: Int, minutes: Int64 = 0) -> Int64 {
        let noon = calendar.date(from: DateComponents(year: 2026, month: 9, day: dayOfMonth, hour: 12))!
        return Int64(noon.timeIntervalSince1970 * 1000) + minutes * 60_000
    }

    private func message(
        _ id: Int32,
        from: Int32,
        at: Int64,
        outgoing: Bool = false,
        replyId: Int32? = nil,
        rxTime: Int64? = nil
    ) -> ChatMessage {
        ChatMessage(
            id: id, channel: 1, fromNodeNum: from, toNodeNum: -1, text: "m\(id)", sentAt: at, rxTime: rxTime,
            isOutgoing: outgoing, replyId: replyId)
    }

    private func build(_ messages: [ChatMessage]) -> [ChatItem] {
        buildChatItems(messages, today: today, calendar: calendar, locale: locale)
    }

    private struct Bubble {
        let message: ChatMessage
        let isFirstInGroup: Bool
        let isLastInGroup: Bool
    }

    private func bubbles(_ items: [ChatItem]) -> [Bubble] {
        items.compactMap { item in
            guard case .bubble(let message, let first, let last) = item else { return nil }
            return Bubble(message: message, isFirstInGroup: first, isLastInGroup: last)
        }
    }

    private func dayLabels(_ items: [ChatItem]) -> [String] {
        items.compactMap { item in
            guard case .day(let label, _) = item else { return nil }
            return label
        }
    }

    @Test func anEmptyChannelProducesNoRows() {
        #expect(build([]).isEmpty)
    }

    @Test func eachDayGetsOneMarker() {
        let items = build([
            message(1, from: 7, at: at(7)),
            message(2, from: 7, at: at(8)),
            message(3, from: 7, at: at(9)),
        ])

        #expect(dayLabels(items) == ["7 September", "Yesterday", "Today"])
    }

    @Test func aBurstFromOneSenderBecomesASingleBlock() {
        let items = build([
            message(1, from: 7, at: at(9, minutes: 0)),
            message(2, from: 7, at: at(9, minutes: 1)),
            message(3, from: 7, at: at(9, minutes: 2)),
        ])

        let grouped = bubbles(items)
        #expect(grouped.map(\.isFirstInGroup) == [true, false, false])
        #expect(grouped.map(\.isLastInGroup) == [false, false, true])
    }

    @Test func aGapLongerThanTheWindowStartsANewBlock() {
        let items = build([
            message(1, from: 7, at: at(9, minutes: 0)),
            message(2, from: 7, at: at(9, minutes: 30)),
        ])

        let grouped = bubbles(items)
        let allOpenABlock = grouped.allSatisfy(\.isFirstInGroup)
        let allCloseABlock = grouped.allSatisfy(\.isLastInGroup)
        #expect(allOpenABlock)
        #expect(allCloseABlock)
    }

    @Test func aDifferentSenderBreaksTheBlock() {
        let items = build([
            message(1, from: 7, at: at(9, minutes: 0)),
            message(2, from: 8, at: at(9, minutes: 1)),
        ])

        let allOpenABlock = bubbles(items).allSatisfy(\.isFirstInGroup)
        #expect(allOpenABlock)
    }

    @Test func myOwnMessageNeverGroupsWithSomeoneElsesAtTheSameMoment() {
        let items = build([
            message(1, from: 7, at: at(9, minutes: 0), outgoing: false),
            message(2, from: 7, at: at(9, minutes: 0), outgoing: true),
        ])

        #expect(bubbles(items)[1].isFirstInGroup)
    }

    @Test func aReplyStandsAloneSoItsQuoteIsNotBuriedInABlock() {
        let items = build([
            message(1, from: 7, at: at(9, minutes: 0)),
            message(2, from: 7, at: at(9, minutes: 1), replyId: 1),
            message(3, from: 7, at: at(9, minutes: 2)),
        ])

        let grouped = bubbles(items)
        #expect(grouped[0].isLastInGroup, "the message before a reply must close its block")
        #expect(grouped[1].isFirstInGroup, "a reply starts its own block so the quote reads clearly")
        #expect(!grouped[2].isFirstInGroup, "a plain follow-up continues the reply's block")
        #expect(grouped[2].isLastInGroup)
    }

    @Test func theRadioClockDecidesTheDayWhenTheTwoClocksAgree() {
        let items = build([message(1, from: 7, at: at(9), rxTime: at(9) - 60_000)])

        #expect(dayLabels(items).first == "Today")
    }

    /// Observed on a radio whose clock was fifteen hours behind: a message that had just arrived was filed under
    /// yesterday, above the replies to it.
    @Test func aRadioClockFarFromThePhonesIsNotBelieved() {
        let items = build([message(1, from: 7, at: at(9), rxTime: at(7))])

        #expect(dayLabels(items).first == "Today")
    }

    /// A radio that has never been told the time once put 1970 between two of today's messages, which repeated a day
    /// key and brought the screen down.
    @Test func aWrongRadioClockCannotRepeatADayKey() {
        let keys = build([
            message(1, from: 7, at: at(9)),
            message(2, from: 7, at: at(9, minutes: 1), rxTime: 0),
            message(3, from: 7, at: at(9, minutes: 2)),
        ]).map(\.id)

        #expect(keys.count == Set(keys).count)
        #expect(keys.filter { $0 == .day("day-2026-09-09") }.count == 1)
    }

    @Test func aDisbelievedRadioClockLeavesAMessageWhereItArrived() {
        let items = build([
            message(1, from: 7, at: at(9)),
            message(2, from: 7, at: at(9, minutes: 1), rxTime: at(7)),
            message(3, from: 7, at: at(9, minutes: 2)),
        ])
        let keys = items.map(\.id)

        #expect(keys.count == Set(keys).count)
        #expect(bubbles(items).map(\.message.id) == [1, 2, 3])
    }
}

/// Port of android/app/src/test/…/chat/MessageTimestampTest.kt. Android always prints a 24-hour `HH:mm` and English
/// month names; iOS follows the locale (en_GB currently writes "9:07", not "09:07"). So these pin en_GB and UTC and
/// build the expected clock and date with the same locale, checking which parts appear when — the rules Android's
/// tests pin down.
@Suite("Message timestamps")
struct MessageTimestampTests {
    private let calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()
    private let locale = Locale(identifier: "en_GB")
    private let today = LocalDay(year: 2026, month: 9, day: 10)

    private func date(_ year: Int, _ month: Int, _ day: Int, _ hour: Int = 12, _ minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
    }

    private func format(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int) -> String {
        let moment = date(year, month, day, hour, minute)
        return MessageTimestamp.format(
            Int64(moment.timeIntervalSince1970 * 1000), today: today, calendar: calendar,
            locale: locale)
    }

    private var style: Date.FormatStyle {
        Date.FormatStyle(
            date: .omitted, time: .omitted, locale: locale, calendar: calendar, timeZone: calendar.timeZone)
    }

    /// The clock as this locale writes it.
    private func clock(_ hour: Int, _ minute: Int) -> String {
        date(2026, 9, 10, hour, minute).formatted(style.hour(.defaultDigits(amPM: .abbreviated)).minute(.twoDigits))
    }

    /// The day and month as this locale writes them.
    private func dayAndMonth(_ year: Int, _ month: Int, _ day: Int, withYear: Bool) -> String {
        let dayMonth = style.day().month(.abbreviated)
        return date(year, month, day).formatted(withYear ? dayMonth.year() : dayMonth)
    }

    @Test func todayShowsTheTimeAlone() {
        #expect(format(2026, 9, 10, 14, 5) == clock(14, 5))
        #expect(clock(14, 5) == "14:05")
    }

    @Test func yesterdayIsNamedRatherThanDated() {
        #expect(format(2026, 9, 9, 23, 59) == "Yesterday \(clock(23, 59))")
    }

    @Test func earlierThisYearShowsDayAndMonthWithoutTheYear() {
        let result = format(2026, 9, 3, 9, 7)
        #expect(result == "\(dayAndMonth(2026, 9, 3, withYear: false)) \(clock(9, 7))")
        #expect(!result.contains("2026"))
    }

    @Test func aPreviousYearIncludesTheYear() {
        let result = format(2025, 12, 31, 18, 30)
        #expect(result == "\(dayAndMonth(2025, 12, 31, withYear: true)) \(clock(18, 30))")
        #expect(result.contains("2025"))
    }

    @Test func aMessageMinutesOldButPastMidnightIsNotCalledToday() {
        // Twenty minutes before the day rolled over, read just after: still not today, and saying otherwise would
        // misdate it by a day.
        let result = format(2026, 9, 9, 23, 40)

        #expect(result.hasPrefix("Yesterday"), "expected a dated label, got \(result)")
    }

    @Test func midnightItselfBelongsToItsOwnDay() {
        let result = format(2026, 9, 10, 0, 0)
        #expect(result == clock(0, 0))
        #expect(!result.hasPrefix("Yesterday"))
    }

    @Test func aTwelveHourLocaleGetsItsOwnClock() {
        let moment = date(2026, 9, 10, 21, 5)
        let result = MessageTimestamp.bubbleFormat(
            Int64(moment.timeIntervalSince1970 * 1000), calendar: calendar,
            locale: Locale(identifier: "en_US"))
        #expect(result.hasPrefix("9:05"))
        #expect(result.contains("PM"))
    }
}

/// Port of android/app/src/test/…/chat/TextHighlightTest.kt. Ranges are UTF-16 offsets, like Kotlin string indices.
@Suite("Search highlight")
struct TextHighlightTests {
    @Test func findsEveryOccurrenceNotJustTheFirst() {
        #expect(highlightRanges("camp at the camp", query: "camp") == [0..<4, 12..<16])
    }

    @Test func ignoresCaseBecauseNobodyTypesASearchTheWayItWasWritten() {
        #expect(highlightRanges("Camp", query: "cAmP") == [0..<4])
    }

    @Test func anEmptyOrBlankQueryHighlightsNothing() {
        #expect(highlightRanges("camp", query: "").isEmpty)
        #expect(highlightRanges("camp", query: "   ").isEmpty)
    }

    @Test func aQueryLongerThanTheTextFindsNothing() {
        #expect(highlightRanges("hi", query: "hello").isEmpty)
    }

    @Test func runsDoNotOverlap() {
        // "aa" in "aaa" matches once; the second would reuse a character.
        #expect(highlightRanges("aaa", query: "aa") == [0..<2])
    }

    @Test func arabicIsMatchedByCharacterNotByByte() {
        let text = "مرحبا بالعالم"
        let ranges = highlightRanges(text, query: "بالعالم")

        #expect(ranges.count == 1)
        let run = ranges[0]
        #expect((text as NSString).substring(with: NSRange(location: run.lowerBound, length: run.count)) == "بالعالم")
    }

    @Test func surroundingSpaceInTheQueryIsNotSearchedFor() {
        #expect(highlightRanges("camp", query: "  camp  ") == [0..<4])
    }
}
