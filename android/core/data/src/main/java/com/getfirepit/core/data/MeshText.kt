package com.getfirepit.core.data

/**
 * Text arriving from the mesh is attacker-controlled. Strip anything that could
 * corrupt the UI and cap the length, but keep the result recognisable rather
 * than silently dropping the message.
 */
internal fun sanitizeMeshText(raw: String, maxChars: Int = 500): String =
    raw.asSequence()
        .filter { char -> char == '\n' || !char.isISOControl() }
        .take(maxChars)
        .joinToString("")
        .trim()
