package com.getfirepit.core.crypto

import kotlin.math.abs

/**
 * Moves a room's key on once an hour, one way.
 *
 * A key that never changes opens everything ever recorded under it, so a phone
 * taken next month would read last month off any recording of the air. Here
 * each hour's key is derived from the hour before, and the phone keeps only
 * the hour just gone (for packets the mesh delivers late). The step cannot be
 * run backwards, so what was said before that is out of reach of anyone who
 * takes the phone, the key store, or a backup of either.
 *
 * Every member derives the same keys from the same clock, so none of this
 * costs a byte or a packet: the nonce of each sealed message already carries
 * which hour sealed it (see [SealedText]).
 *
 * Within an hour each sender seals under a key of their own, derived from the
 * hour's key and their node number, so no two members ever share a key and
 * nonce, however busy the room.
 *
 * HKDF-SHA256 expand steps (RFC 5869 §2.3), keyed by keys that are already
 * uniformly random, so no extract step is needed.
 */
object RoomRatchet {

    const val HOUR_MILLIS = 3_600_000L

    /** How many bytes of the hour a sealed message carries in its nonce. */
    const val HOUR_TAG_SIZE = 2

    /**
     * The hour a key stored before the ratchet existed is taken to belong to:
     * 2026-01-01T00:00Z. Fixed, so phones that update at different times still
     * agree on every later key without saying a word to each other.
     */
    const val LEGACY_HOUR = 490_896

    /** A clock decades out is a broken clock, not a reason to spin. */
    private const val MAX_STEPS = 1_000_000

    private const val TAG_SPAN = 1 shl (HOUR_TAG_SIZE * 8)
    private const val HOUR_INFO = "firepit-hour-v1"
    private const val SENDER_INFO = "firepit-sender-v1"

    fun hourOf(unixMillis: Long): Int = Math.floorDiv(unixMillis, HOUR_MILLIS).toInt()

    /** What a sealed message carries of [hour]: it repeats every 7½ years. */
    fun tagOf(hour: Int): Int = hour and (TAG_SPAN - 1)

    /** The hour carrying [tag] that lies nearest [near]. */
    fun hourNear(tag: Int, near: Int): Int {
        val base = near - tagOf(near) + (tag and (TAG_SPAN - 1))
        return listOf(base - TAG_SPAN, base, base + TAG_SPAN).minBy { abs(it - near) }
    }

    /** The key for [hour] + 1, from the key for [hour]. There is no way back. */
    fun next(key: ByteArray, roomId: Int, generation: Int, hour: Int): ByteArray =
        expand(key, label(HOUR_INFO, roomId, generation, hour + 1))

    /**
     * The key for [to], from the key for [from]. Null when [to] is earlier,
     * which is the whole point, or absurdly far ahead.
     */
    fun forward(key: ByteArray, roomId: Int, generation: Int, from: Int, to: Int): ByteArray? {
        if (to < from || to.toLong() - from > MAX_STEPS) return null
        var current = key.copyOf()
        for (hour in from until to) {
            val next = next(current, roomId, generation, hour)
            current.fill(0)
            current = next
        }
        return current
    }

    /** One sender's key for one hour. */
    fun senderKey(hourKey: ByteArray, roomId: Int, generation: Int, hour: Int, sender: Int): ByteArray =
        expand(hourKey, label(SENDER_INFO, roomId, generation, hour) + intBytes(sender))

    /**
     * The hour this phone takes to be now. Never earlier than the hour it
     * holds a key for: a clock set back must not make it seal under an hour
     * whose key it has already destroyed.
     */
    fun currentHour(heldHour: Int, nowHour: Int): Int = maxOf(heldHour, nowHour)

    /**
     * Whether a message sealed in [hour] may open here: the hour just gone,
     * for packets the mesh delivered late, this one, and the next, for a
     * sender whose clock runs a little ahead. Nothing older, which is what
     * makes taking the phone worthless against what was said before.
     */
    fun opens(heldHour: Int, nowHour: Int, hour: Int): Boolean {
        val current = currentHour(heldHour, nowHour)
        return hour >= maxOf(heldHour, current - 1) && hour <= current + 1
    }

    /** The oldest hour worth keeping a key for: the one just gone. */
    fun keepFrom(heldHour: Int, nowHour: Int): Int = maxOf(heldHour, nowHour - 1)

    private fun expand(key: ByteArray, info: ByteArray): ByteArray {
        require(key.size == RoomCipher.KEY_SIZE) { "A room key is ${RoomCipher.KEY_SIZE} bytes, not ${key.size}" }
        return KeyEnvelope.hmac(key, info + byteArrayOf(1))
    }

    private fun label(info: String, roomId: Int, generation: Int, hour: Int): ByteArray =
        info.encodeToByteArray() + intBytes(roomId) + intBytes(generation) + intBytes(hour)

    private fun intBytes(value: Int) = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}

/** One hour's key, and which hour it is: what a grant or a rotation hands over. */
class HourKey(val hour: Int, val key: ByteArray) {
    init {
        require(key.size == RoomCipher.KEY_SIZE) { "A room key is ${RoomCipher.KEY_SIZE} bytes, not ${key.size}" }
    }

    /** A copy whose bytes can be wiped without touching this one's. */
    fun copy(): HourKey = HourKey(hour, key.copyOf())
}
