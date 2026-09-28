package com.getfirepit.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.ModuleConfig

class MessageAlertsTest {

    @Test
    fun `a radio with no alert section announces nothing`() {
        assertEquals(MessageAlerts.PHONE_ONLY, MessageAlerts.of(null))
    }

    @Test
    fun `the module being on is not enough on its own`() {
        val enabledButSilent = ModuleConfig.ExternalNotificationConfig(
            enabled = true,
            alert_message = false,
        )

        assertEquals(MessageAlerts.PHONE_ONLY, MessageAlerts.of(enabledButSilent))
    }

    @Test
    fun `alerting on a message reads as the radio joining in`() {
        val alerting = ModuleConfig.ExternalNotificationConfig(
            enabled = true,
            alert_message = true,
        )

        assertEquals(MessageAlerts.PHONE_AND_NODE, MessageAlerts.of(alerting))
    }

    /**
     * The firmware replaces the whole section, so anything not carried over is
     * reset — including the pins that say where the buzzer is actually wired.
     */
    @Test
    fun `writing the choice keeps the wiring the radio reported`() {
        val wired = ModuleConfig.ExternalNotificationConfig(
            output = 13,
            output_buzzer = 25,
            output_vibra = 14,
            output_ms = 900,
            nag_timeout = 60,
            use_pwm = true,
            active = true,
        )

        val written = MessageAlerts.applyTo(wired, MessageAlerts.PHONE_AND_NODE)

        assertEquals(13, written.output)
        assertEquals(25, written.output_buzzer)
        assertEquals(14, written.output_vibra)
        assertEquals(900, written.output_ms)
        assertEquals(60, written.nag_timeout)
        assertTrue(written.use_pwm)
        assertTrue(written.active)
    }

    @Test
    fun `choosing the radio turns on every way a board might have of saying so`() {
        val written = MessageAlerts.applyTo(null, MessageAlerts.PHONE_AND_NODE)

        assertTrue(written.enabled)
        assertTrue(written.alert_message)
        assertTrue(written.alert_message_buzzer)
        assertTrue(written.alert_message_vibra)
    }

    /** A buzz is a message carrying only a bell, so the two are never split. */
    @Test
    fun `a bell is announced alongside a message`() {
        val written = MessageAlerts.applyTo(null, MessageAlerts.PHONE_AND_NODE)

        assertTrue(written.alert_bell)
        assertTrue(written.alert_bell_buzzer)
        assertTrue(written.alert_bell_vibra)
    }

    @Test
    fun `choosing the phone alone silences the radio without rewiring it`() {
        val wired = ModuleConfig.ExternalNotificationConfig(
            enabled = true,
            alert_message = true,
            alert_bell = true,
            output_buzzer = 25,
        )

        val written = MessageAlerts.applyTo(wired, MessageAlerts.PHONE_ONLY)

        assertFalse(written.enabled)
        assertFalse(written.alert_message)
        assertFalse(written.alert_bell)
        assertEquals(25, written.output_buzzer)
    }

    @Test
    fun `what was written reads back as what was chosen`() {
        MessageAlerts.entries.forEach { choice ->
            assertEquals(choice, MessageAlerts.of(MessageAlerts.applyTo(null, choice)))
        }
    }
}
