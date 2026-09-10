package com.getfirepit.core.protocol

/**
 * The name a node broadcasts about itself.
 *
 * Both halves are capped in bytes, not characters, because the firmware stores
 * them in fixed buffers: an Arabic or emoji name that looks short can still
 * overflow, and would be cut mid-character by the radio rather than by us.
 */
object OwnerName {

    /** Firmware allows 40 bytes including its terminator. */
    const val MAX_LONG_BYTES: Int = 39

    /** Firmware allows 5 bytes including its terminator. */
    const val MAX_SHORT_BYTES: Int = 4

    fun longName(text: String): String =
        MeshConstants.truncateToBytes(text.trim(), MAX_LONG_BYTES)

    fun shortName(text: String): String =
        MeshConstants.truncateToBytes(text.trim(), MAX_SHORT_BYTES)

    /**
     * A tag for someone who has not chosen one.
     *
     * Initials when the name has several words, otherwise its opening letters,
     * so "Sam Jones" reads as SJ and "Sarmad" as SARM.
     */
    fun suggestShort(longName: String): String {
        val words = longName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val initials = words.mapNotNull { word -> word.firstOrNull()?.uppercaseChar() }
        val candidate = if (initials.size >= 2) initials.joinToString("") else longName.trim().uppercase()
        return shortName(candidate)
    }

    /** True when the radio can carry both halves as given. */
    fun fits(longName: String, shortName: String): Boolean =
        longName.toByteArray(Charsets.UTF_8).size <= MAX_LONG_BYTES &&
            shortName.toByteArray(Charsets.UTF_8).size <= MAX_SHORT_BYTES
}
