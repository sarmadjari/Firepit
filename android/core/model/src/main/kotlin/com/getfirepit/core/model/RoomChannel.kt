package com.getfirepit.core.model

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
) {
    val isRoom: Boolean get() = index > 0 && role == ChannelRole.SECONDARY

    /** An empty name means the firmware shows the modem preset name instead. */
    val displayName: String get() = name.ifBlank { "Primary" }
}

enum class ChannelRole { DISABLED, PRIMARY, SECONDARY }
