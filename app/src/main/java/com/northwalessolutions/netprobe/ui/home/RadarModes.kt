package com.northwalessolutions.netprobe.ui.home

enum class RadarMode { HERO, SCANNING, SETTLED }   // HERO = first launch: no hosts, no completed scan, not scanning
data class RadarSummary(val headline: String, val detail: String)

object RadarModes {
    /** [totalHosts] is unfiltered, so a search or filter with no matches is never a first launch (spec 3.1). */
    fun of(scanning: Boolean, totalHosts: Int, lastScanAt: Long?): RadarMode = when {
        scanning -> RadarMode.SCANNING
        totalHosts == 0 && lastScanAt == null -> RadarMode.HERO
        else -> RadarMode.SETTLED
    }
}

object ScanSummaryText {
    private const val MS_PER_S = 1000L
    private const val HALF_S = 500L
    fun of(totalHosts: Int, otherSubnetHosts: Int, durationMs: Long): RadarSummary {
        val seconds = ((durationMs + HALF_S) / MS_PER_S).coerceAtLeast(1)
        val detail = HomeCopy.scannedIn(seconds) + if (otherSubnetHosts > 0) ", " + HomeCopy.onAnotherSubnet(otherSubnetHosts) else ""
        return RadarSummary(headline(totalHosts), detail)
    }
    fun headline(totalHosts: Int): String = if (totalHosts == 0) HomeCopy.NO_DEVICES else HomeCopy.devices(totalHosts)
}

object RadarSummaries {
    /** The done summary while this view model holds a finished run; otherwise the idle text (duration is not persisted, spec 3.4). */
    fun settled(totalHosts: Int, otherSubnetHosts: Int, lastScanAt: Long?, scanStartedAt: Long?, scanFinishedAt: Long?, now: Long,
                formatDate: (Long) -> String = RelativeTime::defaultDate): RadarSummary {
        if (scanStartedAt != null && scanFinishedAt != null && scanFinishedAt >= scanStartedAt)
            return ScanSummaryText.of(totalHosts, otherSubnetHosts, scanFinishedAt - scanStartedAt)
        val detail = lastScanAt?.let { HomeCopy.lastScan(RelativeTime.of(it, now, formatDate)) } ?: ""
        return RadarSummary(ScanSummaryText.headline(totalHosts), detail)
    }
}

/** Accessibility strings (spec 7.3). */
object RadarText {
    const val RING_DISABLED = "Scan, unavailable, not connected"
    const val STOP_SCAN = "Stop scan"
    const val STOP_QUICK = "Stop quick scan"
    private const val TIME_SEPARATOR = "  "
    private fun lowerFirst(s: String) = s.replaceFirstChar { it.lowercaseChar() }
    fun ringDescription(s: RadarSummary): String = listOf("Scan", s.headline, lowerFirst(s.detail)).filter { it.isNotEmpty() }.joinToString(", ")
    fun scanningDescription(found: Int, progressLine: String?): String {
        val base = "Scanning, ${HomeCopy.devices(found)} found"
        return progressLine?.substringBefore(TIME_SEPARATOR)?.takeIf { it.isNotBlank() }?.let { "$base, $it" } ?: base
    }
    fun heroDescription(subnet: String?): String = subnet?.let { "Scan the network $it" } ?: RING_DISABLED
    fun finished(headline: String): String = "Scan finished, ${lowerFirst(headline)}"
}
