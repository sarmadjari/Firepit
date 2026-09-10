package com.getfirepit.core.protocol

/**
 * Who you are, as distinct from what you are carrying.
 *
 * The mesh addresses radios, not people: every node number belongs to a device,
 * and a person who moves from their pocket radio to a base station changes node
 * number without becoming someone else. This is the phone's own answer to "who
 * is this", held apart from the node name so renaming yourself never
 * reconfigures a radio.
 *
 * [id] is generated once on this phone and never derived from a node number,
 * which is what lets your colour stay yours across every radio you pick up.
 */
data class Person(
    val id: Int,
    val name: String,
    val tag: String,
    val colourSlot: Int? = null,
) {
    companion object {
        /**
         * Applies the same limits as a node name.
         *
         * Nothing forces that today — this never reaches the radio — but a
         * person only becomes visible to anyone else by being sent, and a
         * control packet has no more room than NodeInfo does.
         */
        fun of(id: Int, name: String, tag: String, colourSlot: Int? = null): Person {
            val trimmedName = OwnerName.longName(name)
            return Person(
                id = id,
                name = trimmedName,
                tag = OwnerName.shortName(tag.ifBlank { initialsFor(trimmedName) }),
                colourSlot = colourSlot,
            )
        }

        /**
         * Initials as a person would write them: first name, then surname.
         *
         * Falls back to the opening two letters while only one name has been
         * typed, so the dot is never blank and never a single lonely letter.
         */
        fun initialsFor(name: String): String {
            val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val first = words.firstOrNull()?.firstOrNull() ?: return ""
            val second = if (words.size >= 2) {
                words.last().firstOrNull()
            } else {
                words.first().getOrNull(1)
            }
            return "$first${second ?: ""}".uppercase()
        }
    }
}
