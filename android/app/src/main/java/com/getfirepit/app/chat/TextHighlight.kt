package com.getfirepit.app.chat

/**
 * Where a search term appears in a message.
 *
 * Case-insensitive, because nobody types a search the way it was written, and
 * every occurrence rather than the first: a word repeated in one message is
 * usually why that message matched.
 */
fun highlightRanges(text: String, query: String): List<IntRange> {
    val needle = query.trim()
    if (needle.isEmpty() || text.isEmpty()) return emptyList()

    val found = mutableListOf<IntRange>()
    var from = 0
    while (from <= text.length - needle.length) {
        val at = text.indexOf(needle, startIndex = from, ignoreCase = true)
        if (at < 0) break
        found += at until (at + needle.length)
        // Overlapping matches would paint the same run twice.
        from = at + needle.length
    }
    return found
}
