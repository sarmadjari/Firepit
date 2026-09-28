package com.getfirepit.core.model

/**
 * How somebody in one of your rooms describes themselves.
 *
 * Distinct from the node's own name: the mesh addresses radios, and this is the
 * person holding one. Claimed rather than proven — only [nodeNum] is attested —
 * so it is shown beside the node, never as a substitute for it.
 */
data class PersonCard(
    val nodeNum: Int,
    val name: String,
    val tag: String,
    /** Null leaves the colour derived from [nodeNum], as it is for everyone else. */
    val colourSlot: Int?,
    val updatedAt: Long,
)
