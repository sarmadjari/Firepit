import FirepitProtos
import Foundation
import Testing

@testable import FirepitProtocol

/// The settings that let people read or run a radio, stated as tests.
///
/// If one of these stops being reported, a radio anybody can pair with or
/// administer goes on carrying the group's keys without anyone being told.
@Suite struct RadioSecurityCheckTests {
    private func snapshot(
        bluetooth: Config.BluetoothConfig? = bluetoothConfig(enabled: true, mode: .randomPin),
        security: Config.SecurityConfig? = Config.SecurityConfig(),
        mqtt: ModuleConfig.MQTTConfig? = nil,
        uplink: Bool = false
    ) -> RadioSnapshot {
        var configs: [Config] = []
        if let bluetooth {
            var config = Config()
            config.bluetooth = bluetooth
            configs.append(config)
        }
        if let security {
            var config = Config()
            config.security = security
            configs.append(config)
        }
        var moduleConfigs: [ModuleConfig] = []
        if let mqtt {
            var config = ModuleConfig()
            config.mqtt = mqtt
            moduleConfigs.append(config)
        }
        return RadioSnapshot(
            channels: [
                0: Self.channel(index: 0, uplinkEnabled: false),
                1: Self.channel(index: 1, uplinkEnabled: uplink),
            ],
            configs: configs,
            moduleConfigs: moduleConfigs
        )
    }

    @Test func aRadioAskingForARandomPinWithNothingElseOpenIsFine() {
        #expect(RadioSecurityCheck.risksOf(snapshot: snapshot()).isEmpty)
    }

    @Test func noPinAtAllIsReported() {
        let open = snapshot(bluetooth: Self.bluetoothConfig(enabled: true, mode: .noPin))

        #expect([RadioRisk.bluetoothOpen] == RadioSecurityCheck.risksOf(snapshot: open))
    }

    /// What every screenless radio, the WisMesh Tag among them, ships with.
    @Test func thePublishedDefaultPinIsReported() {
        let `default` = snapshot(
            bluetooth: Self.bluetoothConfig(
                enabled: true,
                mode: .fixedPin,
                fixedPin: RadioSecurityCheck.defaultPin
            )
        )

        #expect([RadioRisk.bluetoothDefaultPin] == RadioSecurityCheck.risksOf(snapshot: `default`))
    }

    @Test func aFixedPinOfTheOwnersChoosingIsFine() {
        let chosen = snapshot(
            bluetooth: Self.bluetoothConfig(enabled: true, mode: .fixedPin, fixedPin: 482_913)
        )

        #expect(RadioSecurityCheck.risksOf(snapshot: chosen).isEmpty)
    }

    @Test func bluetoothSwitchedOffCannotBePairedWithAtAll() {
        let off = snapshot(bluetooth: Self.bluetoothConfig(enabled: false, mode: .noPin))

        #expect(RadioSecurityCheck.risksOf(snapshot: off).isEmpty)
    }

    @Test func everyWayInFromElsewhereIsReported() {
        var security = Config.SecurityConfig()
        security.adminKey = [Data(repeating: 1, count: 32)]
        security.isManaged = true
        security.adminChannelEnabled = true
        security.debugLogApiEnabled = true
        let open = snapshot(security: security)

        #expect(
            [RadioRisk.remoteAdminKey, RadioRisk.managed, RadioRisk.legacyAdminChannel, RadioRisk.debugLog]
                == RadioSecurityCheck.risksOf(snapshot: open)
        )
        #expect(!RadioRisk.managed.canFix)
    }

    @Test func anEmptyAdminKeySlotIsNotAnAdminKey() {
        var security = Config.SecurityConfig()
        security.adminKey = [Data()]
        let empty = snapshot(security: security)

        #expect(RadioSecurityCheck.risksOf(snapshot: empty).isEmpty)
    }

    /// One uplinked channel is enough for the firmware to uplink every PKI packet too.
    @Test func mqttWithAnyUplinkedChannelIsReported() {
        var mqtt = ModuleConfig.MQTTConfig()
        mqtt.enabled = true
        let uplinked = snapshot(mqtt: mqtt, uplink: true)

        #expect([RadioRisk.mqttUplink] == RadioSecurityCheck.risksOf(snapshot: uplinked))
    }

    @Test func aMapReportIsReportedAndMqttSwitchedOffReportsNothing() {
        var reportingMqtt = ModuleConfig.MQTTConfig()
        reportingMqtt.enabled = true
        reportingMqtt.mapReportingEnabled = true
        let reporting = snapshot(mqtt: reportingMqtt)
        var offMqtt = ModuleConfig.MQTTConfig()
        offMqtt.enabled = false
        offMqtt.mapReportingEnabled = true
        let off = snapshot(mqtt: offMqtt, uplink: true)

        #expect([RadioRisk.mqttMapReport] == RadioSecurityCheck.risksOf(snapshot: reporting))
        #expect(RadioSecurityCheck.risksOf(snapshot: off).isEmpty)
    }

    @Test func aRadioThatHasNotReportedIsNotAFinding() {
        #expect(RadioSecurityCheck.risksOf(snapshot: RadioSnapshot()).isEmpty)
    }

    @Test func aNewPinIsSixDigitsAndNeverThePublishedOne() {
        // A generator that offers the default first must not be taken at its word.
        let rigged = RiggedPinGenerator()

        #expect(482_913 == RadioSecurityCheck.newPin(random: rigged.nextInt(in:)))
        for _ in 0..<1_000 {
            let pin = RadioSecurityCheck.newPin()
            #expect(RadioSecurityCheck.defaultPin != pin)
            #expect((100_000...999_999).contains(Int(pin)))
        }
    }

    @Test func theFixesChangeOnlyWhatTheyAreFor() {
        let bluetooth = Self.bluetoothConfig(enabled: true, mode: .noPin)
        let fixed = RadioSecurityCheck.withPin(current: bluetooth, pin: 482_913)
        #expect(Config.BluetoothConfig.PairingMode.fixedPin == fixed.mode)
        #expect(482_913 == fixed.fixedPin)

        var security = Config.SecurityConfig()
        security.publicKey = Data(repeating: 2, count: 32)
        security.privateKey = Data(repeating: 3, count: 32)
        security.adminKey = [Data(repeating: 1, count: 32)]
        security.adminChannelEnabled = true
        security.debugLogApiEnabled = true
        security.serialEnabled = true
        let closed = RadioSecurityCheck.withoutRemoteAccess(current: security)
        #expect(closed.adminKey.isEmpty)
        #expect(!closed.adminChannelEnabled)
        #expect(!closed.debugLogApiEnabled)
        // Its own identity stays, or it would stop being the node everyone knows.
        #expect(security.publicKey == closed.publicKey)
        #expect(security.privateKey == closed.privateKey)
        #expect(security.serialEnabled == closed.serialEnabled)

        var mqttConfig = ModuleConfig.MQTTConfig()
        mqttConfig.enabled = true
        mqttConfig.address = "broker"
        mqttConfig.mapReportingEnabled = true
        let mqtt = RadioSecurityCheck.withoutMqtt(current: mqttConfig)
        #expect(!mqtt.enabled)
        #expect(!mqtt.mapReportingEnabled)
        #expect("broker" == mqtt.address)
    }

    private static func bluetoothConfig(
        enabled: Bool,
        mode: Config.BluetoothConfig.PairingMode,
        fixedPin: UInt32 = 0
    ) -> Config.BluetoothConfig {
        var config = Config.BluetoothConfig()
        config.enabled = enabled
        config.mode = mode
        config.fixedPin = fixedPin
        return config
    }

    private static func channel(index: Int32, uplinkEnabled: Bool) -> Channel {
        var settings = ChannelSettings()
        settings.uplinkEnabled = uplinkEnabled
        var channel = Channel()
        channel.index = index
        channel.settings = settings
        return channel
    }
}

private final class RiggedPinGenerator: @unchecked Sendable {
    private var offered = false

    func nextInt(in range: Range<Int>) -> Int {
        if offered {
            return 482_913
        } else {
            offered = true
            return Int(RadioSecurityCheck.defaultPin)
        }
    }
}
