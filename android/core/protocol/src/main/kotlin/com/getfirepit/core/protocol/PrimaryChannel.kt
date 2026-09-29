package com.getfirepit.core.protocol

import com.getfirepit.protocol.meshchat.MeshMode
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import org.meshtastic.proto.Channel
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.ModuleSettings

/**
 * How far a node's traffic is allowed to travel, and through whom.
 *
 * The primary channel's *name* is hashed into the LoRa frequency slot, so this
 * is the one setting that decides which radios can even hear us. Content is
 * sealed either way; what changes is who relays it and how much airtime we
 * share.
 */
enum class RangeMode(val label: String, val summary: String) {
    /**
     * Our own frequency slot. Only Firepit nodes hear us, and only Firepit
     * nodes carry our packets onward.
     */
    GROUP_ONLY(
        label = "Our nodes only",
        summary = "Quietest and hardest to notice. Messages travel only through Firepit " +
            "radios, so your range is your own group's range.",
    ),

    /**
     * The public mesh's frequency slot. Stranger nodes rebroadcast our packets
     * without being able to read them, which buys range at the cost of being
     * visible as traffic.
     */
    PUBLIC_RELAY(
        label = "Nearby radios too",
        summary = "Further reach. Other Meshtastic radios nearby pass your messages along " +
            "without being able to read them. They can tell that traffic exists, and your " +
            "radio carries theirs in return.",
    ),
    ;

    val wire: MeshMode
        get() = when (this) {
            GROUP_ONLY -> MeshMode.GROUP_ONLY
            PUBLIC_RELAY -> MeshMode.PUBLIC_RELAY
        }

    companion object {
        /** Quietest wins by default: range is a choice, exposure should not be. */
        val DEFAULT = GROUP_ONLY

        fun named(name: String?): RangeMode = entries.firstOrNull { it.name == name } ?: DEFAULT

        fun of(mode: MeshMode?): RangeMode = when (mode) {
            MeshMode.PUBLIC_RELAY -> PUBLIC_RELAY
            MeshMode.GROUP_ONLY -> GROUP_ONLY
            null -> DEFAULT
        }
    }
}

/**
 * Whether this radio's own identity has been made private to Firepit.
 *
 * Three states rather than two, because "nobody has been asked yet" is not the
 * same as "somebody said no". Only the first is worth interrupting for.
 */
enum class RadioPrivacy(val label: String, val summary: String) {
    /** Nobody has been asked. The radio stays exactly as it was found. */
    UNDECIDED(
        label = "Not set",
        summary = "This radio is still set up the way you found it.",
    ),

    /**
     * Slot 0 carries Firepit's key: ordinary Meshtastic radios no longer see
     * this node. The key ships in the app, so every Firepit install can.
     */
    FIREPIT(
        label = "Private to Firepit",
        summary = "Ordinary Meshtastic radios no longer see this one. Its name and battery level " +
            "are still sent in the open to anyone running Firepit, because that key comes with " +
            "the app. If you also use this radio on another Meshtastic mesh, it will leave that mesh.",
    ),

    /** Left as the factory or its owner set it. */
    OPEN(
        label = "Public, as it came",
        summary = "This radio keeps working as a normal Meshtastic node, on whatever mesh you " +
            "already use it for. Its name and battery level are visible to any Meshtastic " +
            "device nearby.",
    ),
    ;

    companion object {
        /** Nothing is taken over until somebody says so. */
        val DEFAULT = UNDECIDED

        fun named(name: String?): RadioPrivacy = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Slot 0, which every Firepit node must be holding before it says anything.
 *
 * The firmware ships this slot with the published default key (`AQ==`, "listed
 * in this source code"), and it is the slot that carries NodeInfo and telemetry
 * and that periodic broadcasts default to. Left alone, a Firepit node announces
 * its owner's name and battery to every Meshtastic radio in relay range, and
 * any packet that falls back to channel encryption is readable by all of them.
 *
 * Writing it does three things: names and telemetry become unreadable to
 * strangers, the NodeDB stops filling with nodes we will never talk to (the
 * firmware admits only packets it could decrypt), and the name we choose picks
 * the frequency slot, which is what [RangeMode] is.
 *
 * The key here is **not a secret**. It ships in every copy of the app, so
 * anyone can extract it; it is what makes Firepit nodes recognise each other,
 * not what keeps words private. Rooms and direct messages carry their own
 * encryption on top and never rely on this.
 */
object PrimaryChannel {

    const val SLOT: Int = ChannelSlotManager.PRIMARY_SLOT

    /** "MESH". Marks the slot as ours so a re-provision can tell it from a user's own channel. */
    const val ID: Int = 0x4D455348

    /** Hashed into a frequency slot of our own, away from the public mesh. */
    const val GROUP_NAME: String = "MeshChat"

    /**
     * Empty, so the firmware hashes the modem preset's display name instead and
     * lands on the same slot as the public mesh.
     */
    const val PUBLIC_NAME: String = ""

    /**
     * The app-wide primary key, base64, byte-for-byte identical on every
     * platform. Asserted against `protos/meshchat-primary-key.txt` in
     * PrimaryChannelTest: the two apps cannot see each other if it drifts.
     */
    const val KEY_BASE64: String = "3RrNdyWIx+i2Z2QBs2sRVxXC1uGZs7oqCN3lIyVMl3k="

    val key: ByteString = requireNotNull(KEY_BASE64.decodeBase64()) { "primary key is not base64" }

    /** True for the key every copy of Firepit carries, which keeps nothing from anyone who has the app. */
    fun isAppWideKey(psk: ByteString?): Boolean = psk == key

    /** How a channel's key reads to a person: the app-wide key is named for what it is. */
    fun keyLabel(psk: ByteString?): String =
        if (isAppWideKey(psk)) APP_WIDE_KEY_LABEL else ChannelKey.of(psk?.toByteArray()).label

    const val APP_WIDE_KEY_LABEL = "Firepit's shared key: anyone with the app can read it"

    fun nameFor(mode: RangeMode): String = when (mode) {
        RangeMode.GROUP_ONLY -> GROUP_NAME
        RangeMode.PUBLIC_RELAY -> PUBLIC_NAME
    }

    /** What slot 0 must look like for [mode]. */
    fun channelFor(mode: RangeMode): Channel = Channel(
        index = SLOT,
        role = Channel.Role.PRIMARY,
        settings = ChannelSettings(
            name = nameFor(mode),
            psk = key,
            id = ID,
            // The primary is the audience nobody chose, and the firmware's own
            // periodic broadcasts default to it.
            uplink_enabled = false,
            downlink_enabled = false,
            module_settings = ModuleSettings(position_precision = PositionPrecision.DISABLED),
        ),
    )

    /** The mode a radio is currently in, or null when slot 0 is not ours at all. */
    fun modeOf(channel: Channel?): RangeMode? {
        val settings = channel?.settings ?: return null
        if (channel.role != Channel.Role.PRIMARY || settings.psk != key) return null
        return when (settings.name) {
            GROUP_NAME -> RangeMode.GROUP_ONLY
            PUBLIC_NAME -> RangeMode.PUBLIC_RELAY
            else -> null
        }
    }

    /**
     * True when the radio already agrees, so a reconnect writes nothing in the
     * normal case. Compares only what we set: the firmware fills other fields.
     */
    fun matches(channel: Channel?, mode: RangeMode): Boolean {
        val settings = channel?.settings ?: return false
        return channel.role == Channel.Role.PRIMARY &&
            settings.name == nameFor(mode) &&
            settings.psk == key &&
            settings.id == ID &&
            !settings.uplink_enabled &&
            !settings.downlink_enabled &&
            (settings.module_settings?.position_precision ?: 0) == PositionPrecision.DISABLED
    }

    /**
     * True when this slot still carries a key every Meshtastic radio holds.
     *
     * Worth saying out loud rather than silently fixing: until it is rewritten,
     * the node's name and battery are public.
     */
    fun isPublic(channel: Channel?): Boolean = MeshtasticChannel.isPublic(channel?.settings)
}
