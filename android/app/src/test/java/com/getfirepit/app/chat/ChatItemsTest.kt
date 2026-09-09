package com.getfirepit.app.chat

import com.getfirepit.core.model.ChatMessage
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatItemsTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 9, 9)

    /** Midday on [dayOfMonth], plus [minutes]. */
    private fun at(dayOfMonth: Int, minutes: Long = 0): Long =
        LocalDate.of(2026, 9, dayOfMonth)
            .atStartOfDay(zone)
            .plusHours(12)
            .plusMinutes(minutes)
            .toInstant()
            .toEpochMilli()

    private fun message(
        id: Int,
        from: Int,
        at: Long,
        outgoing: Boolean = false,
        replyId: Int? = null,
    ) = ChatMessage(
        id = id,
        channel = 1,
        fromNodeNum = from,
        toNodeNum = -1,
        text = "m$id",
        sentAt = at,
        isOutgoing = outgoing,
        replyId = replyId,
    )

    private fun bubbles(items: List<ChatItem>) = items.filterIsInstance<ChatItem.Bubble>()

    @Test
    fun `an empty channel produces no rows`() {
        assertEquals(emptyList<ChatItem>(), buildChatItems(emptyList(), today, zone))
    }

    @Test
    fun `each day gets one marker`() {
        val items = buildChatItems(
            listOf(
                message(1, from = 7, at = at(7)),
                message(2, from = 7, at = at(8)),
                message(3, from = 7, at = at(9)),
            ),
            today,
            zone,
        )

        val days = items.filterIsInstance<ChatItem.Day>()
        assertEquals(3, days.size)
        assertEquals(listOf("7 September", "Yesterday", "Today"), days.map { it.label })
    }

    @Test
    fun `a burst from one sender becomes a single block`() {
        val items = buildChatItems(
            listOf(
                message(1, from = 7, at = at(9, 0)),
                message(2, from = 7, at = at(9, 1)),
                message(3, from = 7, at = at(9, 2)),
            ),
            today,
            zone,
        )

        val grouped = bubbles(items)
        assertEquals(listOf(true, false, false), grouped.map { it.isFirstInGroup })
        assertEquals(listOf(false, false, true), grouped.map { it.isLastInGroup })
    }

    @Test
    fun `a gap longer than the window starts a new block`() {
        val items = buildChatItems(
            listOf(
                message(1, from = 7, at = at(9, 0)),
                message(2, from = 7, at = at(9, 30)),
            ),
            today,
            zone,
        )

        assertTrue(bubbles(items).all { it.isFirstInGroup })
        assertTrue(bubbles(items).all { it.isLastInGroup })
    }

    @Test
    fun `a different sender breaks the block`() {
        val items = buildChatItems(
            listOf(
                message(1, from = 7, at = at(9, 0)),
                message(2, from = 8, at = at(9, 1)),
            ),
            today,
            zone,
        )

        assertTrue(bubbles(items).all { it.isFirstInGroup })
    }

    @Test
    fun `my own message never groups with someone else's at the same moment`() {
        val items = buildChatItems(
            listOf(
                message(1, from = 7, at = at(9, 0), outgoing = false),
                message(2, from = 7, at = at(9, 0), outgoing = true),
            ),
            today,
            zone,
        )

        assertTrue(bubbles(items)[1].isFirstInGroup)
    }

    @Test
    fun `a reply stands alone so its quote is not buried in a block`() {
        val items = buildChatItems(
            listOf(
                message(1, from = 7, at = at(9, 0)),
                message(2, from = 7, at = at(9, 1), replyId = 1),
                message(3, from = 7, at = at(9, 2)),
            ),
            today,
            zone,
        )

        val grouped = bubbles(items)
        assertTrue("the message before a reply must close its block", grouped[0].isLastInGroup)
        assertTrue("a reply starts its own block so the quote reads clearly", grouped[1].isFirstInGroup)
        assertFalse("a plain follow-up continues the reply's block", grouped[2].isFirstInGroup)
        assertTrue(grouped[2].isLastInGroup)
    }

    @Test
    fun `the radio clock wins over local time when deciding the day`() {
        val message = message(1, from = 7, at = at(9)).copy(rxTime = at(7))

        val items = buildChatItems(listOf(message), today, zone)

        assertEquals("7 September", (items.first() as ChatItem.Day).label)
    }
}
