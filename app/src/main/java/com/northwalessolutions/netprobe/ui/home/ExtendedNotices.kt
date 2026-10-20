package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.engine.discovery.ExtendedNote
import com.northwalessolutions.netprobe.engine.discovery.RangeProgress
import com.northwalessolutions.netprobe.engine.discovery.RangeSource
import com.northwalessolutions.netprobe.engine.discovery.RangeState
import com.northwalessolutions.netprobe.netenv.CidrParser
import com.northwalessolutions.netprobe.netenv.Subnet

/** End-of-run notices for the notice line (Radar spec 4.4 and section 8). Ranges with hosts need no notice: their group says it. */
object ExtendedNotices {
    fun of(status: DiscoveryStatus): List<String> {
        val out = ArrayList<String>()
        status.notes.forEach { n ->
            out += when (n) {
                ExtendedNote.GATEWAY_PROBE_SKIPPED_ANSWERS_ALL -> HomeCopy.GATEWAY_ANSWERS_ALL
                ExtendedNote.GATEWAY_PROBE_FOUND_NONE -> HomeCopy.GATEWAY_FOUND_NONE
                ExtendedNote.NO_EXTRA_RANGES -> HomeCopy.NO_EXTRA_RANGES
                ExtendedNote.SKIPPED_BIND_FAILED -> HomeCopy.BIND_FAILED
            }
        }
        val extras = status.ranges.drop(1)
        extras.forEach { r ->
            when {
                r.state == RangeState.PHANTOM -> out += HomeCopy.phantom(r.subnet)
                r.state == RangeState.DONE && r.found == 0 -> out += HomeCopy.nothingFound(r.subnet)
            }
        }
        extras.firstOrNull { it.state == RangeState.CANCELLED }?.let { out += HomeCopy.cancelled(it.subnet) }   // the one that was running
        if (status.truncatedRanges > 0) out += HomeCopy.capDropped(status.truncatedRanges)
        return out
    }
}

/** The done summary's range list (extended spec 8.2): a headline count and one line per extra range with source, outcome and found count. */
object RangeSummaryText {
    fun headline(otherRanges: Int): String = HomeCopy.rangesChecked(otherRanges)

    /** [ranges] is the status list with the local range first; the local range is not listed. */
    fun lines(ranges: List<RangeProgress>): List<String> = ranges.drop(1).map { r ->
        val source = when (r.source) {
            RangeSource.MANUAL -> HomeCopy.SOURCE_MANUAL
            RangeSource.EVIDENCE -> HomeCopy.SOURCE_EVIDENCE
            RangeSource.GATEWAY_PROBE -> HomeCopy.SOURCE_GATEWAY
            null -> HomeCopy.SOURCE_LOCAL
        }
        val outcome = when (r.state) {
            RangeState.DONE -> HomeCopy.outcomeSwept(r.found)
            RangeState.PHANTOM -> HomeCopy.OUTCOME_PHANTOM
            RangeState.TRUNCATED -> HomeCopy.OUTCOME_CAPPED
            RangeState.CANCELLED -> HomeCopy.OUTCOME_STOPPED
            RangeState.PENDING, RangeState.RUNNING -> HomeCopy.OUTCOME_NOT_SWEPT
        }
        HomeCopy.rangeLine(r.subnet, source, outcome)
    }
}

/** The last run's swept extra ranges, kept in view-model memory only (extended spec 5.6), for display-time grouping. */
object LastRun {
    fun sweptRanges(status: DiscoveryStatus): List<Subnet> =
        status.ranges.drop(1).filter { it.state == RangeState.DONE }.mapNotNull { CidrParser.subnetOf(it.subnet) }
}
