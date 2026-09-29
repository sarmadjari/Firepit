package com.getfirepit.core.model

/**
 * A pin dropped on the map.
 *
 * Travels sealed under the room's key, so only members see it and only a
 * member can change it; [lockedTo] then narrows editing to one of them. A
 * member can still write anything, so a pin is a claim, not a fact.
 */
data class MapPin(
    val id: Int,
    val channel: Int,
    val latitudeI: Int,
    val longitudeI: Int,
    val name: String,
    val description: String = "",
    /** Epoch seconds. Zero means it never expires. */
    val expire: Long = 0,
    /** Node number allowed to edit, or 0 when anyone on the channel may. */
    val lockedTo: Int = 0,
    val icon: String? = null,
    val createdBy: Int,
    val receivedAt: Long,
    /** The room it belongs to, which outlives the slot the room sits in. */
    val roomId: Int = 0,
) {
    val latitude: Double get() = latitudeI * 1e-7

    val longitude: Double get() = longitudeI * 1e-7

    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        expire in 1 until nowMillis / 1000L

    fun canEdit(myNodeNum: Int?): Boolean = lockedTo == 0 || lockedTo == myNodeNum
}
