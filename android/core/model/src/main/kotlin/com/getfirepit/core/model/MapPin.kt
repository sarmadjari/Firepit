package com.getfirepit.core.model

/**
 * A pin dropped on the map.
 *
 * Mesh waypoints are public to everyone on the channel and editable by anyone
 * unless [lockedTo] names an owner, so nothing here should be treated as
 * private or authoritative.
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
) {
    val latitude: Double get() = latitudeI * 1e-7

    val longitude: Double get() = longitudeI * 1e-7

    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        expire in 1 until nowMillis / 1000L

    fun canEdit(myNodeNum: Int?): Boolean = lockedTo == 0 || lockedTo == myNodeNum
}
