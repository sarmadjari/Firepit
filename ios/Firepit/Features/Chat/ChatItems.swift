import FirepitModel
import Foundation

/// A calendar day, the Swift stand-in for `java.time.LocalDate` in the chat list.
nonisolated struct LocalDay: Hashable, Comparable, Sendable, CustomStringConvertible {
    let year: Int
    let month: Int
    let day: Int

    init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    /// The day `epochMillis` falls on in `calendar`'s time zone.
    init(epochMillis: Int64, calendar: Calendar) {
        let date = Date(timeIntervalSince1970: TimeInterval(epochMillis) / 1000)
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        self.init(year: parts.year ?? 1970, month: parts.month ?? 1, day: parts.day ?? 1)
    }

    static func today(calendar: Calendar = .current, now: Date = .now) -> LocalDay {
        LocalDay(epochMillis: Int64(now.timeIntervalSince1970 * 1000), calendar: calendar)
    }

    /// Noon on this day, a moment that exists in every time zone whatever its daylight-saving rules.
    func noon(in calendar: Calendar) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day, hour: 12)) ?? .distantPast
    }

    func minusDays(_ days: Int, calendar: Calendar) -> LocalDay {
        let shifted = calendar.date(byAdding: .day, value: -days, to: noon(in: calendar)) ?? noon(in: calendar)
        let parts = calendar.dateComponents([.year, .month, .day], from: shifted)
        return LocalDay(year: parts.year ?? year, month: parts.month ?? month, day: parts.day ?? day)
    }

    /// ISO-8601, as `LocalDate.toString()` prints it: `2026-09-09`.
    var description: String {
        String(format: "%04d-%02d-%02d", year, month, day)
    }

    static func < (lhs: LocalDay, rhs: LocalDay) -> Bool {
        (lhs.year, lhs.month, lhs.day) < (rhs.year, rhs.month, rhs.day)
    }
}

/// One row of the chat list: either a day marker or a message. Ported from android/app/…/chat/ChatItems.kt.
nonisolated enum ChatItem: Hashable, Sendable, Identifiable {
    case day(label: String, date: LocalDay)
    /// Something the room did, rather than something anyone said.
    case notice(id: Int32, text: String)
    case bubble(message: ChatMessage, isFirstInGroup: Bool, isLastInGroup: Bool)

    /// Stable identity for the list. Packet ids are unique, so they never collide with day keys.
    nonisolated enum Key: Hashable, Sendable {
        case day(String)
        case message(Int32)
    }

    var id: Key {
        switch self {
        case .day(_, let date): .day("day-\(date)")
        case .notice(let id, _): .message(id)
        case .bubble(let message, _, _): .message(message.id)
        }
    }
}

/// Long enough to keep a burst together, short enough that a later reply separates.
nonisolated let chatGroupWindowMillis: Int64 = 5 * 60 * 1000

/// How far a radio's clock may be from the phone's before it is not worth believing.
private nonisolated let clockToleranceMillis: Int64 = 5 * 60 * 1000

/// Turns a flat message list into rows with day markers and sender grouping.
///
/// A run of messages from the same sender, close together on the same day, is drawn as one block: the name appears
/// once and only the final bubble gets a tail. Kept pure so the rules are testable without a screen.
nonisolated func buildChatItems(
    _ messages: [ChatMessage],
    today: LocalDay = .today(),
    calendar: Calendar = .current,
    locale: Locale = .current,
    groupWindow: Int64 = chatGroupWindowMillis
) -> [ChatItem] {
    if messages.isEmpty { return [] }

    // The database orders by sentAt, but the day shown comes from the radio's clock where there is one. Grouping an
    // order built on a different clock puts one day on screen twice, which is a repeated key and a crash. The sort is
    // stable, keeping arrival order for equal times as Kotlin's sortedBy does.
    let messages = messages.enumerated()
        .sorted { ($0.element.shownAt(), $0.offset) < ($1.element.shownAt(), $1.offset) }
        .map(\.element)

    var items: [ChatItem] = []
    for (index, message) in messages.enumerated() {
        let date = message.displayDate(calendar)
        let previous = index > 0 ? messages[index - 1] : nil
        let next = index + 1 < messages.count ? messages[index + 1] : nil

        let newDay = previous.map { $0.displayDate(calendar) != date } ?? true
        if newDay {
            items.append(.day(label: dayLabel(date, today: today, calendar: calendar, locale: locale), date: date))
        }

        if message.isNotice {
            items.append(.notice(id: message.id, text: message.text))
            continue
        }

        // A quote needs its author named above it, so a reply opens a block.
        let isFirstInGroup =
            newDay
            || message.replyId != nil
            || !message.groupsWith(previous, calendar: calendar, window: groupWindow)
        // A reply carries a quote, so it always starts its own block.
        let isLastInGroup =
            next.map { next in
                next.displayDate(calendar) != date
                    || !next.groupsWith(message, calendar: calendar, window: groupWindow)
                    || next.replyId != nil
            } ?? true
        items.append(.bubble(message: message, isFirstInGroup: isFirstInGroup, isLastInGroup: isLastInGroup))
    }
    return items
}

extension ChatMessage {
    /// No real node has zero, so it marks a line the room itself wrote.
    nonisolated var isNotice: Bool { fromNodeNum == 0 }

    /// When the message existed, as far as anything here can tell.
    ///
    /// The radio hands a packet to the phone as soon as it has it, so the two clocks should agree. A radio that has
    /// never been told the time can be hours out or read as 1970, and believing it files today's conversation under
    /// yesterday, above the replies to it. Past the tolerance the phone's clock is the more honest of the two; the
    /// radio's claim is still shown in the info sheet.
    nonisolated func shownAt() -> Int64 {
        guard let claimed = rxTime else { return sentAt }
        return abs(claimed - sentAt) <= clockToleranceMillis ? claimed : sentAt
    }

    nonisolated fileprivate func displayDate(_ calendar: Calendar) -> LocalDay {
        LocalDay(epochMillis: shownAt(), calendar: calendar)
    }

    nonisolated fileprivate func groupsWith(_ other: ChatMessage?, calendar: Calendar, window: Int64) -> Bool {
        guard let other else { return false }
        if other.fromNodeNum != fromNodeNum || other.isOutgoing != isOutgoing { return false }
        if other.displayDate(calendar) != displayDate(calendar) { return false }
        if replyId != nil { return false }
        return abs(shownAt() - other.shownAt()) <= window
    }
}

/// "Today", "Yesterday", then the date — with the year only when it is not this one.
private nonisolated func dayLabel(_ date: LocalDay, today: LocalDay, calendar: Calendar, locale: Locale) -> String {
    if date == today { return String(localized: "Today", locale: locale) }
    if date == today.minusDays(1, calendar: calendar) { return String(localized: "Yesterday", locale: locale) }
    var style = Date.FormatStyle(
        date: .omitted, time: .omitted, locale: locale, calendar: calendar,
        timeZone: calendar.timeZone
    )
    .day()
    .month(.wide)
    if date.year != today.year {
        style = style.year()
    }
    return date.noon(in: calendar).formatted(style)
}
