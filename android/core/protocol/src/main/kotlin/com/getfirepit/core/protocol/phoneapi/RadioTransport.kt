package com.getfirepit.core.protocol.phoneapi

import kotlinx.coroutines.flow.Flow

/**
 * A connected link carrying the ToRadio/FromRadio protobuf stream.
 *
 * Shaped after the BLE PhoneAPI, which is the primary transport: writes go to
 * the ToRadio characteristic, and reads pull one queued message at a time from
 * FromRadio until it comes back empty. TCP and serial can satisfy the same
 * contract by framing their stream.
 *
 * Implementations are handed to [PhoneApiSession] already connected; they do
 * not own reconnection.
 */
interface RadioTransport {

    /**
     * Emits the radio's FromNum counter whenever new data is waiting.
     *
     * Only fires once the radio reaches its packet-sending state, which is why
     * the config download in [PhoneApiSession] polls instead of waiting here.
     */
    val dataAvailable: Flow<Int>

    /**
     * Subscribe to FromNum. Must happen before the handshake — the firmware
     * gates these notifications behind STATE_SEND_PACKETS, and subscribing
     * afterwards can miss the transition.
     */
    suspend fun enableNotifications()

    /** Write one encoded `ToRadio`. */
    suspend fun write(frame: ByteArray)

    /** One encoded `FromRadio`, or null once the radio's queue is drained. */
    suspend fun read(): ByteArray?
}
