package com.getfirepit.core.protocol

/**
 * Parsed from `DeviceMetadata.firmware_version`, e.g. "2.7.26.54e0d8d" or
 * "2.8.1-dev". Used only for behaviour differences that have no capability
 * field of their own; anything with a field is gated on the field instead.
 */
data class FirmwareVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val raw: String,
) : Comparable<FirmwareVersion> {

    override fun compareTo(other: FirmwareVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = raw

    companion object {
        private val PATTERN = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?""")

        fun parseOrNull(raw: String): FirmwareVersion? {
            val match = PATTERN.find(raw.trim()) ?: return null
            val (major, minor, patch) = match.destructured
            return FirmwareVersion(
                major = major.toInt(),
                minor = minor.toInt(),
                patch = patch.toIntOrNull() ?: 0,
                raw = raw.trim(),
            )
        }
    }
}

/**
 * What this radio can actually do. Gate features on these, never on a version
 * string, wherever the protobuf exposes a capability field.
 */
data class RadioCapabilities(
    val firmwareVersion: FirmwareVersion?,
    /** X25519 public-key encryption for direct messages. */
    val supportsPki: Boolean,
    /** 2.8 XEdDSA broadcast signing — drives the "verified" badge and nothing else. */
    val supportsSigning: Boolean,
    val minAppVersion: Int,
    /** 2.8 only; 0 on 2.7. */
    val nodeDbCount: Int,
) {
    /** Firepit's baseline. Below this the PhoneAPI shape we rely on is not guaranteed. */
    val isSupported: Boolean
        get() = firmwareVersion == null || firmwareVersion >= MINIMUM_FIRMWARE

    companion object {
        val MINIMUM_FIRMWARE = FirmwareVersion(2, 7, 0, "2.7.0")
    }
}
