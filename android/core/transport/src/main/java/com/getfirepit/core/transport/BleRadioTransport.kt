package com.getfirepit.core.transport

import com.getfirepit.core.protocol.phoneapi.RadioTransport
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * [RadioTransport] over BLE.
 *
 * @param scope must be tied to the connection, so the FromNum observation dies
 * with it rather than leaking across reconnects.
 */
class BleRadioTransport(
    private val peripheral: Peripheral,
    private val scope: CoroutineScope,
) : RadioTransport {

    private val _dataAvailable = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    override val dataAvailable: Flow<Int> = _dataAvailable.asSharedFlow()

    private var observation: Job? = null

    /**
     * Subscribes to FromNum and waits until the subscription is actually live.
     *
     * Kable's `observe` is cold, so simply having a Flow is not enough — the
     * handshake must not start until notifications are genuinely enabled.
     */
    override suspend fun enableNotifications() {
        if (observation != null) return

        val subscribed = CompletableDeferred<Unit>()
        observation = scope.launch {
            peripheral
                .observe(MeshtasticGatt.fromNum) { subscribed.complete(Unit) }
                .collect { _dataAvailable.emit(it.toFromNum()) }
        }
        subscribed.await()
    }

    override suspend fun write(frame: ByteArray) {
        peripheral.write(MeshtasticGatt.toRadio, frame, WriteType.WithResponse)
    }

    override suspend fun read(): ByteArray? =
        peripheral.read(MeshtasticGatt.fromRadio).takeIf { it.isNotEmpty() }
}

/** FromNum is a little-endian uint32 packet counter. */
private fun ByteArray.toFromNum(): Int {
    var value = 0
    for (index in minOf(size, Int.SIZE_BYTES) - 1 downTo 0) {
        value = (value shl 8) or (this[index].toInt() and 0xFF)
    }
    return value
}
