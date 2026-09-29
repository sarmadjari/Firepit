package com.getfirepit.core.protocol.phoneapi

import com.getfirepit.core.protocol.FirmwareVersion
import com.getfirepit.core.protocol.RadioCapabilities
import org.meshtastic.proto.Channel
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetadata
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.LoRaRegionPresetMap
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo

/**
 * Everything the radio reported during one config download.
 *
 * The firmware sends this in a fixed order (my_info first, config_complete_id
 * last), but the client only depends on those two anchors — each variant is
 * stored as it arrives.
 */
data class RadioSnapshot(
    val myInfo: MyNodeInfo? = null,
    val metadata: DeviceMetadata? = null,
    val channels: Map<Int, Channel> = emptyMap(),
    val configs: List<Config> = emptyList(),
    val moduleConfigs: List<ModuleConfig> = emptyList(),
    val nodes: Map<Int, NodeInfo> = emptyMap(),
    /** 2.8 only: which presets the current region allows. */
    val regionPresets: LoRaRegionPresetMap? = null,
) {
    val myNodeNum: Int? get() = myInfo?.my_node_num

    val device: Config.DeviceConfig? get() = configs.firstNotNullOfOrNull { it.device }
    val position: Config.PositionConfig? get() = configs.firstNotNullOfOrNull { it.position }
    val lora: Config.LoRaConfig? get() = configs.firstNotNullOfOrNull { it.lora }
    val bluetooth: Config.BluetoothConfig? get() = configs.firstNotNullOfOrNull { it.bluetooth }
    val security: Config.SecurityConfig? get() = configs.firstNotNullOfOrNull { it.security }
    val telemetry: ModuleConfig.TelemetryConfig? get() = moduleConfigs.firstNotNullOfOrNull { it.telemetry }
    val externalNotification: ModuleConfig.ExternalNotificationConfig?
        get() = moduleConfigs.firstNotNullOfOrNull { it.external_notification }
    val mqtt: ModuleConfig.MQTTConfig? get() = moduleConfigs.firstNotNullOfOrNull { it.mqtt }

    val capabilities: RadioCapabilities
        get() = RadioCapabilities(
            firmwareVersion = metadata?.firmware_version?.let(FirmwareVersion::parseOrNull),
            supportsPki = metadata?.hasPKC == true,
            supportsSigning = metadata?.has_xeddsa == true,
            minAppVersion = myInfo?.min_app_version ?: 0,
            nodeDbCount = myInfo?.nodedb_count ?: 0,
        )

    /**
     * A node cannot transmit at all until its region is set, so this gates the
     * onboarding flow.
     */
    val regionIsSet: Boolean
        get() = lora?.region != null && lora?.region != Config.LoRaConfig.RegionCode.UNSET
}

/**
 * Accumulates one config download. A fresh instance is created per handshake,
 * so repeated variants across reconnects never pile up.
 */
internal class RadioSnapshotAccumulator {
    private var snapshot = RadioSnapshot()

    /** Returns true once the radio signals this download is complete. */
    fun accept(message: FromRadio, expectedConfigId: Int): Boolean {
        snapshot = with(message) {
            when {
                my_info != null -> snapshot.copy(myInfo = my_info)
                metadata != null -> snapshot.copy(metadata = metadata)
                channel != null -> snapshot.copy(channels = snapshot.channels + (channel.index to channel))
                config != null -> snapshot.copy(configs = snapshot.configs + config)
                moduleConfig != null -> snapshot.copy(moduleConfigs = snapshot.moduleConfigs + moduleConfig)
                node_info != null -> snapshot.copy(nodes = snapshot.nodes + (node_info.num to node_info))
                region_presets != null -> snapshot.copy(regionPresets = region_presets)
                else -> snapshot
            }
        }
        return message.config_complete_id == expectedConfigId
    }

    fun build(): RadioSnapshot = snapshot
}
