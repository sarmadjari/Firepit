package com.getfirepit.core.model

/**
 * How far a message got with one person.
 *
 * Only ever what someone told us. Silence is not a third state: a member out of
 * range, asleep or with a flat battery looks the same as one ignoring you, so
 * nothing here is ever rendered as "unread by".
 */
enum class ReceiptState {
    /** Their phone has it, and has not shown it to them. */
    RECEIVED,

    /** They had the conversation open. */
    READ,
}

/** One person's progress with one message. */
data class Receipt(
    val nodeNum: Int,
    val state: ReceiptState,
    val at: Long,
)
