package com.getfirepit.core.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The one local "you" fix Firepit may show or seal. Pure port of iOS `OwnPosition`.
 *
 * The phone wins: faster, more accurate, and it spares the radio's smaller
 * battery. The radio's own GPS stands in when the phone has nothing, or has
 * gone quiet while the radio kept fixing. A phone standing still reports
 * less often (iOS pauses its updates), so its fix stays good for a while.
 */
object OwnPosition {
    const val PHONE_FRESH_MILLIS = 10 * 60 * 1000L
    const val RADIO_FRESH_MILLIS = 10 * 60 * 1000L

    /** How much newer the radio's fix must be before it is shown instead of the phone's. */
    const val PHONE_QUIET_MILLIS = 2 * 60 * 1000L

    /** A fix stamped this far ahead of our clock still counts: phone, radio and GPS clocks differ. */
    const val CLOCK_SKEW_MILLIS = 5 * 60 * 1000L

    enum class Source { PHONE, RADIO }

    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val altitude: Int?,
        val timeMillis: Long,
        val source: Source,
    ) {
        val latitudeI: Int get() = (latitude * 1e7).toInt()
        val longitudeI: Int get() = (longitude * 1e7).toInt()
    }

    fun choose(phone: Fix?, radio: Fix?, nowMillis: Long, locationAllowed: Boolean): Fix? {
        if (!locationAllowed) return null
        val fromPhone = phone?.takeIf { fresh(it, nowMillis, PHONE_FRESH_MILLIS) }
        val fromRadio = radio?.takeIf { fresh(it, nowMillis, RADIO_FRESH_MILLIS) }
        return when {
            fromPhone == null -> fromRadio
            fromRadio == null -> fromPhone
            fromRadio.timeMillis - fromPhone.timeMillis > PHONE_QUIET_MILLIS -> fromRadio
            else -> fromPhone
        }
    }

    private fun fresh(fix: Fix, nowMillis: Long, limit: Long): Boolean =
        nowMillis - fix.timeMillis in -CLOCK_SKEW_MILLIS..limit

    fun distanceMetres(a: Fix, b: Fix): Double {
        val earth = 6_371_000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * earth * asin(sqrt(h))
    }
}
