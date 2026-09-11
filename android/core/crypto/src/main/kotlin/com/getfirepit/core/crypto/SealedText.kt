package com.getfirepit.core.crypto

import com.getfirepit.core.protocol.MeshConstants

/**
 * A room message as it travels: a version, then ciphertext.
 *
 * Carried in MeshChatControl on PRIVATE_APP, the way the rest of this protocol
 * travels, so a client that is not Firepit ignores it and the radio's own
 * screen does not display it. The version byte exists so a later format is
 * recognised rather than mis-read.
 */
object SealedText {

    private const val VERSION: Byte = 0x01
    private const val HEADER = 1

    /** What sealing costs against the message budget. */
    const val OVERHEAD = HEADER + RoomCipher.OVERHEAD

    /** What is left for the person typing. */
    const val MAX_TEXT_BYTES = MeshConstants.MAX_TEXT_BYTES - OVERHEAD

    fun seal(key: ByteArray, text: String, context: ByteArray): ByteArray =
        byteArrayOf(VERSION) + RoomCipher.seal(key, text.toByteArray(Charsets.UTF_8), context)

    /**
     * Null when this is not ours to read: a wrong key, a changed byte, or a
     * version this build does not know. The caller says a message arrived and
     * could not be opened, rather than showing rubbish as though it were words.
     */
    fun open(key: ByteArray, payload: ByteArray, context: ByteArray): String? {
        if (payload.size <= HEADER || payload[0] != VERSION) return null
        return RoomCipher.open(key, payload.copyOfRange(HEADER, payload.size), context)
            ?.toString(Charsets.UTF_8)
    }

    /**
     * Binds a message to where and from whom it was sent, so one cannot be
     * lifted into another room or re-attributed to someone else.
     */
    fun contextOf(roomId: Int, senderNodeNum: Int): ByteArray =
        intBytes(roomId) + intBytes(senderNodeNum)

    private fun intBytes(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
