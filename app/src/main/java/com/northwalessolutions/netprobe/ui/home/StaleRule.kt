package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.data.HostEntity

/** Home's dim rule (3A spec 5.5): a row the latest completed run did not reach. With no run (latestRun 0) nothing is dimmed (3C spec 3.4). */
object StaleRule {
    fun of(latestRun: Long, h: HostEntity): Boolean = latestRun > 0 && h.lastDiscoveryRun < latestRun
}
