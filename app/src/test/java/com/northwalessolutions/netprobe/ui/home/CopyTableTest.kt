package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.engine.discovery.*
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Radar spec section 8 copy table held as regex templates. Each test generates a matrix of reachable states and asserts every
 * produced string matches a template, so a state that yields wording outside the table fails here. Task 16 appends its own templates.
 */
class CopyTableTest {
    private val date = "3 Jan 2026"
    private val templates: List<Regex> = listOf(
        "Scan", "Stop", "found", "No devices found", "\\d+ devices?",
        "Last scan (just now|\\d+ seconds ago|\\d+ minutes? ago|\\d+ hours? ago|yesterday|$date)",
        "Scanned in \\d+ s(, \\d+ on another subnet)?",
        "Nothing scanned yet", "Tap to find what is on \\d+\\.\\d+\\.\\d+\\.\\d+/\\d+", "No network", "Connect to Wi-Fi or Ethernet to scan",
        "starting", "probing \\d+ of \\d+  \\d+ s", "identifying \\d+ devices?  \\d+ s", "Quick scanning", "Search name, IP, vendor or MAC",
        "Devices appear here as they are found", "Looking for devices", "No devices found\\. Check the network and scan again\\.",
        "No devices match \".+\"", "No devices marked \\w+", "Network changed, scan stopped",
        "Large network \\(/\\d+\\)\\. Scanned your /24 only\\. Widen in Settings\\.",
        "probing \\S+ for gateways, \\d+ of \\d+  \\d+ s", "probing for gateways, \\d+ of \\d+  \\d+ s", "\\S+ \\(\\d+ of \\d+\\), probing \\d+ of \\d+", "preparing next range  \\d+ s",
        "\\d+ more ranges? found, raise the cap in Settings", "Extended discovery: no extra ranges to sweep", "Gateway probe found no other ranges",
        "Gateway probe skipped: every address answered", "Other ranges skipped: could not bind to Wi-Fi", "\\S+ skipped: every address answered",
        "Stopped at \\S+", "\\d+ other ranges?: nothing found", "\\d+ other ranges? answered for everything", "\\S+: nothing found", "\\d+ other ranges? checked",
        "\\S+  (manual|evidence|gateway probe|local)  (swept, nothing reachable|swept, \\d+ found|skipped, every address answered|not swept, scan limit reached|stopped|not swept)",
    ).map { Regex(it) }
    /** The only blank string a helper may return: a settled radar with stored hosts and no known scan time (summary detail). */
    private fun inTable(s: String) = assertTrue("not in the copy table: '$s'", templates.any { it.matches(s) })
    private fun detailOk(s: String, blankAllowed: Boolean) = if (s.isEmpty()) assertTrue("blank detail where text is expected", blankAllowed) else inTable(s)

    @Test fun `progress states`() {
        val statuses = listOf(null, DiscoveryStatus(), DiscoveryStatus(probed = 0, total = 254), DiscoveryStatus(probed = 171, total = 254, found = 9),
            DiscoveryStatus(probed = 254, total = 254, found = 0), DiscoveryStatus(probed = 254, total = 254, found = 1), DiscoveryStatus(probed = 254, total = 254, found = 14))
        for (st in statuses) for (elapsed in listOf(0L, 7L, 125L)) inTable(ProgressLine.of(st, elapsed))
    }
    @Test fun `every summary state matches the table`() {
        val now = 10_000_000L
        val lastScans = listOf<Long?>(null, now, now - 30_000, now - 120_000, now - 7_200_000, now - 100_000_000L, now - 300_000_000L)
        val finishes = listOf<Pair<Long, Long>?>(null, 1_000_000L to 1_012_000L, 1_000_000L to 1_000_100L)
        for (hosts in listOf(0, 1, 13)) for (other in listOf(0, 1)) for (finish in finishes) for (last in lastScans) {
            val mode = RadarModes.of(false, hosts, last ?: finish?.second)
            if (mode == RadarMode.HERO) continue   // HERO shows the hero strings, checked below
            val s = RadarSummaries.settled(hosts, other, last, finish?.first, finish?.second, now) { date }
            inTable(s.headline)
            detailOk(s.detail, blankAllowed = hosts > 0 && last == null && finish == null)   // the one reachable blank
        }
        for (hosts in listOf(0, 1, 13)) for (other in listOf(0, 1)) ScanSummaryText.of(hosts, other, 12_000).let { inTable(it.headline); inTable(it.detail) }
    }
    @Test fun `hero, empty and notice strings`() {
        listOf(HomeCopy.SCAN, HomeCopy.STOP, HomeCopy.FOUND, HomeCopy.HERO_TITLE, HomeCopy.heroSubtitle("10.0.3.0/24"), HomeCopy.HERO_NO_NETWORK,
            HomeCopy.HERO_NO_NETWORK_SUB, HomeCopy.QUICK_SCANNING, HomeCopy.SEARCH_PLACEHOLDER, HomeCopy.EMPTY_HERO, HomeCopy.EMPTY_SCANNING,
            HomeCopy.EMPTY_NONE, HomeCopy.emptySearch("nas"), HomeCopy.emptyFilter("Interesting"), HomeCopy.NETWORK_CHANGED,
            HomeCopy.largeNetwork(16)).forEach { inTable(it) }
        StatusFilter.entries.filter { it != StatusFilter.ALL }.forEach { inTable(HomeCopy.emptyFilter(it.label)) }
    }

    private val localRange = RangeProgress("10.0.3.0/24", null, RangeState.DONE, 254, 254, 12)

    @Test fun `every extended progress state matches the table`() {
        val probe = GatewayProbeProgress("10.0.0.0/16", 112, 256, 224)
        val unlabelled = GatewayProbeProgress("other ranges", 112, 256, 224)
        for (phase in DiscoveryPhase.entries) for (gateway in listOf<GatewayProbeProgress?>(null, probe, unlabelled)) for (state in RangeState.entries) for (elapsed in listOf(0L, 31L)) {
            val st = DiscoveryStatus(probed = 254, total = 254, found = 9, phase = phase, gatewayProbe = gateway, ranges = listOf(localRange,
                RangeProgress("10.0.4.0/24", RangeSource.EVIDENCE, state, 171, 254, 0), RangeProgress("10.0.6.0/24", RangeSource.MANUAL, RangeState.PENDING, 0, 254, 0)))
            inTable(ProgressLine.of(st, elapsed))   // includes GATEWAY_PROBE with no progress yet ("preparing next range") and EXTENDED with nothing running
        }
    }
    @Test fun `every notice and range summary state matches the table`() {
        for (note in ExtendedNote.entries) inTable(ExtendedNotices.of(DiscoveryStatus(notes = listOf(note))).single())
        for (state in RangeState.entries) for (found in listOf(0, 2)) for (source in RangeSource.entries) for (dropped in listOf(0, 1, 2)) {
            val st = DiscoveryStatus(truncatedRanges = dropped, ranges = listOf(localRange) + (1..4).map { RangeProgress("10.0.$it.0/24", source, state, 254, 254, found) })
            ExtendedNotices.of(st).forEach { inTable(it) }
            RangeSummaryText.lines(st.ranges).forEach { inTable(it) }
            inTable(RangeSummaryText.headline(st.ranges.size - 1))
        }
        inTable(HomeCopy.rangesChecked(5)); inTable(HomeCopy.capDropped(1)); inTable(HomeCopy.capDropped(2))
    }
}
