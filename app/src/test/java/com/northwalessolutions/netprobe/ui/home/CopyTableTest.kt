package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.actions.DeviceAction
import com.northwalessolutions.netprobe.actions.PortUris
import com.northwalessolutions.netprobe.engine.discovery.*
import com.northwalessolutions.netprobe.data.TestHosts
import com.northwalessolutions.netprobe.engine.identify.DeviceType
import com.northwalessolutions.netprobe.ui.detail.DetailCopy
import com.northwalessolutions.netprobe.ui.detail.EditCopy
import com.northwalessolutions.netprobe.ui.detail.setByYouLine
import com.northwalessolutions.netprobe.ui.settings.DefaultMarker
import com.northwalessolutions.netprobe.ui.settings.ExtendedCopy
import com.northwalessolutions.netprobe.ui.settings.SettingsCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.northwalessolutions.netprobe.ui.manual.ManualCopy
import com.northwalessolutions.netprobe.ui.settings.ListenCopy

/**
 * The Radar spec section 8 copy table held as regex templates. Each test generates a matrix of reachable states and asserts every
 * produced string matches a template, so a state that yields wording outside the table fails here. Task 16 appends its own templates.
 */
class CopyTableTest {
    private val date = "3 Jan 2026"
    private val templates: List<Regex> = listOf(
        "Forget this network", "Forgot .+", "Removed .+", "Undo",
        "Scan", "Stop", "found", "No devices found", "\\d+ devices?",
        "Last scan (just now|\\d+ seconds ago|\\d+ minutes? ago|\\d+ hours? ago|yesterday|$date)",
        "(Scanned in|Stopped after) \\d+ s(, \\d+ on another subnet)?",
        "Nothing scanned yet", "Tap to find what is on \\d+\\.\\d+\\.\\d+\\.\\d+/\\d+", "No network", "Connect to Wi-Fi or Ethernet to scan",
        "starting", "probing \\d+ of \\d+  \\d+ s", "identifying \\d+ devices?  \\d+ s", "Quick scanning", "Search name, IP, vendor or MAC",
        "Devices appear here as they are found", "Looking for devices", "No devices found\\. Check the network and scan again\\.",
        "No devices match \".+\"", "No devices marked \\w+", "Network changed, scan stopped",
        "Large network \\(/\\d+\\)\\. Scanned your /\\d+ only\\.( Widen in Settings\\.| Turn on Aggressive scan in Settings\\.)?",
        "probing \\S+ for gateways, \\d+ of \\d+  \\d+ s", "probing for gateways, \\d+ of \\d+  \\d+ s", "\\S+ \\(\\d+ of \\d+\\), probing \\d+ of \\d+", "preparing next range  \\d+ s",
        "\\d+ more ranges? found, raise Maximum extra ranges in Settings", "Aggressive scan: no extra ranges to sweep", "Smart discovery found no other ranges",
        "Smart discovery skipped: every address answered", "Other ranges skipped: could not bind to Wi-Fi", "\\S+ skipped: every address answered", "Smart discovery: too many ranges answered, nearby ranges not checked",
        "Stopped at \\S+", "\\d+ other ranges?: nothing found", "\\d+ other ranges? answered for everything", "\\S+: nothing found", "\\d+ other ranges? checked",
        "\\S+  (manual|evidence|smart discovery|local)  (swept, nothing reachable|swept, \\d+ found|skipped, every address answered|not swept, scan limit reached|stopped|not started)",
        "Scan (finished|stopped), (\\d+ devices?|no devices found)", "Scan skipped: could not bind to Wi-Fi", "identifying \\d+ ports?",
        "\\d+ of \\d+, found \\d+",
        "Runs .+",
        "About", "NetProbe", "Copy", "Close", "Version \\S+ \\(\\d+\\)", "Build \\S+, \\S+ \\d\\d:\\d\\d", "Android \\S+",
        "Set by you: (type (Router|Phone|Computer|Printer|Media|IoT|NAS|Camera|Server)(, vendor .+)?(, model .+)?|vendor .+(, model .+)?|model .+)",
        "Edit device, .+", "More options", "Edit device", "Remove device", "Name", "Type", "Vendor", "Model", "Detected: .+", "Reset to detected",
        "Router", "Phone", "Computer", "Printer", "Media", "IoT", "NAS", "Camera", "Server", "Not set", "Save", "Cancel",
        "Open with\\.\\.\\.", "Try as", "(http|https|ssh|vnc|rdp|ftp|telnet|smb)",
        "Open \\d+\\.\\d+\\.\\d+\\.\\d+:\\d+ as (http|https|ssh|vnc|rdp|ftp|telnet|smb)",
        // 3C-1 Settings (spec 7.2)
        "Settings menu", "Reset to defaults", "Settings reset to defaults", "Default \\d+( ms)?", "Reset .+ to \\d+( ms)?",
        "Largest block of a big network to sweep\\. With Aggressive scan off, a network wider than this gets your /24 only\\. .+",
        "Aggressive scan", "Smart discovery", "Swept first on every scan, with Aggressive scan on or off", "Your subnet is always scanned; .+", "Looks for gateways at the usual addresses .+",
    ).map { Regex(it) }
    /** The only blank string a helper may return: a settled radar with stored hosts and no known scan time (summary detail). */
    private fun inTable(s: String) = assertTrue("not in the copy table: '$s'", templates.any { it.matches(s) })
    private fun detailOk(s: String, blankAllowed: Boolean) = if (s.isEmpty()) assertTrue("blank detail where text is expected", blankAllowed) else inTable(s)

    @Test fun `forget and undo strings`() {
        listOf(HomeCopy.FORGET_NETWORK, HomeCopy.UNDO, HomeCopy.forgot("Home"), HomeCopy.removed("DiskStation")).forEach { inTable(it) }
        assertEquals("Forgot Home", HomeCopy.forgot("Home")); assertEquals("Removed DiskStation", HomeCopy.removed("DiskStation"))
    }

    @Test fun `about strings`() {
        listOf(HomeCopy.ABOUT, HomeCopy.APP_NAME, HomeCopy.COPY_VERSION, HomeCopy.CLOSE, HomeCopy.version("0.3.0", 3),
            HomeCopy.build("c8a62e6", "2026-10-21 14:05"), HomeCopy.android("14")).forEach { inTable(it) }
        assertEquals("Version 0.3.0 (3)", HomeCopy.version("0.3.0", 3))
    }

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
            for (completed in listOf(true, false)) {
                val s = RadarSummaries.settled(hosts, other, last, finish?.first, finish?.second, now, completed = completed) { date }
                inTable(s.headline)
                detailOk(s.detail, blankAllowed = hosts > 0 && last == null && finish == null)   // the one reachable blank
            }
        }
        for (hosts in listOf(0, 1, 13)) for (other in listOf(0, 1)) for (completed in listOf(true, false)) ScanSummaryText.of(hosts, other, 12_000, completed).let { inTable(it.headline); inTable(it.detail) }
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
            RangeSummaryText.checked(st.ranges)?.let { inTable(it); assertFalse("headline with nothing swept: '$it'", it.startsWith("0 ")) }
        }
        inTable(HomeCopy.rangesChecked(5)); inTable(HomeCopy.capDropped(1)); inTable(HomeCopy.capDropped(2))
    }
    @Test fun `software chip strings`() {
        listOf("Home Assistant", "Plex Media Server", "UniFi Network").forEach { inTable(DetailCopy.runs(it)) }
        assertTrue(DetailCopy.runs("Grafana") == "Runs Grafana")
    }
    @Test fun `edit dialog, overflow and set by you strings`() {
        listOf(EditCopy.TITLE, EditCopy.NAME, EditCopy.TYPE, EditCopy.VENDOR, EditCopy.MODEL, EditCopy.RESET, EditCopy.NOT_SET, EditCopy.SAVE, EditCopy.CANCEL,
            EditCopy.detected("Synology"), EditCopy.detected(null), DetailCopy.MORE_OPTIONS, DetailCopy.EDIT_DEVICE, DetailCopy.REMOVE_DEVICE,
            DetailCopy.editDescription("DiskStation")).forEach { inTable(it) }
        assertEquals("Detected: none", EditCopy.detected(null)); assertEquals(9, EditCopy.TYPES.size)
        EditCopy.TYPES.forEach { inTable(EditCopy.typeLabel(it)) }
        val h = TestHosts.host()
        for (t in listOf(null, DeviceType.NAS)) for (v in listOf(null, "Synology")) for (m in listOf(null, "DS220+"))
            setByYouLine(h.copy(userType = t, userVendor = v, userModel = m))?.let { inTable(it) }
    }
    @Test fun `port sheet strings`() {
        inTable(DeviceAction.OpenWith("http://10.0.3.59:8090/").label); inTable(DetailCopy.TRY_AS)
        PortUris.TRY_AS.forEach { inTable(it); inTable(DetailCopy.tryAsDescription("10.0.3.59", 8123, it)) }
        assertEquals("Open 10.0.3.59:8123 as ssh", DetailCopy.tryAsDescription("10.0.3.59", 8123, "ssh"))
    }
    @Test fun `announcements, bind wording and the identify tail`() {
        for (h in listOf("No devices found", "1 device", "13 devices")) { inTable(RadarText.finished(h)); inTable(RadarText.stopped(h)) }
        inTable(DetailCopy.SCAN_BIND_FAILED); inTable(DetailCopy.identifying(1)); inTable(DetailCopy.identifying(3))
        for (p in listOf(com.northwalessolutions.netprobe.engine.portscan.ScanProgress(312, 65535, 4, 0), com.northwalessolutions.netprobe.engine.portscan.ScanProgress(9, 9, 2, 0)))
            for (n in listOf(0, 1, 3)) inTable(com.northwalessolutions.netprobe.ui.detail.detailProgress(p, n))
    }

    @Test fun `settings reset strings`() {
        listOf(SettingsCopy.MENU, SettingsCopy.RESET, SettingsCopy.RESET_DONE, SettingsCopy.LARGE_NETWORKS_CAPTION).forEach { inTable(it) }
        for (unit in listOf("", " ms")) for (v in listOf(1, 4, 128, 600, 2000)) {
            inTable(SettingsCopy.defaultMarker(v, unit)); inTable(SettingsCopy.resetDescription("Sweep connect timeout", v, unit))
            DefaultMarker.text(v + 1, v, unit)?.let { inTable(it) }
        }
    }

    @Test fun `aggressive scan and the large network notices`() {
        listOf(ExtendedCopy.TITLE, ExtendedCopy.SUPPORT, ExtendedCopy.PROBE_LABEL, ExtendedCopy.PROBE_SUPPORT, ExtendedCopy.MANUAL_LINE,
            HomeCopy.GATEWAY_WALK_SKIPPED, HomeCopy.largeNetwork(16), HomeCopy.largeNetwork(21), HomeCopy.largeNetworkPartial(16, 22), HomeCopy.largeNetworkPartial(16, 20)).forEach { inTable(it) }
        assertEquals("Large network (/16). Scanned your /22 only. Widen in Settings.", HomeCopy.largeNetworkPartial(16, 22))
        assertEquals("at the widest limit there is nothing to widen", "Large network (/16). Scanned your /20 only.", HomeCopy.largeNetworkPartial(16, 20))
        assertEquals("widening cannot help a /16: Aggressive scan can", "Large network (/16). Scanned your /24 only. Turn on Aggressive scan in Settings.", HomeCopy.largeNetwork(16))
        assertEquals("Large network (/21). Scanned your /24 only. Widen in Settings.", HomeCopy.largeNetwork(21))
        assertEquals("1 more range found, raise Maximum extra ranges in Settings", HomeCopy.capDropped(1))
    }
    @Test fun `names removed from the draft do not exist`() {   // spec 7.2
        val fields = HomeCopy::class.java.declaredFields.map { it.name }; val methods = HomeCopy::class.java.declaredMethods.map { it.name }
        assertFalse("MENU_AGGRESSIVE" in fields); assertFalse("aggressiveDescription" in methods)
    }
    @Test fun `manual tools strings`() {
        val manualTemplates = listOf("Run a tool\\.\\.\\.", "Run a tool", "IP address", "10\\.0\\.3\\.20", "Port", "443", "Tool: .+", "Use HTTPS",
            "Uses port 445", "Uses UDP 161", "Known device: .+, results also show on its page", "Connect to Wi-Fi or Ethernet to run a tool",
            "Enter an address like 10\\.0\\.3\\.20", "That address cannot be used", "This tool needs a port", "Use a port from 1 to 65535",
            "Recent runs on this network", "No tools run on this network yet", ".+ on \\d+\\.\\d+\\.\\d+\\.\\d+:(\\d+|UDP 161)", ".+, .+",
            "Show .+ results for \\d+\\.\\d+\\.\\d+\\.\\d+:(\\d+|UDP 161)").map { Regex(it) }
        val strings = listOf(ManualCopy.TITLE, ManualCopy.IP, ManualCopy.IP_PLACEHOLDER, ManualCopy.PORT, ManualCopy.PORT_PLACEHOLDER,
            ManualCopy.toolRow("HTTP fingerprint"), ManualCopy.USE_HTTPS, ManualCopy.FIXED_SMB, ManualCopy.FIXED_SNMP, ManualCopy.knownDevice("CHNAS"),
            ManualCopy.NO_LAN, ManualCopy.ERR_IP, ManualCopy.ERR_ADDRESS, ManualCopy.ERR_PORT_NEEDED, ManualCopy.ERR_PORT_RANGE, ManualCopy.HISTORY,
            ManualCopy.HISTORY_EMPTY, ManualCopy.historyRow("HTTP fingerprint", "10.0.3.5", "5000"), ManualCopy.historyRow("SNMP walk", "10.0.3.1", "UDP 161"),
            ManualCopy.historyLine("Synology DSM, 6 findings", "2 min ago"), ManualCopy.historyDescription("HTTP fingerprint", "10.0.3.5", "5000"))
        strings.forEach { s -> assertTrue("not in the manual tools copy table: '$s'", manualTemplates.any { it.matches(s) }); assertFalse("em-dash in '$s'", s.contains('\u2014')) }
        assertEquals("HTTP fingerprint on 10.0.3.5:5000", ManualCopy.historyRow("HTTP fingerprint", "10.0.3.5", "5000"))
        assertEquals("Synology DSM, 6 findings, 2 min ago", ManualCopy.historyLine("Synology DSM, 6 findings", "2 min ago"))
        val removed = HomeCopy::class.java.declaredFields.map { it.name } + ManualCopy::class.java.declaredFields.map { it.name }
        for (gone in listOf("MENU_AGGRESSIVE", "ERR_PRIVATE")) assertFalse("$gone was removed from the spec", gone in removed)   // spec 7.2
    }
    @Test fun `listening strings`() {
        val listenTemplates = listOf("Listen between scans", "While NetProbe is open, adds devices heard announcing themselves over mDNS and SSDP\\.",
            "Listening for (mDNS and SSDP|mDNS only|SSDP only)", "Could not open the multicast ports", "Not listening: connect to Wi-Fi or Ethernet",
            "Paused while a scan runs", "mDNS and SSDP are off above", "Scan this network once to start listening",
            "Listening stopped after an error, retrying").map { Regex(it) }
        val strings = listOf(ListenCopy.LABEL, ListenCopy.SUPPORT) + ListenStatus.entries.mapNotNull { ListenCopy.status(it) }
        strings.forEach { s -> assertTrue("not in the listening copy table: '$s'", listenTemplates.any { it.matches(s) }); assertFalse("em-dash in '$s'", s.contains('\u2014')) }
        assertEquals(11, strings.size)   // label, subtitle and the nine status lines (spec 3.1, amendment A3); OFF shows nothing
        assertEquals(null, ListenCopy.status(ListenStatus.OFF))
    }
}
