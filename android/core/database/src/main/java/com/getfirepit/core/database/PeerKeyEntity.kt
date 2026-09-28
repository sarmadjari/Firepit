package com.getfirepit.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Another person's phone key, which is where a new room key is sealed to.
 *
 * Kept by node rather than by room, because a phone is the same phone in every
 * room you share. Learned on first sight and then kept: see
 * `TrustRules.shouldStorePhoneKey` for when one may be replaced.
 */
@Entity(tableName = "peer_keys")
data class PeerKeyEntity(
    @PrimaryKey val nodeNum: Int,
    /** Base64 of the 33-byte compressed point. */
    val phoneKey: String,
    val learnedAt: Long,
)
