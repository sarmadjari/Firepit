package com.getfirepit.core.protocol

import org.meshtastic.proto.ModuleConfig

/**
 * Where an arriving message announces itself.
 *
 * The radio's alert is the firmware's own, so it still happens with Firepit
 * closed, and it is the only one that reaches you when the phone is in a bag or
 * out of Bluetooth range. What the radio can actually do is the board's
 * business: some beep, some vibrate, most only blink.
 */
enum class MessageAlerts(val label: String, val summary: String) {

    PHONE_ONLY(
        label = "Phone only",
        summary = "Only this phone announces a message. The radio stays silent.",
    ),

    PHONE_AND_NODE(
        label = "Phone and radio",
        summary = "The radio beeps, vibrates or blinks as well — whichever its board has. " +
            "Useful when the phone is away from you.",
    ),
    ;

    companion object {

        /** Read from the radio rather than from what we last asked it to do. */
        fun of(config: ModuleConfig.ExternalNotificationConfig?): MessageAlerts =
            if (config?.enabled == true && config.alert_message) PHONE_AND_NODE else PHONE_ONLY

        /**
         * The section to write back.
         *
         * Built from what the radio reported, because the firmware replaces the
         * whole section: anything left out would be reset to its default,
         * including the pin numbers that say where the buzzer is wired.
         */
        fun applyTo(
            config: ModuleConfig.ExternalNotificationConfig?,
            choice: MessageAlerts,
        ): ModuleConfig.ExternalNotificationConfig {
            val on = choice == PHONE_AND_NODE
            return (config ?: ModuleConfig.ExternalNotificationConfig()).copy(
                enabled = on,
                // All three, because which one exists is the board's business
                // and asking for one a board lacks costs nothing.
                alert_message = on,
                alert_message_buzzer = on,
                alert_message_vibra = on,
                // A buzz is a message with nothing but a bell in it, so the two
                // travel together: turning one on and not the other would mean
                // the buzz somebody sent you arrived silently.
                alert_bell = on,
                alert_bell_buzzer = on,
                alert_bell_vibra = on,
            )
        }
    }
}
