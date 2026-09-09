package com.getfirepit.app.chat

import com.getfirepit.core.model.ChatMessage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** One row of the chat list: either a day marker or a message. */
sealed interface ChatItem {
    data class Day(val label: String, val date: LocalDate) : ChatItem

    data class Bubble(
        val message: ChatMessage,
        val isFirstInGroup: Boolean,
        val isLastInGroup: Boolean,
    ) : ChatItem
}

/**
 * Turns a flat message list into rows with day markers and sender grouping.
 *
 * A run of messages from the same sender, close together on the same day, is
 * drawn as one block: the name appears once and only the final bubble gets a
 * tail. Kept pure so the rules are testable without a screen.
 */
fun buildChatItems(
    messages: List<ChatMessage>,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    groupWindow: Duration = GROUP_WINDOW,
): List<ChatItem> {
    if (messages.isEmpty()) return emptyList()

    val items = mutableListOf<ChatItem>()
    messages.forEachIndexed { index, message ->
        val date = message.displayDate(zone)
        val previous = messages.getOrNull(index - 1)
        val next = messages.getOrNull(index + 1)

        val newDay = previous == null || previous.displayDate(zone) != date
        if (newDay) items += ChatItem.Day(dayLabel(date, today), date)

        items += ChatItem.Bubble(
            message = message,
            // A quote needs its author named above it, so a reply opens a block.
            isFirstInGroup = newDay ||
                message.replyId != null ||
                !message.groupsWith(previous, zone, groupWindow),
            // A reply carries a quote, so it always starts its own block.
            isLastInGroup = next == null ||
                next.displayDate(zone) != date ||
                !next.groupsWith(message, zone, groupWindow) ||
                next.replyId != null,
        )
    }
    return items
}

private fun ChatMessage.groupsWith(other: ChatMessage?, zone: ZoneId, window: Duration): Boolean {
    if (other == null) return false
    if (other.fromNodeNum != fromNodeNum || other.isOutgoing != isOutgoing) return false
    if (other.displayDate(zone) != displayDate(zone)) return false
    if (replyId != null) return false
    return kotlin.math.abs(displayTime() - other.displayTime()) <= window.inWholeMilliseconds
}

/** The radio's clock when it gave us one, since that is when the message existed. */
private fun ChatMessage.displayTime(): Long = rxTime ?: sentAt

private fun ChatMessage.displayDate(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(displayTime()).atZone(zone).toLocalDate()

private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> {
        val pattern = if (date.year == today.year) "d MMMM" else "d MMMM yyyy"
        date.format(DateTimeFormatter.ofPattern(pattern))
    }
}

/** Stable identity for the lazy list. Packet ids are unique, so they never collide with day keys. */
fun ChatItem.key(): Any = when (this) {
    is ChatItem.Day -> "day-$date"
    is ChatItem.Bubble -> message.id
}

/** Long enough to keep a burst together, short enough that a later reply separates. */
private val GROUP_WINDOW = 5.minutes
