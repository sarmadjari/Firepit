package com.getfirepit.core.protocol

/**
 * How private a channel's traffic actually is.
 *
 * Meshtastic's one-byte shorthand keys are the default key and its nine
 * neighbours, and the protocol says plainly that they are "listed in this
 * source code". A channel using one is readable by any Meshtastic device out of
 * the box, which is worth saying out loud before someone trusts it.
 */
enum class ChannelKey(val label: String) {
    NONE("Not encrypted"),
    DEFAULT("Default key, which every radio has"),
    PRIVATE("Private key"),
    ;

    val isPrivate: Boolean get() = this == PRIVATE

    companion object {
        /**
         * Only the full 16 and 32 byte keys count as private.
         *
         * Anything else is reported as public. Calling an unrecognised key
         * private would be the dangerous way round to be wrong.
         */
        fun of(psk: ByteArray?): ChannelKey = when {
            psk == null || psk.isEmpty() -> NONE
            psk.size == 1 && psk[0].toInt() == 0 -> NONE
            psk.size == 16 || psk.size == 32 -> PRIVATE
            else -> DEFAULT
        }
    }
}
