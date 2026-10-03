package com.getfirepit.core.crypto

import com.getfirepit.core.protocol.MeshConstants

/**
 * A sealed payload as it travels: a version, a nonce, then ciphertext and tag.
 *
 * Carried in MeshChatControl on PRIVATE_APP, the way the rest of this protocol
 * travels, so a client that is not Firepit ignores it and the radio's own
 * screen does not display it. The version byte exists so a later format is
 * recognised rather than mis-read.
 *
 * Words and receipts use the same envelope, so a listener cannot tell a
 * conversation from an acknowledgement by the shape of the traffic.
 *
 * Version 2 seals under one sender's key for one hour ([RoomRatchet]). The
 * first two bytes of the nonce say which hour, so the receiver knows which key
 * to derive without a byte more on the air; the other ten are random. Version
 * 1, which sealed under a key that never changed, is no longer read.
 */
object SealedText {

    private const val VERSION: Byte = 0x02
    private const val FIRST_VERSION: Byte = 0x01
    private const val HEADER = 1
    private const val RANDOM_SIZE = RoomCipher.NONCE_SIZE - RoomRatchet.HOUR_TAG_SIZE

    /** What sealing costs against the message budget. */
    const val OVERHEAD = HEADER + RoomCipher.OVERHEAD

    /** What is left for the person typing. */
    const val MAX_TEXT_BYTES = MeshConstants.MAX_TEXT_BYTES - OVERHEAD

    /**
     * Sealed under [key], one sender's key for [hour]. Ten random bytes of
     * nonce under a key nobody else seals with: a repeat is not a risk worth
     * counting.
     */
    fun seal(key: ByteArray, hour: Int, plaintext: ByteArray, context: ByteArray): ByteArray {
        val tag = RoomRatchet.tagOf(hour)
        val nonce = byteArrayOf((tag ushr 8).toByte(), tag.toByte()) + RoomCipher.randomBytes(RANDOM_SIZE)
        return byteArrayOf(VERSION) + RoomCipher.seal(key, plaintext, context, nonce)
    }

    /** Which hour [payload] says it was sealed in, or null when it is not something this build reads. */
    fun hourTagOf(payload: ByteArray): Int? {
        if (payload.size < HEADER + RoomCipher.OVERHEAD || payload[0] != VERSION) return null
        return ((payload[HEADER].toInt() and 0xFF) shl 8) or (payload[HEADER + 1].toInt() and 0xFF)
    }

    /**
     * True for the first format, which builds from before hourly keys still
     * send: it cannot be opened here, but it says who needs to update.
     */
    fun isFirstFormat(payload: ByteArray): Boolean =
        payload.size >= HEADER + RoomCipher.OVERHEAD && payload[0] == FIRST_VERSION

    /** Different for every message ever sealed, so a second arrival of one is a copy. */
    fun nonceOf(payload: ByteArray): ByteArray? =
        if (hourTagOf(payload) == null) null else payload.copyOfRange(HEADER, HEADER + RoomCipher.NONCE_SIZE)

    /**
     * Null when this is not ours to read: a wrong key, a changed byte, or a
     * version this build does not know. The caller says a message arrived and
     * could not be opened, rather than showing rubbish as though it were words.
     */
    fun open(key: ByteArray, payload: ByteArray, context: ByteArray): ByteArray? {
        if (hourTagOf(payload) == null) return null
        return RoomCipher.open(key, payload.copyOfRange(HEADER, payload.size), context)
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
