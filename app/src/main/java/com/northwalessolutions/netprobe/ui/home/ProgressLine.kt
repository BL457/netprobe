package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.discovery.DiscoveryStatus

/** The radar's progress line (spec 3.3 and section 8). Task 16 adds the extended-discovery phases. */
object ProgressLine {
    fun of(status: DiscoveryStatus?, elapsedS: Long): String {
        if (status == null || status.total == 0) return HomeCopy.STARTING
        return if (status.probed < status.total) HomeCopy.probing(status.probed, status.total, elapsedS)
        else HomeCopy.identifying(status.found, elapsedS)
    }
}
