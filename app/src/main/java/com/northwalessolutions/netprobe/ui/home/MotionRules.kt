package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.ui.theme.Motion

/** A ring pulse starts when none has started this scan, or the last one started at least PULSE_MIN_GAP_MS ago by the frame clock (spec 1.5, Gruff M2). */
object PulseGate {
    fun shouldStart(nowMs: Long, lastStartMs: Long?): Boolean = lastStartMs == null || nowMs - lastStartMs >= Motion.PULSE_MIN_GAP_MS
}

/** The k-th new row of one list emission, in list order, waits min(k * ROW_STAGGER_MS, ROW_STAGGER_MAX_MS) (spec 1.8). */
object RowStagger {
    fun delays(newIds: List<Long>): Map<Long, Int> =
        newIds.withIndex().associate { (k, id) -> id to minOf(k * Motion.ROW_STAGGER_MS, Motion.ROW_STAGGER_MAX_MS) }
}
