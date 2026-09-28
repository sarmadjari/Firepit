package com.getfirepit.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.getfirepit.core.model.PersonCard

/**
 * How a room member described themselves, last time they said.
 *
 * Keyed by node rather than by room: a person is the same person in every room
 * you share, and the card arrives sealed under whichever room they happened to
 * send it in.
 */
@Entity(tableName = "person_cards")
data class PersonCardEntity(
    @PrimaryKey val nodeNum: Int,
    val name: String,
    val tag: String,
    /** Null means no choice, so the colour stays derived from the node number. */
    val colourSlot: Int?,
    val updatedAt: Long,
)

internal fun PersonCardEntity.toDomain() = PersonCard(
    nodeNum = nodeNum,
    name = name,
    tag = tag,
    colourSlot = colourSlot,
    updatedAt = updatedAt,
)
