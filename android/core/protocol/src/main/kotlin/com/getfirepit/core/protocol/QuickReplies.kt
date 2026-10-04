package com.getfirepit.core.protocol

/**
 * Quick replies: short, ready-made messages that one tap sends (UX §5.4).
 *
 * Each is a whole LoRa message, so they are kept short: at [MAX_BYTES] a quick
 * reply never comes near a room's limit, sealed or not, and goes out through
 * the same paced queue as anything typed.
 */
object QuickReplies {

    const val MAX_BYTES = 40

    /** Enough to choose from in one glance over the composer. */
    const val MAX_COUNT = 10

    /** What a phone starts with; editable in Settings › Quick replies. */
    val DEFAULTS: List<String> = listOf("On my way", "Where are you?", "Wait for me", "I'm here", "OK")

    /**
     * One line, trimmed, and within [MAX_BYTES] without splitting a character.
     * Null when nothing is left to send.
     */
    fun clean(text: String): String? {
        val oneLine = text.replace(WHITESPACE, " ").trim()
        return MeshConstants.truncateToBytes(oneLine, MAX_BYTES).trim().ifEmpty { null }
    }

    /** The list as it is kept: each one cleaned, empty ones and repeats dropped, at most [MAX_COUNT]. */
    fun normalise(replies: List<String>): List<String> = replies.mapNotNull(::clean).distinct().take(MAX_COUNT)

    private val WHITESPACE = Regex("\\s+")
}
