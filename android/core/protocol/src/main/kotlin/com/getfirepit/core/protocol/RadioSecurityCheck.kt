package com.getfirepit.core.protocol

import com.getfirepit.core.protocol.phoneapi.RadioSnapshot
import kotlin.random.Random
import org.meshtastic.proto.Config
import org.meshtastic.proto.ModuleConfig

/**
 * Something in a radio's own settings that lets people read it or run it.
 *
 * The radio holds every room's channel key and its own private key, and hands
 * both to whatever connects to it. Sealing keeps the words and positions out of
 * reach either way, but the radio is still the easiest thing in the group to
 * take over, so these are checked on every connection rather than trusted.
 */
enum class RadioRisk(
    val title: String,
    val detail: String,
    /** False when the radio itself refuses to be changed from this phone. */
    val canFix: Boolean = true,
) {
    BLUETOOTH_OPEN(
        title = "Bluetooth has no PIN",
        detail = "Anyone nearby can connect to this radio whenever your phone isn't, read its room " +
            "and private keys, and change its settings. Setting a PIN closes that.",
    ),
    BLUETOOTH_DEFAULT_PIN(
        title = "Bluetooth uses the default PIN",
        detail = "This radio pairs with 123456, the PIN every radio without a screen ships with. " +
            "Anyone nearby can pair whenever your phone isn't connected, and read or change " +
            "everything on it. Setting a new PIN closes that.",
    ),
    REMOTE_ADMIN_KEY(
        title = "Remote admin is on",
        detail = "An admin key is set, so whoever holds its partner can read this radio's keys and " +
            "change its settings over the mesh — even after a room moves to new keys. Remove it " +
            "unless you set it yourself.",
    ),
    MANAGED(
        title = "Managed by someone else",
        detail = "This radio is in managed mode, so it ignores settings from this phone. Firepit " +
            "can't keep it from broadcasting or change its keys. Whoever manages it can.",
        canFix = false,
    ),
    LEGACY_ADMIN_CHANNEL(
        title = "Admin channel is on",
        detail = "The old admin channel lets anyone holding that channel's key run this radio. " +
            "Turning it off closes that.",
    ),
    DEBUG_LOG(
        title = "Debug logs go to connected apps",
        detail = "Debug logging hands the radio's own log to whatever app connects, including " +
            "what it hears. Turning it off closes that.",
    ),
    MQTT_UPLINK(
        title = "Copies traffic to the internet",
        detail = "MQTT is on with an uplink, so this radio copies packets to an internet server — " +
            "including the private messages that carry room keys. Turning MQTT off stops it.",
    ),
    MQTT_MAP_REPORT(
        title = "Reports to a public map",
        detail = "This radio publishes its name and position to an internet map whenever it has " +
            "a connection. Turning MQTT off stops it.",
    ),
}

object RadioSecurityCheck {

    /** What every radio without a screen pairs with out of the box. */
    const val DEFAULT_PIN = 123456

    /**
     * What is wrong with this radio, most serious first. Empty when nothing is,
     * and also when the radio has not reported: absence is not a finding.
     */
    fun risksOf(snapshot: RadioSnapshot): List<RadioRisk> = buildList {
        snapshot.bluetooth?.takeIf { it.enabled }?.let { bluetooth ->
            when {
                bluetooth.mode == Config.BluetoothConfig.PairingMode.NO_PIN -> add(RadioRisk.BLUETOOTH_OPEN)
                bluetooth.mode == Config.BluetoothConfig.PairingMode.FIXED_PIN &&
                    bluetooth.fixed_pin == DEFAULT_PIN -> add(RadioRisk.BLUETOOTH_DEFAULT_PIN)
            }
        }
        snapshot.security?.let { security ->
            if (security.admin_key.any { it.size > 0 }) add(RadioRisk.REMOTE_ADMIN_KEY)
            if (security.is_managed) add(RadioRisk.MANAGED)
            if (security.admin_channel_enabled) add(RadioRisk.LEGACY_ADMIN_CHANNEL)
            if (security.debug_log_api_enabled) add(RadioRisk.DEBUG_LOG)
        }
        snapshot.mqtt?.takeIf { it.enabled }?.let { mqtt ->
            // One uplinked channel is enough: the firmware then uplinks every
            // PKI packet it carries as well, whichever channel it came in on.
            val uplinking = snapshot.channels.values.any { it.settings?.uplink_enabled == true }
            if (uplinking) add(RadioRisk.MQTT_UPLINK)
            if (mqtt.map_reporting_enabled) add(RadioRisk.MQTT_MAP_REPORT)
        }
    }

    /**
     * A six-digit PIN that is not the default. Kept away from the published one
     * so a fix can never land back on it.
     */
    fun newPin(random: Random = Random.Default): Int {
        var pin = DEFAULT_PIN
        while (pin == DEFAULT_PIN) pin = random.nextInt(100_000, 1_000_000)
        return pin
    }

    /** The radio's own Bluetooth section with a PIN required, everything else kept. */
    fun withPin(current: Config.BluetoothConfig, pin: Int): Config.BluetoothConfig =
        current.copy(enabled = true, mode = Config.BluetoothConfig.PairingMode.FIXED_PIN, fixed_pin = pin)

    /**
     * The radio's own security section with every way in from elsewhere closed.
     * Its own keys are kept, or it would stop being the node everyone knows.
     */
    fun withoutRemoteAccess(current: Config.SecurityConfig): Config.SecurityConfig =
        current.copy(admin_key = emptyList(), admin_channel_enabled = false, debug_log_api_enabled = false)

    /** The radio's own MQTT section, switched off, its server details kept for whoever set them. */
    fun withoutMqtt(current: ModuleConfig.MQTTConfig): ModuleConfig.MQTTConfig =
        current.copy(enabled = false, map_reporting_enabled = false)
}
