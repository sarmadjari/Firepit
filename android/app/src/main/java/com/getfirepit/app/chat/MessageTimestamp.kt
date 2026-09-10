package com.getfirepit.app.chat

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Bubble timestamps.
 *
 * Day separators already carry the date, but they scroll off the top, so a
 * message read in isolation would give no clue how old it is. Anything not from
 * today therefore names its day as well as its time.
 */
object MessageTimestamp {

    /**
     * Bubble variant: the clock only.
     *
     * A day separator sits above every run of messages, so repeating the date
     * in each bubble padded them out without telling the reader anything new.
     */
    fun bubbleFormat(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return "%02d:%02d".format(moment.hour, moment.minute)
    }

    fun format(
        epochMillis: Long,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val date = moment.toLocalDate()
        val time = "%02d:%02d".format(moment.hour, moment.minute)

        return when {
            date == today -> time
            date == today.minusDays(1) -> "Yesterday $time"
            // Within the year the year itself is noise; beyond it, it is the point.
            date.year == today.year -> "${date.format(DAY_AND_MONTH)} $time"
            else -> "${date.format(DAY_MONTH_YEAR)} $time"
        }
    }

    /**
     * Chat-list variant: one short column, so it names the day or the date but
     * never both, and drops the clock once a conversation is older than today.
     */
    fun listFormat(
        epochMillis: Long,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val date = moment.toLocalDate()

        return when {
            date == today -> "%02d:%02d".format(moment.hour, moment.minute)
            date == today.minusDays(1) -> "Yesterday"
            date.year == today.year -> date.format(DAY_AND_MONTH)
            else -> date.format(DAY_MONTH_YEAR)
        }
    }

    private val DAY_AND_MONTH = DateTimeFormatter.ofPattern("d MMM")
    private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy")
}
