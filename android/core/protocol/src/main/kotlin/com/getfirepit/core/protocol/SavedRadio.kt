package com.getfirepit.core.protocol

/**
 * What a radio is for.
 *
 * The distinction is the user's, not the mesh's: Meshtastic has its own device
 * roles, but which radio is *yours* is a fact about the person carrying it.
 */
enum class NodeRole(val label: String) {
    /** The one in your pocket. Exactly one, and the app stays connected to it. */
    PERSONAL("Personal"),

    /** A radio left somewhere useful. Any number. */
    BASE("Base"),

    /** A radio placed to extend range. Any number. */
    ROUTER("Router"),
}

/**
 * How the phone reaches a device.
 *
 * Meshtastic radios also speak over USB and over the network, but Firepit only
 * carries a Bluetooth transport today; the others are named so a saved device
 * survives the day one arrives.
 */
enum class DeviceTransport(val label: String) {
    BLUETOOTH("Bluetooth"),
    USB("USB"),
    NETWORK("Wi-Fi"),
}

/** A radio this phone knows about. */
data class SavedRadio(
    val identifier: String,
    val name: String,
    val role: NodeRole,
    val transport: DeviceTransport = DeviceTransport.BLUETOOTH,
)

/**
 * The set of radios this phone administers.
 *
 * Only one can be Personal, because only one can be the radio the app keeps a
 * standing connection to; assigning it therefore has to take it away from
 * whoever held it.
 */
object SavedRadios {

    fun personal(radios: List<SavedRadio>): SavedRadio? =
        radios.firstOrNull { it.role == NodeRole.PERSONAL }

    /**
     * Adds or updates one radio, keeping Personal unique.
     *
     * A demoted Personal becomes a Base rather than disappearing: it is still a
     * radio you own, and silently forgetting it would be worse than guessing.
     */
    fun assign(radios: List<SavedRadio>, radio: SavedRadio): List<SavedRadio> {
        val others = radios.filterNot { it.identifier == radio.identifier }
        val adjusted = if (radio.role == NodeRole.PERSONAL) {
            others.map { if (it.role == NodeRole.PERSONAL) it.copy(role = NodeRole.BASE) else it }
        } else {
            others
        }
        return (adjusted + radio).sortedWith(
            // Yours first, then by name, so the list does not reshuffle as
            // radios come and go.
            compareBy({ it.role != NodeRole.PERSONAL }, { it.name.lowercase() }),
        )
    }

    fun forget(radios: List<SavedRadio>, identifier: String): List<SavedRadio> =
        radios.filterNot { it.identifier == identifier }
}
