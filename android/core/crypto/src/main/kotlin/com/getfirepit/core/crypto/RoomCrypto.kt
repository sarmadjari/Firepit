package com.getfirepit.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Room keys and the rotating QR token.
 *
 * Uses the JDK's own primitives: HMAC-SHA256 and a seeded-by-the-OS CSPRNG are
 * all this stage needs. libsodium arrives with the link+PIN invite, which needs
 * Argon2id and has no JDK equivalent.
 */
object RoomCrypto {

    const val PSK_SIZE = 32
    const val TOKEN_SIZE = 8

    /** How long one QR code stays valid before it is redrawn. */
    const val ROTATION_SECONDS = 8L

    /**
     * Windows either side of the scanner's own that are still accepted, to
     * absorb clock skew between two phones. Two windows is ~16 s.
     */
    const val WINDOW_TOLERANCE = 2

    /**
     * How far back the inviter will recognise one of its own tokens.
     *
     * Deliberately short: the joiner replies the moment it scans, because the
     * inviter's public key is in the code and nothing has to propagate first.
     * Every window past that is time a photographed code stays usable.
     */
    const val DEFAULT_LOOKBACK_WINDOWS = 4

    private const val INVITE_CONTEXT = "meshchat-invite-v1"
    private const val HMAC = "HmacSHA256"

    private val random = SecureRandom()

    /**
     * A room's pre-shared key: 32 bytes for AES-256.
     *
     * Never derived from a name or anything guessable, and never empty — an
     * empty PSK makes the firmware silently fall back to the primary key.
     */
    fun generatePsk(): ByteArray = ByteArray(PSK_SIZE).also(random::nextBytes)

    /** Non-zero identifier that survives renames, re-indexing and key rotation. */
    fun generateRoomId(): Int {
        var id = 0
        while (id == 0) id = random.nextInt()
        return id
    }

    /**
     * Derived from the room key, so every current key-holder can issue invites
     * without any of them holding extra authority.
     */
    fun inviteKey(roomPsk: ByteArray, roomId: Int, generation: Int): ByteArray {
        require(roomPsk.size == PSK_SIZE) { "room PSK must be $PSK_SIZE bytes, was ${roomPsk.size}" }
        return hmac(roomPsk, INVITE_CONTEXT.toByteArray() + roomId.toBytes() + generation.toBytes())
    }

    /** Which rotation window a moment falls in. */
    fun windowFor(epochMillis: Long): Int = (epochMillis / 1000L / ROTATION_SECONDS).toInt()

    /**
     * Binds an invite to one inviter and one moment. It narrows the window in
     * which a shoulder-surfed code is usable; it does not revoke the room key
     * afterwards, which nothing can.
     */
    fun token(inviteKey: ByteArray, inviterNodeNum: Int, window: Int): ByteArray =
        hmac(inviteKey, inviterNodeNum.toBytes() + window.toBytes()).copyOf(TOKEN_SIZE)

    /**
     * True when [token] was minted by us within the last [lookbackWindows].
     *
     * A join hello echoes the token but not the window it came from, so the
     * inviter has to search. Only the inviter can do this at all: the token is
     * an HMAC under the room's key, and a scanner holds no key until it is let
     * in.
     */
    fun matchesRecentToken(
        inviteKey: ByteArray,
        inviterNodeNum: Int,
        token: ByteArray,
        nowMillis: Long,
        lookbackWindows: Int = DEFAULT_LOOKBACK_WINDOWS,
    ): Boolean {
        if (token.size != TOKEN_SIZE) return false
        val current = windowFor(nowMillis)
        // Always walk the whole range so timing does not reveal which window hit.
        return (current - lookbackWindows..current + WINDOW_TOLERANCE)
            .count { MessageDigest.isEqual(token, token(inviteKey, inviterNodeNum, it)) } > 0
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance(HMAC).apply { init(SecretKeySpec(key, HMAC)) }.doFinal(message)

    private fun Int.toBytes(): ByteArray = byteArrayOf(
        (this ushr 24).toByte(),
        (this ushr 16).toByte(),
        (this ushr 8).toByte(),
        this.toByte(),
    )
}
