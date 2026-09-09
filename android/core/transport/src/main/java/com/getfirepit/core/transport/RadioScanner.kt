package com.getfirepit.core.transport

import com.juul.kable.Advertisement
import com.juul.kable.Scanner
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold

/** A Meshtastic radio seen while scanning. */
data class DiscoveredRadio(
    val identifier: String,
    val name: String?,
    val rssi: Int,
    internal val advertisement: Advertisement,
)

/**
 * Scans for radios advertising the Meshtastic service.
 *
 * Filtering by service UUID is natively supported on every platform, which lets
 * the system optimise the scan and keeps other people's peripherals out of the
 * results entirely.
 */
@OptIn(ExperimentalUuidApi::class)
class RadioScanner {

    private val scanner = Scanner {
        filters {
            match { services = listOf(MeshtasticGatt.SERVICE_UUID) }
        }
    }

    /** Raw stream; the same radio is re-emitted every time it advertises. */
    fun scan(): Flow<DiscoveredRadio> = scanner.advertisements.map { advertisement ->
        DiscoveredRadio(
            identifier = advertisement.identifier.toString(),
            name = advertisement.name ?: advertisement.peripheralName,
            rssi = advertisement.rssi,
            advertisement = advertisement,
        )
    }

    /** Deduplicated, strongest first — what a device picker should show. */
    fun scanDistinct(): Flow<List<DiscoveredRadio>> =
        scan().runningFold(emptyMap<String, DiscoveredRadio>()) { found, radio ->
            found + (radio.identifier to radio)
        }.map { found -> found.values.sortedByDescending(DiscoveredRadio::rssi) }
}
