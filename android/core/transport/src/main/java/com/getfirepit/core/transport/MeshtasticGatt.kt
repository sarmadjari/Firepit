package com.getfirepit.core.transport

import com.juul.kable.characteristicOf
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The radio's GATT profile, from the firmware's `BluetoothCommon.h`. The same
 * values appear in the official Android and Apple clients.
 */
@OptIn(ExperimentalUuidApi::class)
object MeshtasticGatt {
    val SERVICE_UUID: Uuid = Uuid.parse("6ba1b218-15a8-461f-9fa8-5dcae273eafd")

    private val TO_RADIO_UUID = Uuid.parse("f75c76d2-129e-4dad-a1dd-7866124401e7")
    private val FROM_RADIO_UUID = Uuid.parse("2c55e69e-4993-11ed-b878-0242ac120002")
    private val FROM_NUM_UUID = Uuid.parse("ed9da18c-a800-4f66-a670-aa7547e34453")

    /** Write one encoded `ToRadio`, with response. */
    val toRadio = characteristicOf(SERVICE_UUID, TO_RADIO_UUID)

    /** Read one queued `FromRadio`; an empty read means the queue is drained. */
    val fromRadio = characteristicOf(SERVICE_UUID, FROM_RADIO_UUID)

    /** Notifies with a packet counter whenever data is waiting. */
    val fromNum = characteristicOf(SERVICE_UUID, FROM_NUM_UUID)

    /**
     * The radio negotiates upward from the 23-byte default; nRF52 hardware
     * settles around 247. Requested so config download is not split into
     * dozens of tiny reads.
     */
    const val PREFERRED_MTU: Int = 512
}
