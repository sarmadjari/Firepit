package com.getfirepit.core.model

/**
 * Somebody we believe is in a room.
 *
 * Meshtastic channels have no membership: anyone holding the key can talk, and
 * nobody announces leaving. So this is what we have *observed*, not a
 * authoritative list. A member who never speaks is invisible, and one who has
 * deleted the room still appears until they go stale.
 */
data class RoomMember(
    val roomId: Int,
    val nodeNum: Int,
    /**
     * Who vouched for them. Set only when their join was proved against an
     * invite we issued, or relayed to us by the inviter. Null means we simply
     * heard them on the channel.
     */
    val invitedBy: Int? = null,
    val firstSeen: Long,
    /** Null when another member told us about them but we have never heard them. */
    val lastHeard: Long? = null,
) {
    /** True when the invite chain proved how they got in, not just that they talk. */
    val isVouched: Boolean get() = invitedBy != null

    /** False when the entry is somebody else's word rather than our own observation. */
    val isFirstHand: Boolean get() = lastHeard != null
}
