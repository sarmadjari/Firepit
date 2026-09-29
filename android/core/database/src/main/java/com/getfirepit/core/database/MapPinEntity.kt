package com.getfirepit.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.getfirepit.core.model.MapPin

@Entity(tableName = "map_pins")
data class MapPinEntity(
    @PrimaryKey val id: Int,
    /** Where its room sits now; see [MessageEntity.roomId] for why that is not its identity. */
    val channel: Int,
    val latitudeI: Int,
    val longitudeI: Int,
    val name: String,
    val description: String,
    val expire: Long,
    val lockedTo: Int,
    val icon: String?,
    val createdBy: Int,
    val receivedAt: Long,
    @ColumnInfo(defaultValue = "0") val roomId: Int = 0,
)

/**
 * A pin we have deleted.
 *
 * The mesh has no delete, only expiry, and other nodes keep rebroadcasting what
 * they hold. Without a record of what we removed, somebody else's copy would
 * put the pin straight back on our map.
 */
@Entity(tableName = "deleted_pins")
data class DeletedPinEntity(
    @PrimaryKey val id: Int,
    val channel: Int,
    val deletedAt: Long,
)

internal fun MapPinEntity.toDomain() = MapPin(
    id = id,
    channel = channel,
    latitudeI = latitudeI,
    longitudeI = longitudeI,
    name = name,
    description = description,
    expire = expire,
    lockedTo = lockedTo,
    icon = icon,
    createdBy = createdBy,
    receivedAt = receivedAt,
    roomId = roomId,
)

internal fun MapPin.toEntity() = MapPinEntity(
    id = id,
    channel = channel,
    latitudeI = latitudeI,
    longitudeI = longitudeI,
    name = name,
    description = description,
    expire = expire,
    lockedTo = lockedTo,
    icon = icon,
    createdBy = createdBy,
    receivedAt = receivedAt,
    roomId = roomId,
)
