package com.getfirepit.core.model

/**
 * A node the radio has heard of.
 *
 * Everything here except [nodeNum] arrives from the mesh and is therefore
 * attacker-controlled: names are untrusted display strings, not identifiers.
 */
data class MeshNode(
    val nodeNum: Int,
    val userId: String? = null,
    val longName: String? = null,
    /** The 2-character tag shown in avatars and map markers. */
    val shortName: String? = null,
    val hwModel: String? = null,
    val role: String? = null,
    /** Base64. Required before a direct message can be encrypted to this node. */
    val publicKey: String? = null,
    /** Infrastructure nodes set this so they are not offered as chat targets. */
    val isUnmessagable: Boolean = false,
    val lastHeard: Long? = null,
    val snr: Float? = null,
    val rssi: Int? = null,
    val hopsAway: Int? = null,
    /** 101 means USB powered, per the firmware's magic value. */
    val batteryLevel: Int? = null,
    val voltage: Float? = null,
    val channelUtilization: Float? = null,
    val airUtilTx: Float? = null,
    val isFavorite: Boolean = false,
    /** 1e-7 degrees, as the mesh carries it. Null until the node reports a fix. */
    val latitudeI: Int? = null,
    val longitudeI: Int? = null,
    val altitude: Int? = null,
    /** When the position was measured, which is not when we heard about it. */
    val positionTime: Long? = null,
    /** Bits the sender truncated to; below 32 the point is an area, not a place. */
    val positionPrecision: Int? = null,
    /** km/h, absent when the radio did not report movement. */
    val groundSpeed: Int? = null,
    /** True North course in hundredths of a degree. */
    val groundTrack: Int? = null,
    /** True when the newest stored position came from the radio safety net. */
    val positionFromRadio: Boolean = false,
) {
    /** Display form used by every Meshtastic client. */
    val displayId: String get() = userId ?: "!%08x".format(nodeNum)

    val displayName: String get() = longName?.takeIf { it.isNotBlank() } ?: displayId

    val latitude: Double? get() = latitudeI?.let { it * 1e-7 }

    val longitude: Double? get() = longitudeI?.let { it * 1e-7 }

    val hasPosition: Boolean get() = latitudeI != null && longitudeI != null
}
