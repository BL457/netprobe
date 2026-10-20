package com.northwalessolutions.netprobe.engine.discovery

import com.northwalessolutions.netprobe.engine.common.Probe

/**
 * "Connect or refused means alive" is safe on-link but not through a router: a REJECT rule or a captive portal answers for every
 * address (extended spec 4.7). Layer 2 is the per-range canary, layer 3 the sweep guard; layer 1 is the TEST-NET pre-check.
 */
object PhantomGuard {
    const val SAMPLE = 64
    const val MIN_PROBES = 16
    const val ALIVE_RATIO = 0.9
    const val CANARY_MIN_UNKNOWN = 4

    enum class Verdict { HOLD, RELEASE, PHANTOM }

    /** Hold hosts until 64 probes (or the end of a smaller range); then more than 90 percent alive is a phantom range. */
    fun decide(probed: Int, alive: Int, finished: Boolean): Verdict {
        if (!finished && probed < SAMPLE) return Verdict.HOLD
        if (probed < MIN_PROBES) return Verdict.RELEASE
        return if (alive > ALIVE_RATIO * probed) Verdict.PHANTOM else Verdict.RELEASE
    }

    /** Both never-seen canary addresses answering on a random high port means the range answers for everything. */
    fun canaryPhantom(outcomes: List<Probe.Outcome>): Boolean = outcomes.isNotEmpty() && outcomes.all(AnswersForEverything::answered)
}
