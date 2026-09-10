package com.getfirepit.core.protocol

/** One step along a traced path. */
data class TraceHop(
    val nodeNum: Int,
    /** Signal into this hop, or null when the responder did not report one. */
    val snr: Float?,
)

/**
 * A traceroute reply, turned into something displayable.
 *
 * The route and SNR lists are parallel but the firmware does not guarantee they
 * are the same length, so hops are paired defensively: a missing SNR is absent
 * rather than invented as zero, which would read as a real measurement.
 */
data class TraceRouteResult(
    val target: Int,
    val towards: List<TraceHop>,
    val back: List<TraceHop>,
) {
    /** True when the target answered directly, with nobody relaying. */
    val isDirect: Boolean get() = towards.isEmpty()

    /** Hops out to the target, counting the target itself. */
    val hopsOut: Int get() = towards.size + 1

    companion object {
        /** The firmware sends SNR in quarter-decibels to keep it an integer. */
        const val SNR_SCALE = 4f

        fun from(
            target: Int,
            route: List<Int>,
            snrTowards: List<Int>,
            routeBack: List<Int>,
            snrBack: List<Int>,
        ) = TraceRouteResult(
            target = target,
            towards = pair(route, snrTowards),
            back = pair(routeBack, snrBack),
        )

        private fun pair(route: List<Int>, snr: List<Int>): List<TraceHop> =
            route.mapIndexed { index, nodeNum ->
                TraceHop(
                    nodeNum = nodeNum,
                    snr = snr.getOrNull(index)
                        ?.takeIf { it != UNKNOWN_SNR }
                        ?.let { it / SNR_SCALE },
                )
            }

        /** What the firmware sends when a hop reported no usable signal reading. */
        private const val UNKNOWN_SNR = -128
    }
}
