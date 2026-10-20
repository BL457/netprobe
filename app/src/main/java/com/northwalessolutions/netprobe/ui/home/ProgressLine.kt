package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.discovery.DiscoveryPhase
import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus
import com.northwalessolutions.netprobe.engine.discovery.RangeState

/** The radar's progress line (Radar spec 3.3 and section 8). "2 of 4" counts extra ranges only, never the local sweep. */
object ProgressLine {
    private val CIDR_LABEL = Regex("""\d+\.\d+\.\d+\.\d+/\d+""")

    fun of(status: DiscoveryStatus?, elapsedS: Long): String {
        if (status == null) return HomeCopy.STARTING
        when (status.phase) {
            DiscoveryPhase.GATEWAY_PROBE -> {
                val p = status.gatewayProbe ?: return HomeCopy.preparing(elapsedS)
                // Only a real CIDR is shown; any other engine label (for example "other ranges") is replaced by the unnamed template.
                return if (CIDR_LABEL.matches(p.label)) HomeCopy.gatewayProbing(p.label, p.done, p.total, elapsedS)
                else HomeCopy.gatewayProbingUnnamed(p.done, p.total, elapsedS)
            }
            DiscoveryPhase.EXTENDED -> {
                val extras = status.ranges.drop(1).filter { it.state != RangeState.TRUNCATED }
                val i = extras.indexOfFirst { it.state == RangeState.RUNNING }
                return if (i < 0) HomeCopy.preparing(elapsedS)
                else extras[i].let { HomeCopy.extendedRange(it.subnet, i + 1, extras.size, it.probed, it.total) }
            }
            DiscoveryPhase.LOCAL, DiscoveryPhase.DONE -> Unit
        }
        if (status.total == 0) return HomeCopy.STARTING
        return if (status.probed < status.total) HomeCopy.probing(status.probed, status.total, elapsedS)
        else HomeCopy.identifying(status.found, elapsedS)
    }
}
