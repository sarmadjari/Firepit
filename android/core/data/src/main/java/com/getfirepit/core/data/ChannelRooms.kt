package com.getfirepit.core.data

import com.getfirepit.core.protocol.ChannelKey

/**
 * What a channel slot means to Firepit.
 *
 * Several repositories need these, and a mirrored copy in each is one edit away
 * from the two disagreeing about what a slot is.
 */

/** The room a slot carries, or null when the slot is not a Firepit room. */
internal fun MeshRepository.roomIdForChannel(channel: Int): Int? = channels.value
    .firstOrNull { it.index == channel && it.isRoom }
    ?.id
    ?.takeIf { it != 0 }

/**
 * True when this slot holds a conversation at all.
 *
 * Judged by the slot and its role, never by the room id: a channel somebody
 * made in the official Meshtastic app is a perfectly good conversation and
 * often carries no id of its own.
 */
internal fun MeshRepository.isRoomSlot(channel: Int): Boolean =
    channels.value.any { it.index == channel && it.isRoom }

/**
 * How private the radio's own encryption on this slot is.
 *
 * This is all an interoperable channel has — the key lives on the radio, so it
 * protects against the mesh at large and against nobody holding the hardware.
 */
internal fun MeshRepository.channelKeyOf(channel: Int): ChannelKey =
    ChannelKey.of(snapshot.value?.channels?.get(channel)?.settings?.psk?.toByteArray())
