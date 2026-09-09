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
}
