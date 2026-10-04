package com.getfirepit.core.model

/**
 * The six fixed reactions (UX §5.4, decision U-1) and how they are counted.
 *
 * A reaction travels as a message of its own: one emoji, Meshtastic's `emoji`
 * flag, and the id of the message it reacts to. It is shown under that
 * message, never as a line of the conversation.
 */
object Reactions {

    /** In the order the picker shows them. */
    val CHOICES: List<String> = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    /** One emoji's count under a message, and whether one of them is yours. */
    data class Count(val emoji: String, val count: Int, val mine: Boolean)

    /** A message that reacts to another rather than saying something. */
    fun isReaction(message: ChatMessage): Boolean = (message.emoji ?: 0) != 0 && message.replyId != null

    /**
     * Each message's reactions, by the id of the message they react to.
     *
     * A mesh cannot take a reaction back, so a person's latest one stands for
     * theirs: choosing again replaces it rather than adding a second. The six
     * come first in the picker's order, then anything another app sent.
     */
    fun countsByTarget(messages: List<ChatMessage>, myNodeNum: Int?): Map<Int, List<Count>> =
        messages.filter(::isReaction)
            .groupBy { requireNotNull(it.replyId) }
            .mapValues { (_, reactions) ->
                reactions.groupBy { it.fromNodeNum }
                    .map { (_, theirs) -> theirs.maxBy { it.sentAt } }
                    .groupBy { it.text }
                    .map { (emoji, latest) ->
                        Count(emoji, latest.size, latest.any { it.isOutgoing || it.fromNodeNum == myNodeNum })
                    }
                    .sortedWith(compareBy<Count> { order(it.emoji) }.thenBy { it.emoji })
            }

    private fun order(emoji: String): Int = CHOICES.indexOf(emoji).let { if (it < 0) CHOICES.size else it }
}
