package com.getfirepit.core.model

/**
 * What kind of conversation a channel slot holds, and therefore who can read
 * it. Shown on the room itself, because the difference is the whole point.
 *
 * The labels name the protocol rather than describing the privacy. A word like
 * "shared" reads as reassuring when it should read as a warning, whereas
 * "Meshtastic" states the one fact that predicts everything else: this is not a
 * Firepit room, so other apps can read it and Firepit's own features are off.
 */
enum class RoomKind(
    /** The badge, shown wherever the room is named. */
    val label: String,
    /** Who can read it, in one line, for sitting under the room name. */
    val readableBy: String,
    /** The whole story, for when there is room to tell it. */
    val summary: String,
) {
    /**
     * Sealed under a key that only ever sits on members' phones. Other
     * Meshtastic clients see an unreadable blob and cannot join in.
     */
    FIREPIT(
        label = "Firepit",
        readableBy = "Only the people you invited",
        summary = "Firepit's own kind of room. Messages are sealed on your phone before the " +
            "radio ever sees them, so nobody on the Meshtastic network can read this — not " +
            "even someone holding one of the radios. Names, colours, and delivery and read " +
            "status all work here. Invite people with a Firepit QR code.",
    ),

    /**
     * An ordinary Meshtastic channel with a real key. Anyone running any
     * Meshtastic client can take part if they have the key — and so can anyone
     * who picks up one of their radios.
     */
    MESHTASTIC_PRIVATE(
        label = "Meshtastic",
        readableBy = "Anyone who has this channel's key",
        summary = "A standard Meshtastic channel, so people using the ordinary Meshtastic app " +
            "can take part. Its key is kept on the radios, which means anyone holding one can " +
            "read everything sent here. Firepit's own features — read status, profile names " +
            "and colours — are switched off, because other apps would not understand them.",
    ),

    /**
     * The open mesh, on the key published in the Meshtastic source. Useful, and
     * not private in any sense.
     */
    MESHTASTIC_PUBLIC(
        label = "Meshtastic · public",
        readableBy = "Every Meshtastic radio in range",
        summary = "The open Meshtastic network, on the key published in Meshtastic's own source " +
            "code. Every radio in range can read this. Useful for reaching strangers, never " +
            "for anything you would not say out loud.",
    ),

    /** No encryption at all. Firepit will not send here. */
    UNENCRYPTED(
        label = "No key",
        readableBy = "Everyone, in the clear",
        summary = "This channel has no encryption key at all, so anything sent on it travels in " +
            "the clear. Firepit will not send messages here.",
    ),
    ;

    /** True when this room is unreadable to everyone outside it. */
    val isPrivate: Boolean get() = this == FIREPIT

    /** True when other Meshtastic clients can take part. */
    val isInteroperable: Boolean get() = this == MESHTASTIC_PRIVATE || this == MESHTASTIC_PUBLIC
}

/**
 * A channel slot on the radio. Slot 0 is the primary; 1-7 are rooms.
 *
 * [id] is Firepit's stable room identifier and survives renames and
 * re-indexing, so it is what the app keys rooms by — never the slot index.
 */
data class RoomChannel(
    val index: Int,
    val name: String,
    val role: ChannelRole,
    val id: Int,
    /** 0 means positions are never sent on this channel. */
    val positionPrecision: Int,
    /** Least private by default: a slot is only a sealed room once a key proves it. */
    val kind: RoomKind = RoomKind.MESHTASTIC_PUBLIC,
) {
    val isRoom: Boolean get() = index > 0 && role == ChannelRole.SECONDARY

    /** An empty name means the firmware shows the modem preset name instead. */
    val displayName: String get() = name.ifBlank { "Primary" }

    /**
     * What picks the room's icon. Every member has to land on the same one, and
     * the two kinds of room agree on different things.
     *
     * A Firepit room travels with its id inside the invite, so that is shared
     * by construction. A Meshtastic channel's id is whatever the app that made
     * it happened to choose — often nothing at all — and a member who typed the
     * channel in by hand would have a different one. Its name, though, must
     * match on every radio or the channel does not work, so that is the only
     * thing safe to draw from.
     */
    val iconSeed: Int
        get() = if (kind == RoomKind.FIREPIT && id != 0) id else seedOf(name)

    private companion object {
        /**
         * FNV-1a, written out rather than using [String.hashCode] so iOS can
         * reproduce it exactly. Swift's own hashing is salted per process and
         * would put the same room under a different icon on each platform.
         */
        fun seedOf(name: String): Int {
            var hash = -0x7ee3623b // 2166136261 as a signed Int
            name.toByteArray(Charsets.UTF_8).forEach { byte ->
                hash = (hash xor (byte.toInt() and 0xff)) * 16777619
            }
            return hash
        }
    }
}

enum class ChannelRole { DISABLED, PRIMARY, SECONDARY }
