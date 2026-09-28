package com.getfirepit.core.protocol

/**
 * Reading timestamps that came off a radio.
 *
 * A Meshtastic node keeps its own clock, and one that has never been told the
 * time sits at zero or drifts years out. Nothing should be shown to a reader on
 * a radio's word alone: the phone's clock is the one the person can check
 * against their own day, so every stamp is put in those terms first.
 */
object RadioClock {

    /** Older than the app keeps anything, so such a stamp is the clock talking. */
    const val CLAIM_STALE_MS = 30L * 24 * 60 * 60 * 1_000

    /** Room for a little drift forward; nobody transmits from the future. */
    const val CLAIM_AHEAD_MS = 5L * 60 * 1_000

    /**
     * A stamp from the radio in our own hand, read in the phone's terms.
     *
     * It hands a packet over as soon as it has it, so whatever its clock is out
     * by is a constant that can be measured once and taken off everything
     * after. Never reads later than [now]: the radio cannot have heard
     * something after we asked.
     */
    fun onPhoneClock(radioSeconds: Int, skewMillis: Long?, now: Long): Long? {
        if (radioSeconds == 0) return null
        return (radioSeconds.toLong() * 1_000 - (skewMillis ?: 0L)).coerceAtMost(now)
    }

    /**
     * A stamp another node put on its own fix, believed only while it could be
     * true.
     *
     * Their clock is theirs, and nothing here can measure it the way we measure
     * the radio we are holding. A claim from next month, or from before any of
     * this existed, says more about their clock than their position, so it is
     * refused rather than drawn as a fact.
     */
    fun ifPlausible(claimSeconds: Int, now: Long): Long? {
        if (claimSeconds == 0) return null
        return (claimSeconds.toLong() * 1_000)
            .takeIf { it in (now - CLAIM_STALE_MS)..(now + CLAIM_AHEAD_MS) }
    }
}
