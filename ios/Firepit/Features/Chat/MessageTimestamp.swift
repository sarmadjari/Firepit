import Foundation

/// Bubble timestamps, ported from android/app/…/chat/MessageTimestamp.kt.
///
/// Day separators already carry the date, but they scroll off the top, so a message read in isolation would give no
/// clue how old it is. Anything not from today therefore names its day as well as its time.
///
/// Android prints a fixed 24-hour `HH:mm` and English month names; iOS formats with the user's locale, so a phone set
/// to 12-hour time shows "9:05 PM". The rules — which parts appear when — are the same.
nonisolated enum MessageTimestamp {
    /// Bubble variant: the clock only. A day separator sits above every run of messages, so repeating the date in
    /// each bubble padded them out without telling the reader anything new.
    static func bubbleFormat(_ epochMillis: Int64, calendar: Calendar = .current, locale: Locale = .current) -> String {
        clock(date(epochMillis), calendar: calendar, locale: locale)
    }

    static func format(
        _ epochMillis: Int64,
        today: LocalDay = .today(),
        calendar: Calendar = .current,
        locale: Locale = .current
    ) -> String {
        let moment = date(epochMillis)
        let day = LocalDay(epochMillis: epochMillis, calendar: calendar)
        let time = clock(moment, calendar: calendar, locale: locale)

        if day == today { return time }
        if day == today.minusDays(1, calendar: calendar) {
            return String(localized: "Yesterday \(time)", locale: locale)
        }
        // Within the year the year itself is noise; beyond it, it is the point.
        return "\(dayAndMonth(moment, withYear: day.year != today.year, calendar: calendar, locale: locale)) \(time)"
    }

    /// Chat-list variant: one short column, so it names the day or the date but never both, and drops the clock once
    /// a conversation is older than today.
    static func listFormat(
        _ epochMillis: Int64,
        today: LocalDay = .today(),
        calendar: Calendar = .current,
        locale: Locale = .current
    ) -> String {
        let moment = date(epochMillis)
        let day = LocalDay(epochMillis: epochMillis, calendar: calendar)

        if day == today { return clock(moment, calendar: calendar, locale: locale) }
        if day == today.minusDays(1, calendar: calendar) { return String(localized: "Yesterday", locale: locale) }
        return dayAndMonth(moment, withYear: day.year != today.year, calendar: calendar, locale: locale)
    }

    private static func date(_ epochMillis: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(epochMillis) / 1000)
    }

    private static func clock(_ date: Date, calendar: Calendar, locale: Locale) -> String {
        date.formatted(
            Date.FormatStyle(
                date: .omitted, time: .omitted, locale: locale, calendar: calendar,
                timeZone: calendar.timeZone
            )
            .hour(.defaultDigits(amPM: .abbreviated))
            .minute(.twoDigits))
    }

    private static func dayAndMonth(_ date: Date, withYear: Bool, calendar: Calendar, locale: Locale) -> String {
        var style = Date.FormatStyle(
            date: .omitted, time: .omitted, locale: locale, calendar: calendar,
            timeZone: calendar.timeZone
        )
        .day()
        .month(.abbreviated)
        if withYear {
            style = style.year()
        }
        return date.formatted(style)
    }
}
