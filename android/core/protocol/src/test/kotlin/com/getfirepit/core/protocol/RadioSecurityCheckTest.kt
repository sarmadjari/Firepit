package com.getfirepit.core.protocol

import com.getfirepit.core.protocol.phoneapi.RadioSnapshot
import kotlin.random.Random
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.ModuleConfig

/**
 * The settings that let people read or run a radio, stated as tests.
 *
 * If one of these stops being reported, a radio anybody can pair with or
 * administer goes on carrying the group's keys without anyone being told.
 */
class RadioSecurityCheckTest {

    private fun snapshot(
        bluetooth: Config.BluetoothConfig? = Config.BluetoothConfig(
            enabled = true,
            mode = Config.BluetoothConfig.PairingMode.RANDOM_PIN,
        ),
        security: Config.SecurityConfig? = Config.SecurityConfig(),
        mqtt: ModuleConfig.MQTTConfig? = null,
        uplink: Boolean = false,
    ) = RadioSnapshot(
        configs = listOfNotNull(
            bluetooth?.let { Config(bluetooth = it) },
            security?.let { Config(security = it) },
        ),
        moduleConfigs = listOfNotNull(mqtt?.let { ModuleConfig(mqtt = it) }),
        channels = mapOf(
            0 to Channel(index = 0, settings = ChannelSettings(uplink_enabled = false)),
            1 to Channel(index = 1, settings = ChannelSettings(uplink_enabled = uplink)),
        ),
    )

    @Test
    fun `a radio asking for a random pin with nothing else open is fine`() {
        assertTrue(RadioSecurityCheck.risksOf(snapshot()).isEmpty())
    }

    @Test
    fun `no pin at all is reported`() {
        val open = snapshot(bluetooth = Config.BluetoothConfig(enabled = true, mode = Config.BluetoothConfig.PairingMode.NO_PIN))

        assertEquals(listOf(RadioRisk.BLUETOOTH_OPEN), RadioSecurityCheck.risksOf(open))
    }

    /** What every screenless radio, the WisMesh Tag among them, ships with. */
    @Test
    fun `the published default pin is reported`() {
        val default = snapshot(
            bluetooth = Config.BluetoothConfig(
                enabled = true,
                mode = Config.BluetoothConfig.PairingMode.FIXED_PIN,
                fixed_pin = RadioSecurityCheck.DEFAULT_PIN,
            ),
        )

        assertEquals(listOf(RadioRisk.BLUETOOTH_DEFAULT_PIN), RadioSecurityCheck.risksOf(default))
    }

    @Test
    fun `a fixed pin of the owner's choosing is fine`() {
        val chosen = snapshot(
            bluetooth = Config.BluetoothConfig(enabled = true, mode = Config.BluetoothConfig.PairingMode.FIXED_PIN, fixed_pin = 482_913),
        )

        assertTrue(RadioSecurityCheck.risksOf(chosen).isEmpty())
    }

    @Test
    fun `bluetooth switched off cannot be paired with at all`() {
        val off = snapshot(bluetooth = Config.BluetoothConfig(enabled = false, mode = Config.BluetoothConfig.PairingMode.NO_PIN))

        assertTrue(RadioSecurityCheck.risksOf(off).isEmpty())
    }

    @Test
    fun `every way in from elsewhere is reported`() {
        val open = snapshot(
            security = Config.SecurityConfig(
                admin_key = listOf(ByteArray(32) { 1 }.toByteString()),
                is_managed = true,
                admin_channel_enabled = true,
                debug_log_api_enabled = true,
            ),
        )

        assertEquals(
            listOf(RadioRisk.REMOTE_ADMIN_KEY, RadioRisk.MANAGED, RadioRisk.LEGACY_ADMIN_CHANNEL, RadioRisk.DEBUG_LOG),
            RadioSecurityCheck.risksOf(open),
        )
        assertFalse("the radio refuses this phone, so there is nothing to offer", RadioRisk.MANAGED.canFix)
    }

    @Test
    fun `an empty admin key slot is not an admin key`() {
        val empty = snapshot(security = Config.SecurityConfig(admin_key = listOf(okio.ByteString.EMPTY)))

        assertTrue(RadioSecurityCheck.risksOf(empty).isEmpty())
    }

    /** One uplinked channel is enough for the firmware to uplink every PKI packet too. */
    @Test
    fun `mqtt with any uplinked channel is reported`() {
        val uplinked = snapshot(mqtt = ModuleConfig.MQTTConfig(enabled = true), uplink = true)

        assertEquals(listOf(RadioRisk.MQTT_UPLINK), RadioSecurityCheck.risksOf(uplinked))
    }

    @Test
    fun `a map report is reported, and mqtt switched off reports nothing`() {
        val reporting = snapshot(mqtt = ModuleConfig.MQTTConfig(enabled = true, map_reporting_enabled = true))
        val off = snapshot(mqtt = ModuleConfig.MQTTConfig(enabled = false, map_reporting_enabled = true), uplink = true)

        assertEquals(listOf(RadioRisk.MQTT_MAP_REPORT), RadioSecurityCheck.risksOf(reporting))
        assertTrue(RadioSecurityCheck.risksOf(off).isEmpty())
    }

    @Test
    fun `a radio that has not reported is not a finding`() {
        assertTrue(RadioSecurityCheck.risksOf(RadioSnapshot()).isEmpty())
    }

    @Test
    fun `a new pin is six digits and never the published one`() {
        // A generator that offers the default first must not be taken at its word.
        val rigged = object : Random() {
            private var offered = false
            override fun nextBits(bitCount: Int): Int = Random.Default.nextBits(bitCount)
            override fun nextInt(from: Int, until: Int): Int =
                if (offered) 482_913 else RadioSecurityCheck.DEFAULT_PIN.also { offered = true }
        }

        assertEquals(482_913, RadioSecurityCheck.newPin(rigged))
        repeat(1_000) {
            val pin = RadioSecurityCheck.newPin()
            assertNotEquals(RadioSecurityCheck.DEFAULT_PIN, pin)
            assertTrue("$pin", pin in 100_000..999_999)
        }
    }

    @Test
    fun `the fixes change only what they are for`() {
        val bluetooth = Config.BluetoothConfig(enabled = true, mode = Config.BluetoothConfig.PairingMode.NO_PIN)
        val fixed = RadioSecurityCheck.withPin(bluetooth, 482_913)
        assertEquals(Config.BluetoothConfig.PairingMode.FIXED_PIN, fixed.mode)
        assertEquals(482_913, fixed.fixed_pin)

        val security = Config.SecurityConfig(
            public_key = ByteArray(32) { 2 }.toByteString(),
            private_key = ByteArray(32) { 3 }.toByteString(),
            admin_key = listOf(ByteArray(32) { 1 }.toByteString()),
            admin_channel_enabled = true,
            debug_log_api_enabled = true,
            serial_enabled = true,
        )
        val closed = RadioSecurityCheck.withoutRemoteAccess(security)
        assertTrue(closed.admin_key.isEmpty())
        assertFalse(closed.admin_channel_enabled)
        assertFalse(closed.debug_log_api_enabled)
        // Its own identity stays, or it would stop being the node everyone knows.
        assertEquals(security.public_key, closed.public_key)
        assertEquals(security.private_key, closed.private_key)
        assertEquals(security.serial_enabled, closed.serial_enabled)

        val mqtt = RadioSecurityCheck.withoutMqtt(ModuleConfig.MQTTConfig(enabled = true, address = "broker", map_reporting_enabled = true))
        assertFalse(mqtt.enabled)
        assertFalse(mqtt.map_reporting_enabled)
        assertEquals("broker", mqtt.address)
    }
}
