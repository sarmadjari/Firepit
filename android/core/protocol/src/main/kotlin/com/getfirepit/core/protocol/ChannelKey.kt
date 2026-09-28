package com.getfirepit.core.protocol

import okio.ByteString.Companion.toByteString

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
         * Only a full key nobody else has counts as private.
         *
         * Length alone is not enough to decide: the published default key is
         * sixteen bytes, the same as a real AES-128 key, so it is compared by
         * value. Anything unrecognised is reported as public, because calling
         * an unknown key private would be the dangerous way round to be wrong.
         */
        fun of(psk: ByteArray?): ChannelKey {
            val expanded = MeshtasticChannel.expandPsk(psk?.toByteString())
            return when {
                expanded.size == 0 -> NONE
                MeshtasticChannel.isWellKnown(expanded) -> DEFAULT
                expanded.size == 16 || expanded.size == 32 -> PRIVATE
                else -> DEFAULT
            }
        }
    }
}
