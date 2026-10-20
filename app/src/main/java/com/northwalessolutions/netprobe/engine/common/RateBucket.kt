package com.northwalessolutions.netprobe.engine.common

import kotlin.math.ceil
import kotlin.math.min

/**
 * A per-host token bucket (spec 5.3): [ratePerSecond] refill, [burst] capacity. [acquireDelayMs] consumes a token and returns 0 when
 * one is free, else the whole milliseconds until the next token; the caller delays and retries. [reset] empties it, so the bucket
 * refills from empty after a background pause. A constant, never a user option. The default clock is monotonic (nanoTime).
 */
class RateBucket(val ratePerSecond: Int = MAX_REQUESTS_PER_SECOND, val burst: Int = BURST, private val now: () -> Long = { System.nanoTime() / NANOS_PER_MS }) {
    private var tokens = burst.toDouble()
    private var last = now()
    /** (t - last) is clamped at 0, so a clock that steps backwards never removes tokens. */
    private fun refill() { val t = now(); tokens = min(burst.toDouble(), tokens + (t - last).coerceAtLeast(0L) * ratePerSecond / 1000.0); last = t }
    @Synchronized fun acquireDelayMs(): Long {
        refill()
        if (tokens >= 1.0) { tokens -= 1.0; return 0L }
        return ceil((1.0 - tokens) / ratePerSecond * 1000.0).toLong().coerceAtLeast(1L)
    }
    @Synchronized fun reset() { tokens = 0.0; last = now() }
    companion object { const val MAX_REQUESTS_PER_SECOND = 100; const val BURST = 10; private const val NANOS_PER_MS = 1_000_000L }
}
