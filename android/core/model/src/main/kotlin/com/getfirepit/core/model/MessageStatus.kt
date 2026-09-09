package com.getfirepit.core.model

/**
 * How far an outgoing message actually got.
 *
 * A LoRa mesh cannot prove a person read anything, and for a room broadcast it
 * cannot even prove anyone received it. Each state maps to something the radio
 * genuinely reported — see `meshchat-ux-design.md` §7.2 for the user-facing
 * wording.
 *
 * Declaration order is the progression order: status only ever moves forward,
 * so a late packet cannot downgrade a confirmed delivery.
 */
enum class MessageStatus {
    /** In the app's outbound queue, not yet written to the radio. */
    QUEUED,

    /** The radio accepted it for transmission. */
    SENT_TO_NODE,

    /** No routing packet arrived in time. Displayed the same as [SENT_TO_NODE]. */
    UNKNOWN,

    /** Retransmits exhausted and nobody repeated it. */
    UNHEARD,

    FAILED,

    /**
     * We heard our own packet rebroadcast, so it entered the mesh. This is the
     * final, honest state for a room message: it never means anyone received it.
     */
    REACHED_MESH,

    /** The destination node acknowledged. Direct messages only. */
    DELIVERED,

    /**
     * Somebody else's message that we received. Outside the progression above:
     * a delivery status describes something we sent, and there is nothing to
     * confirm about a message already in our hands.
     */
    RECEIVED,
    ;

    val isFailure: Boolean get() = this == FAILED || this == UNHEARD
}
