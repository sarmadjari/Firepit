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
    fun `the radio clock decides the day when the two clocks agree`() {
        val message = message(1, from = 7, at = at(9)).copy(rxTime = at(9) - 60_000)

        val items = buildChatItems(listOf(message), today, zone)

        assertEquals("Today", (items.first() as ChatItem.Day).label)
    }

    /**
     * Observed on a radio whose clock was fifteen hours behind: a message that
     * had just arrived was filed under yesterday, above the replies to it.
     */
    @Test
    fun `a radio clock far from the phone's is not believed`() {
        val message = message(1, from = 7, at = at(9)).copy(rxTime = at(7))

        val items = buildChatItems(listOf(message), today, zone)

        assertEquals("Today", (items.first() as ChatItem.Day).label)
    }

    /**
     * A radio that has never been told the time once put 1970 between two of
     * today's messages, which repeated a day key and brought the screen down.
     */
    @Test
    fun `a wrong radio clock cannot repeat a day key`() {
        val messages = listOf(
            message(1, from = 7, at = at(9)),
            message(2, from = 7, at = at(9, 1)).copy(rxTime = 0),
            message(3, from = 7, at = at(9, 2)),
        )

        val keys = buildChatItems(messages, today, zone).map { it.key() }

        assertEquals(keys.size, keys.toSet().size)
        assertEquals(1, keys.count { it == "day-2026-09-09" })
    }

    @Test
    fun `a disbelieved radio clock leaves a message where it arrived`() {
        val messages = listOf(
            message(1, from = 7, at = at(9)),
            message(2, from = 7, at = at(9, 1)).copy(rxTime = at(7)),
            message(3, from = 7, at = at(9, 2)),
        )

        val items = buildChatItems(messages, today, zone)
        val keys = items.map { it.key() }

        assertEquals(keys.size, keys.toSet().size)
        assertEquals(listOf(1, 2, 3), items.filterIsInstance<ChatItem.Bubble>().map { it.message.id })
    }
}
