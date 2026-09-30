import FirepitProtos
import Foundation

/// Everything the radio reported during one config download.
///
/// The firmware sends this in a fixed order (my_info first, config_complete_id
/// last), but the client only depends on those two anchors — each variant is
/// stored as it arrives.
public struct RadioSnapshot: Equatable, Sendable {
    public var myInfo: MyNodeInfo?
    public var metadata: DeviceMetadata?
    public var channels: [Int32: Channel]
    public var configs: [Config]
    public var moduleConfigs: [ModuleConfig]
    public var nodes: [Int32: NodeInfo]
    /// 2.8 only: which presets the current region allows.
    public var regionPresets: LoRaRegionPresetMap?

    public init(
        myInfo: MyNodeInfo? = nil,
        metadata: DeviceMetadata? = nil,
        channels: [Int32: Channel] = [:],
        configs: [Config] = [],
        moduleConfigs: [ModuleConfig] = [],
        nodes: [Int32: NodeInfo] = [:],
        regionPresets: LoRaRegionPresetMap? = nil
    ) {
        self.myInfo = myInfo
        self.metadata = metadata
        self.channels = channels
        self.configs = configs
        self.moduleConfigs = moduleConfigs
        self.nodes = nodes
        self.regionPresets = regionPresets
    }

    public var myNodeNum: Int32? {
        guard let myInfo else {
            return nil
        }
        return Int32(bitPattern: myInfo.myNodeNum)
    }

    public var device: Config.DeviceConfig? {
        configs.compactMap { config in
            if case .device(let value)? = config.payloadVariant {
                return value
            }
            return nil
        }.first
    }

    public var position: Config.PositionConfig? {
        configs.compactMap { config in
            if case .position(let value)? = config.payloadVariant {
                return value
            }
            return nil
        }.first
    }

    public var lora: Config.LoRaConfig? {
        configs.compactMap { config in
            if case .lora(let value)? = config.payloadVariant {
                return value
            }
            return nil
        }.first
    }

    public var bluetooth: Config.BluetoothConfig? {
        configs.compactMap { config in
            if case .bluetooth(let value)? = config.payloadVariant {
                return value
            }
            return nil
        }.first
    }

    public var security: Config.SecurityConfig? {
        configs.compactMap { config in
            if case .security(let value)? = config.payloadVariant {
                return value
            }
            return nil
        }.first
    }

    public var telemetry: ModuleConfig.TelemetryConfig? {
        moduleConfigs.compactMap { config in
            if case .telemetry(let value)? = config.payloadVariant {
                return value
            }
            return nil
        }.first
    }

    public var externalNotification: ModuleConfig.ExternalNotificationConfig? {
        moduleConfigs.compactMap { config in
            if case .externalNotification(let value)? = config.payloadVariant {
                return value
            }
            return nil
        }.first
    }

    public var mqtt: ModuleConfig.MQTTConfig? {
        moduleConfigs.compactMap { config in
            if case .mqtt(let value)? = config.payloadVariant {
                return value
            }
            return nil
        }.first
    }

    public var capabilities: RadioCapabilities {
        RadioCapabilities(
            firmwareVersion: metadata.flatMap { FirmwareVersion.parseOrNull($0.firmwareVersion) },
            supportsPki: metadata?.hasPkc_p == true,
            supportsSigning: metadata?.hasXeddsa_p == true,
            minAppVersion: Int(myInfo?.minAppVersion ?? 0),
            nodeDbCount: Int(myInfo?.nodedbCount ?? 0)
        )
    }

    /// A node cannot transmit at all until its region is set, so this gates the
    /// onboarding flow.
    public var regionIsSet: Bool {
        lora?.region != nil && lora?.region != .unset
    }
}

/// Accumulates one config download. A fresh instance is created per handshake,
/// so repeated variants across reconnects never pile up.
internal actor RadioSnapshotAccumulator {
    private var snapshot = RadioSnapshot()

    /// Returns true once the radio signals this download is complete.
    func accept(message: FromRadio, expectedConfigId: Int32) -> Bool {
        switch message.payloadVariant {
        case .myInfo(let value):
            snapshot.myInfo = value
        case .metadata(let value):
            snapshot.metadata = value
        case .channel(let value):
            snapshot.channels[Int32(value.index)] = value
        case .config(let value):
            snapshot.configs.append(value)
        case .moduleConfig(let value):
            snapshot.moduleConfigs.append(value)
        case .nodeInfo(let value):
            snapshot.nodes[Int32(bitPattern: value.num)] = value
        case .regionPresets(let value):
            snapshot.regionPresets = value
        default:
            break
        }
        if case .configCompleteID(let value)? = message.payloadVariant {
            return Int32(bitPattern: value) == expectedConfigId
        }
        return false
    }

    func build() -> RadioSnapshot {
        snapshot
    }
}
