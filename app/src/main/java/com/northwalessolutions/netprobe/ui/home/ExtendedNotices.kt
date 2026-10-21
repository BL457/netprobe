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
    private const val MAX_RANGE_LINES = 2

    /**
     * [aggressive] false means the run swept manual ranges only (3C spec 2.4): every per-range notice and the bind failure still show,
     * the Aggressive and Smart discovery notes never do.
     */
    fun of(status: DiscoveryStatus, aggressive: Boolean = true): List<String> {
        val out = ArrayList<String>()
        status.notes.filter { aggressive || it == ExtendedNote.SKIPPED_BIND_FAILED }.forEach { n ->
            out += when (n) {
                ExtendedNote.GATEWAY_PROBE_SKIPPED_ANSWERS_ALL -> HomeCopy.GATEWAY_ANSWERS_ALL
                ExtendedNote.GATEWAY_PROBE_FOUND_NONE -> HomeCopy.GATEWAY_FOUND_NONE
                ExtendedNote.NO_EXTRA_RANGES -> HomeCopy.NO_EXTRA_RANGES
                ExtendedNote.SKIPPED_BIND_FAILED -> HomeCopy.BIND_FAILED
                ExtendedNote.GATEWAY_WALK_SKIPPED_MANY -> HomeCopy.GATEWAY_WALK_SKIPPED
            }
        }
        val extras = status.ranges.drop(1)
        // The notice sits in the pinned header: at most MAX_RANGE_LINES per-range lines, the rest rolled up one line per kind.
        val quiet = extras.filter { it.state == RangeState.PHANTOM || (it.state == RangeState.DONE && it.found == 0) }
        quiet.take(MAX_RANGE_LINES).forEach { r -> out += if (r.state == RangeState.PHANTOM) HomeCopy.phantom(r.subnet) else HomeCopy.nothingFound(r.subnet) }
        val rest = quiet.drop(MAX_RANGE_LINES)
        rest.count { it.state == RangeState.PHANTOM }.takeIf { it > 0 }?.let { out += HomeCopy.othersAnsweredAll(it) }
        rest.count { it.state == RangeState.DONE }.takeIf { it > 0 }?.let { out += HomeCopy.othersNothingFound(it) }
        extras.firstOrNull { it.state == RangeState.CANCELLED }?.let { out += HomeCopy.cancelled(it.subnet) }   // the one that was running
        if (status.truncatedRanges > 0) out += HomeCopy.capDropped(status.truncatedRanges)
        return out
    }
}

/** The done summary's range list (extended spec 8.2): a headline count and one line per extra range with source, outcome and found count. */
object RangeSummaryText {
    fun headline(otherRanges: Int): String = HomeCopy.rangesChecked(otherRanges)

    /** The headline counts ranges actually swept; skipped, capped and stopped ranges appear only in the expanded lines. Null when none was swept (M4). */
    fun checked(ranges: List<RangeProgress>): String? = ranges.drop(1).count { it.state == RangeState.DONE }.takeIf { it > 0 }?.let(::headline)

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
            RangeState.CANCELLED, RangeState.RUNNING -> HomeCopy.OUTCOME_STOPPED   // RUNNING never survives the engine's finally
            RangeState.PENDING -> HomeCopy.OUTCOME_NOT_STARTED
        }
        HomeCopy.rangeLine(r.subnet, source, outcome)
    }
}

/** The last run's swept extra ranges, kept in view-model memory only (extended spec 5.6), for display-time grouping. */
object LastRun {
    fun sweptRanges(status: DiscoveryStatus): List<Subnet> =
        status.ranges.drop(1).filter { it.state == RangeState.DONE }.mapNotNull { CidrParser.subnetOf(it.subnet) }
}

/** What one run publishes when it ends, or null when its network is no longer current (a slow unwind must not touch the new network's state). */
data class RunEnd(val swept: List<Subnet>?, val progress: List<RangeProgress>?, val notice: String?) {
    companion object {
        private const val FALLBACK_PREFIX = 24

        /**
         * [completed] is true only for a scan that ran to its end: the large-network notice is not shown after Stop, failure or a network
         * change. [extEnabled] is the run's ExtendedOptions.enabled (Aggressive scan on, or manual ranges set); [aggressive] is the run's
         * snapshot (3C spec 2.1, 2.4).
         */
        fun of(currentNetworkId: Long?, runNetworkId: Long, extEnabled: Boolean, completed: Boolean, status: DiscoveryStatus,
               aggressive: Boolean = extEnabled): RunEnd? {
            if (currentNetworkId != runNetworkId) return null
            val large = status.clampedFrom?.takeIf { completed }?.let { from ->
                if (aggressive) HomeCopy.largeNetworkPartial(from, sweptPrefix(status)) else HomeCopy.largeNetwork(from)
            }
            val notices = listOfNotNull(large) + if (extEnabled) ExtendedNotices.of(status, aggressive) else emptyList()
            return RunEnd(if (extEnabled) LastRun.sweptRanges(status) else null, if (extEnabled) status.ranges else null, notices.takeIf { it.isNotEmpty() }?.joinToString("\n"))
        }

        /** The local range is ranges[0] (for example "172.20.8.0/22"); its prefix is what was swept. */
        private fun sweptPrefix(status: DiscoveryStatus): Int =
            status.ranges.firstOrNull()?.subnet?.substringAfterLast('/')?.toIntOrNull() ?: FALLBACK_PREFIX
    }
}

/** The per-network memory of the last run (finish time, the extended range lists and the run's settings): all of it belongs to one network. */
data class RunMemory(val finishedAt: Long?, val swept: List<Subnet>, val progress: List<RangeProgress>, val settings: RunSettings? = null) {
    fun afterNetworkChange(previousNetworkId: Long?, networkId: Long?): RunMemory =
        if (previousNetworkId == networkId) this
        else RunMemory(ScanFinish.afterNetworkChange(previousNetworkId, networkId, finishedAt), emptyList(), emptyList(), null)
}
