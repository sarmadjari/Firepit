import Foundation

/// Where a search term appears in a message, as UTF-16 offsets (Kotlin string indices) for `MessageBubble`'s
/// highlight. Ported from android/app/…/chat/TextHighlight.kt.
///
/// Case-insensitive, because nobody types a search the way it was written, and every occurrence rather than the
/// first: a word repeated in one message is usually why that message matched.
nonisolated func highlightRanges(_ text: String, query: String) -> [Range<Int>] {
    let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
    if needle.isEmpty || text.isEmpty { return [] }

    let haystack = text as NSString
    let needleLength = (needle as NSString).length
    var found: [Range<Int>] = []
    var from = 0
    while from <= haystack.length - needleLength {
        let match = haystack.range(
            of: needle, options: .caseInsensitive,
            range: NSRange(location: from, length: haystack.length - from))
        if match.location == NSNotFound { break }
        found.append(match.location..<(match.location + match.length))
        // Overlapping matches would paint the same run twice.
        from = match.location + max(match.length, 1)
    }
    return found
}
