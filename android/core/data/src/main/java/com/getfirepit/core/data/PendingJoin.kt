package com.getfirepit.core.data

import com.getfirepit.core.protocol.KeyFingerprint
import okio.ByteString

/**
 * Somebody who has shown a valid code and is waiting to be let in.
 *
 * The keys do not move until a person answers this, which is what makes a
 * photographed code a request rather than a way in.
 */
data class PendingJoin(
    val nodeNum: Int,
    val roomId: Int,
    val inviteId: Int,
    val generation: Int,
    /**
     * Their radio's key: the one the firmware decrypted their hello with, and
     * so the one the grant's outer layer goes to.
     */
    val joinerKey: ByteString,
    /** Their phone's key, which the room's own key is sealed to. Came inside their hello. */
    val phoneKey: ByteString,
    val askedAt: Long,
) {
    /** The only identity the mesh attests. Anyone can claim any name. */
    val nodeId: String get() = "!%08x".format(nodeNum)

    /**
     * Their radio's key and their phone's key as one line, short enough to
     * read aloud. Their own screen shows the same line; if the two differ, the
     * hello is not from the phone in front of you — and it is that phone the
     * room's key will be sealed to.
     */
    val fingerprint: String? get() = KeyFingerprint.ofJoin(joinerKey.toByteArray(), phoneKey.toByteArray())
}

/** A room we have asked to join, while the answer is still outstanding. */
data class AwaitedRoom(
    val roomId: Int,
    val roomName: String,
    val inviteId: Int,
    /**
     * Who we asked. The room id and invite id are both in the code, so anyone
     * who photographed it can name them; only the inviter can answer as the
     * node whose key we seeded from that same code.
     */
    val inviter: Int,
    /** Our own radio's and phone's keys as one line, for reading aloud so the inviter can check them. */
    val ownFingerprint: String? = null,
    /** Set when the answer came back and it was no. */
    val declined: Boolean = false,
)
