package com.getfirepit.core.protocol

/**
 * Wire-contract constants. Values are asserted against the vendored protobufs
 * in ProtocolContractTest, so they cannot silently drift from the firmware.
 */
object MeshConstants {
    const val BROADCAST_NODENUM: Int = -1 // 0xFFFFFFFF as a signed uint32

    /** Firmware default when `lora.hop_limit` is 0. Never raise it: it multiplies traffic mesh-wide. */
    const val DEFAULT_HOP_LIMIT: Int = 3
    const val MAX_HOP_LIMIT: Int = 7

    /** Firepit's private application port. Event-driven only, never periodic. */
    const val MESHCHAT_CONTROL_PORT: Int = 300

    /**
     * Sealed room text. Private range, so nothing else on the mesh reads it.
     *
     * A separate port rather than a marker inside TEXT_MESSAGE_APP: other
     * clients then show nothing at all instead of a line of rubbish, and the
     * radio's own screen does not display what the phone has kept private.
     */
    const val MESHCHAT_TEXT_PORT: Int = 301

    /** `Constants.DATA_PAYLOAD_LEN` — the room available to `Data.payload`. */
    const val DATA_PAYLOAD_LEN: Int = 233

    /** PKI direct messages cost 12 bytes of tag and extra nonce. */
    const val PKC_OVERHEAD: Int = 12

    /** A 2.8 XEdDSA signature costs 64 bytes plus 2 of field encoding. */
    const val XEDDSA_SIGNATURE_OVERHEAD: Int = 66

    /** Composer cap, matching the Apple client so messages render identically everywhere. */
    const val MAX_TEXT_BYTES: Int = 200

    /** Past this, a 2.8 node sends the broadcast unsigned because the signature no longer fits. */
    const val SIGNED_BROADCAST_TEXT_BUDGET: Int = 165

    /** A single ToRadio/FromRadio message never exceeds this. */
    const val MAX_TO_FROM_RADIO_SIZE: Int = 512

    fun formatNodeId(nodeNum: Int): String = "!%08x".format(nodeNum)

    /**
     * Trims [text] to at most [maxBytes] of UTF-8 without splitting a character.
     *
     * The limit is in bytes but people type characters, and Arabic, emoji and
     * accented Latin all cost more than one byte each. Cutting at a byte index
     * would produce mojibake or a lone surrogate.
     */
    fun truncateToBytes(text: String, maxBytes: Int = MAX_TEXT_BYTES): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text

        var bytes = 0
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            // Surrogate pairs are one character over two chars, so step by the
            // code point rather than the UTF-16 unit.
            val codePoint = text.codePointAt(index)
            val width = Character.charCount(codePoint)
            val chunk = text.substring(index, index + width)
            val chunkBytes = chunk.toByteArray(Charsets.UTF_8).size
            if (bytes + chunkBytes > maxBytes) break
            builder.append(chunk)
            bytes += chunkBytes
            index += width
        }
        return builder.toString()
    }
}
