package com.getfirepit.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.getfirepit.core.model.MeshNode

@Entity(tableName = "nodes")
data class NodeEntity(
    @PrimaryKey val nodeNum: Int,
    val userId: String?,
    val longName: String?,
    val shortName: String?,
    val hwModel: String?,
    val role: String?,
    val publicKey: String?,
    val isUnmessagable: Boolean,
    val lastHeard: Long?,
    val snr: Float?,
    val rssi: Int?,
    val hopsAway: Int?,
    val batteryLevel: Int?,
    val voltage: Float?,
    val channelUtilization: Float?,
    val airUtilTx: Float?,
    val isFavorite: Boolean,
    val firstSeen: Long,
)

internal fun NodeEntity.toDomain() = MeshNode(
    nodeNum = nodeNum,
    userId = userId,
    longName = longName,
    shortName = shortName,
    hwModel = hwModel,
    role = role,
    publicKey = publicKey,
    isUnmessagable = isUnmessagable,
    lastHeard = lastHeard,
    snr = snr,
    rssi = rssi,
    hopsAway = hopsAway,
    batteryLevel = batteryLevel,
    voltage = voltage,
    channelUtilization = channelUtilization,
    airUtilTx = airUtilTx,
    isFavorite = isFavorite,
)

internal fun MeshNode.toEntity(firstSeen: Long) = NodeEntity(
    nodeNum = nodeNum,
    userId = userId,
    longName = longName,
    shortName = shortName,
    hwModel = hwModel,
    role = role,
    publicKey = publicKey,
    isUnmessagable = isUnmessagable,
    lastHeard = lastHeard,
    snr = snr,
    rssi = rssi,
    hopsAway = hopsAway,
    batteryLevel = batteryLevel,
    voltage = voltage,
    channelUtilization = channelUtilization,
    airUtilTx = airUtilTx,
    isFavorite = isFavorite,
    firstSeen = firstSeen,
)
