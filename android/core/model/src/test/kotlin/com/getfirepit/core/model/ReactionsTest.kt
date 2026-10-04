package com.getfirepit.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionsTest {

    private val me = 1
    private val maya = 2
    private val ryan = 3

    private fun message(id: Int, from: Int, text: String = "Hi", replyId: Int? = null, emoji: Int? = null, at: Long = id.toLong()) =
        ChatMessage(
            id = id,
            channel = 1,
            fromNodeNum = from,
            toNodeNum = BROADCAST_NODE_NUM,
            text = text,
            sentAt = at,
            isOutgoing = from == me,
            replyId = replyId,
            emoji = emoji,
        )

    @Test
    fun `there are six choices, in the picker's order`() {
        assertEquals(listOf("👍", "❤️", "😂", "😮", "😢", "🙏"), Reactions.CHOICES)
    }

    @Test
    fun `only an emoji aimed at a message is a reaction`() {
        assertTrue(Reactions.isReaction(message(2, maya, "👍", replyId = 1, emoji = 1)))
        assertFalse(Reactions.isReaction(message(2, maya, "👍", replyId = 1)))
        assertFalse(Reactions.isReaction(message(2, maya, "👍", emoji = 1)))
    }

    @Test
    fun `reactions are counted under their message, the six in order and yours marked`() {
        val counts = Reactions.countsByTarget(
            listOf(
                message(1, maya, "Coffee?"),
                message(2, ryan, "❤️", replyId = 1, emoji = 1),
                message(3, me, "👍", replyId = 1, emoji = 1),
                message(4, maya, "👍", replyId = 1, emoji = 1),
            ),
            myNodeNum = me,
        )

        assertEquals(
            listOf(Reactions.Count("👍", 2, mine = true), Reactions.Count("❤️", 1, mine = false)),
            counts[1],
        )
    }

    @Test
    fun `a person's latest reaction replaces their earlier one`() {
        val counts = Reactions.countsByTarget(
            listOf(
                message(2, maya, "👍", replyId = 1, emoji = 1, at = 10),
                message(3, maya, "😂", replyId = 1, emoji = 1, at = 20),
            ),
            myNodeNum = me,
        )

        assertEquals(listOf(Reactions.Count("😂", 1, mine = false)), counts[1])
    }

    @Test
    fun `an emoji another app sent follows the six`() {
        val counts = Reactions.countsByTarget(
            listOf(
                message(2, maya, "🔥", replyId = 1, emoji = 1),
                message(3, ryan, "🙏", replyId = 1, emoji = 1),
            ),
            myNodeNum = me,
        )

        assertEquals(listOf("🙏", "🔥"), counts.getValue(1).map { it.emoji })
    }
}
