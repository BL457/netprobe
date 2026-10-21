package com.northwalessolutions.netprobe.ui.scans

import com.northwalessolutions.netprobe.engine.portscan.PortScans
import com.northwalessolutions.netprobe.ui.detail.DetailCopy
import org.junit.Assert.*
import org.junit.Test

/** Spec 9.1 item 12: section 6 as regex templates; every helper is driven through a matrix of inputs. */
class ScansCopyTableTest {
    private val templates: List<Regex> = listOf(
        "Scans", "Running", "Recent", "Nothing running", "Scans and tool runs show here while they run, and finished ones stay for a day",
        "Clear history", "History cleared", "Undo", "Radar scan", "Quick scan all", "Port scan", "Stop", "device",
        "Port scan on .+", ".+ on .+:\\d+", "Wordlist from .+", "\\d+ of \\d+ devices, \\d+ s",
        "Done in (\\d+ s|\\d+ min( \\d+ s)?), (no devices|1 device|\\d+ devices)",
        "Done in (\\d+ s|\\d+ min( \\d+ s)?), (no devices|1 device|\\d+ devices) scanned",
        "Done in (\\d+ s|\\d+ min( \\d+ s)?), (no open ports|1 open port|\\d+ open ports)",
        "Stopped after (\\d+ s|\\d+ min( \\d+ s)?)", "Interrupted when NetProbe closed", "\\d+ s|\\d+ min( \\d+ s)?",
        ".+, (just now|\\d+ seconds ago|\\d+ minutes? ago|\\d+ hours? ago|yesterday|3 Jan 2026)", "Stop (port|radar|quick|directory|http|tls|snmp|smb|wordlist).+",
        "(One port scan is|\\w+ port scans are) already running, stop (it|one) in Scans",
    ).map { Regex(it) }
    private fun inTable(s: String) = assertTrue("not in the copy table: '$s'", templates.any { it.matches(s) })

    @Test fun `fixed strings`() {
        listOf(ScansCopy.TITLE, ScansCopy.RUNNING, ScansCopy.RECENT, ScansCopy.EMPTY, ScansCopy.EMPTY_LINE, ScansCopy.CLEAR, ScansCopy.CLEARED, ScansCopy.UNDO,
            ScansCopy.RADAR, ScansCopy.QUICK, ScansCopy.PORT_SCAN_NAME, ScansCopy.STOP, ScansCopy.UNKNOWN_HOST, ScansCopy.INTERRUPTED).forEach { inTable(it) }
        assertEquals("Undo", com.northwalessolutions.netprobe.ui.home.HomeCopy.UNDO); assertEquals(com.northwalessolutions.netprobe.ui.home.HomeCopy.UNDO, ScansCopy.UNDO)
    }
    @Test fun `duration at the boundaries`() {
        assertEquals("0 s", ScansCopy.duration(0)); assertEquals("59 s", ScansCopy.duration(59)); assertEquals("1 min", ScansCopy.duration(60))
        assertEquals("2 min 4 s", ScansCopy.duration(124)); assertEquals("0 s", ScansCopy.duration(-5))
        listOf(0L, 8L, 59L, 60L, 124L, 3_600L).forEach { inTable(ScansCopy.duration(it)) }
    }
    @Test fun `summaries in every plural and zero form`() {
        for (s in listOf(0L, 14L, 124L)) {
            for (n in listOf(0, 1, 9)) { inTable(ScansCopy.radarDone(n, s)); inTable(ScansCopy.quickDone(n, s)); inTable(ScansCopy.portDone(n, s)) }
            inTable(ScansCopy.stoppedAfter(s))
        }
        assertEquals("Done in 12 s, 9 devices", ScansCopy.radarDone(9, 12)); assertEquals("Done in 12 s, 1 device", ScansCopy.radarDone(1, 12))
        assertEquals("Done in 2 min 4 s, 30 devices scanned", ScansCopy.quickDone(30, 124)); assertEquals("Done in 9 s, 1 device scanned", ScansCopy.quickDone(1, 9))
        assertEquals("Done in 14 s, 5 open ports", ScansCopy.portDone(5, 14)); assertEquals("Done in 14 s, 1 open port", ScansCopy.portDone(1, 14))
        assertEquals("Done in 14 s, no open ports", ScansCopy.portDone(0, 14)); assertEquals("Stopped after 8 s", ScansCopy.stoppedAfter(8))
    }
    @Test fun `titles and lines`() {
        inTable(ScansCopy.portScan("Office NAS")); inTable(ScansCopy.tool("Directory brute-force", "Office NAS", 443)); inTable(ScansCopy.tool("SNMP walk", "10.0.3.9", 161))
        inTable(ScansCopy.wordlistImport("raw.githubusercontent.com")); inTable(ScansCopy.quickProgress(3, 30, 42))
        assertEquals("Port scan on Office NAS", ScansCopy.portScan("Office NAS")); assertEquals("Directory brute-force on Office NAS:443", ScansCopy.tool("Directory brute-force", "Office NAS", 443))
        assertEquals("SNMP walk on 10.0.3.9:161", ScansCopy.tool("SNMP walk", "10.0.3.9", 161)); assertEquals("3 of 30 devices, 42 s", ScansCopy.quickProgress(3, 30, 42))
        for (r in listOf("just now", "5 seconds ago", "2 minutes ago", "1 hour ago", "yesterday", "3 Jan 2026")) inTable(ScansCopy.recentLine("Done in 12 s, 9 devices", r))
        assertEquals("Done in 12 s, 9 devices, 2 minutes ago", ScansCopy.recentLine("Done in 12 s, 9 devices", "2 minutes ago"))
    }
    @Test fun `stop description lower cases the first letter of the title`() {
        assertEquals("Stop port scan on Office NAS", ScansCopy.stopDescription("Port scan on Office NAS"))
        assertEquals("Stop radar scan", ScansCopy.stopDescription("Radar scan"))
        listOf("Port scan on Office NAS", "Radar scan", "Quick scan all", "Directory brute-force on Office NAS:443", "Wordlist from example.com").forEach { inTable(ScansCopy.stopDescription(it)) }
    }
    @Test fun `the cap line names the constant's value as a word`() {
        val word = listOf("Zero", "One", "Two", "Three", "Four")[PortScans.MAX_PORT_SCANS]
        assertTrue(DetailCopy.portScansTooMany(PortScans.MAX_PORT_SCANS).startsWith(word))
        for (n in 1..4) inTable(DetailCopy.portScansTooMany(n))
        assertEquals("Two port scans are already running, stop one in Scans", DetailCopy.portScansTooMany(2))
    }
    @Test fun `no Scans string carries an em dash or the old Activity word`() {
        val dash = 0x2014.toChar()
        val all = listOf(ScansCopy.TITLE, ScansCopy.EMPTY_LINE, ScansCopy.INTERRUPTED, ScansCopy.portScan("x"), ScansCopy.radarDone(2, 5), DetailCopy.portScansTooMany(2))
        all.forEach { assertFalse(it, it.contains(dash)); assertFalse(it, it.contains("Activity")) }
    }
}
