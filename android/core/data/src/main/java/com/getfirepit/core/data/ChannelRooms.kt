package com.getfirepit.core.data

import com.getfirepit.core.crypto.RoomCrypto

/**
 * What a channel slot means to Firepit, and the key its members share.
 *
 * Both the room and receipt repositories need these, and a mirrored copy in
 * each is one edit away from the two disagreeing about which key seals what.
 */

/** The room a slot carries, or null when the slot is not one of ours. */
internal fun MeshRepository.roomIdForChannel(channel: Int): Int? = channels.value
    .firstOrNull { it.index == channel && it.isRoom }
    ?.id
    ?.takeIf { it != 0 }

/**
 * A sealing key every member can derive from the room's own PSK.
 *
 * Weaker than a key the radio never sees, and meant to be replaced by one: it
 * keeps relays and non-members out, which is what it is for, but not anyone
 * holding the radio.
 */
internal fun MeshRepository.channelKeyFor(roomId: Int): ByteArray? {
    val index = channels.value.firstOrNull { it.id == roomId }?.index ?: return null
    val psk = snapshot.value?.channels?.get(index)?.settings?.psk?.toByteArray()
    if (psk == null || psk.size != RoomCrypto.PSK_SIZE) return null
    return RoomCrypto.channelKey(psk, roomId, generation = 1)
}
