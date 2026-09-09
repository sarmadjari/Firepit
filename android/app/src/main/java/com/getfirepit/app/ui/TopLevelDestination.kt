package com.getfirepit.app.ui

/**
 * The three tabs, in the order the design's bottom bar shows them.
 *
 * Glyphs rather than vector icons: the design uses custom glyphs throughout and
 * this avoids pulling the whole Material icon set in for three symbols.
 */
enum class TopLevelDestination(val label: String, val glyph: String) {
    CHATS("Chats", "\uD83D\uDCAC"),
    MAP("Map", "\uD83D\uDDFA"),
    SETTINGS("Settings", "\u2699"),
}
