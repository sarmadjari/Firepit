package com.getfirepit.core.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * What the phone's Bluetooth stack knows about our radios.
 *
 * Addresses, matching [DiscoveredRadio.identifier].
 */
data class BluetoothState(
    /** Holding a live GATT connection, to this app or to another. */
    val connected: Set<String> = emptySet(),
    /** Paired with the phone. Android's own settings screen calls these "connected". */
    val paired: Set<String> = emptySet(),
)

/**
 * Which radios the phone is connected or paired to.
 *
 * Deliberately wider than [RadioLink]: several radios can be paired and more
 * than one can hold a connection, but a radio accepts a single PhoneAPI client.
 * Reporting only our own link would tell someone carrying three live radios
 * that two of them were off.
 *
 * Polled, because a GATT connection raises no broadcast an app can subscribe
 * to — `ACTION_ACL_CONNECTED` covers classic Bluetooth, not BLE.
 */
class BluetoothPresence(private val context: Context) {

    fun state(): Flow<BluetoothState> = flow {
        while (true) {
            emit(read())
            delay(POLL)
        }
    }.distinctUntilChanged()

    // Permission is requested by the screen that shows this; refused, the calls
    // throw and the answer is honestly "nothing is connected".
    @SuppressLint("MissingPermission")
    private fun read(): BluetoothState = runCatching {
        val manager = context.getSystemService(BluetoothManager::class.java)
            ?: return BluetoothState()
        BluetoothState(
            connected = manager.getConnectedDevices(BluetoothProfile.GATT)
                .mapTo(mutableSetOf()) { it.address },
            paired = manager.adapter?.bondedDevices.orEmpty().mapTo(mutableSetOf()) { it.address },
        )
    }.getOrDefault(BluetoothState())

    private companion object {
        /** Fast enough to feel immediate, slow enough to be free. */
        val POLL = 2.seconds
    }
}
