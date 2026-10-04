package com.getfirepit.core.database

import androidx.room.Entity
import com.getfirepit.core.model.RoomMember

/** Keyed by room rather than slot index, so a room keeps its roster when it moves slot. */
@Entity(tableName = "room_members", primaryKeys = ["roomId", "nodeNum"])
data class RoomMemberEntity(
    val roomId: Int,
    val nodeNum: Int,
    val invitedBy: Int?,
    val firstSeen: Long,
    val lastHeard: Long?,
    val lastOpenedGeneration: Int?,
)

internal fun RoomMemberEntity.toDomain() = RoomMember(
    roomId = roomId,
    nodeNum = nodeNum,
    invitedBy = invitedBy,
    firstSeen = firstSeen,
    lastHeard = lastHeard,
)
