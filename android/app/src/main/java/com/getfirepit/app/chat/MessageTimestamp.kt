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

    private val DAY_AND_MONTH = DateTimeFormatter.ofPattern("d MMM")
    private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy")
}
