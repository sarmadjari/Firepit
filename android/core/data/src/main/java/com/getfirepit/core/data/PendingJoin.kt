package com.getfirepit.core.data

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
    /** Where the grant will be encrypted to. Came inside their sealed hello. */
    val joinerKey: ByteString,
    val askedAt: Long,
) {
    /** The only identity the mesh attests. Anyone can claim any name. */
    val nodeId: String get() = "!%08x".format(nodeNum)
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
    /** Set when the answer came back and it was no. */
    val declined: Boolean = false,
)
