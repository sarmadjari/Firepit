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
) {
    /** Display form used by every Meshtastic client. */
    val displayId: String get() = userId ?: "!%08x".format(nodeNum)

    val displayName: String get() = longName?.takeIf { it.isNotBlank() } ?: displayId
}
