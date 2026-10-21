package com.northwalessolutions.netprobe.ui

import org.junit.Assert.*
import org.junit.Test

/** Spec 6 (Nav rows) as regex templates, plus the badge cap and the back-label rule. */
class NavCopyTableTest {
    private val templates = listOf("Devices", "Scans", "Tools", "Settings", "Scans, \\d+ running", "[1-9]|9\\+", "Back to (Devices|Scans|Tools|Settings)", "Back to device").map { Regex(it) }
    private fun inTable(s: String) = assertTrue("not in the copy table: '$s'", templates.any { it.matches(s) })

    @Test fun `labels and back rows`() {
        Tab.entries.forEach { inTable(NavCopy.label(it)); inTable(NavCopy.backTo(it)) }
        assertEquals(listOf("Devices", "Scans", "Tools", "Settings"), Tab.entries.map { NavCopy.label(it) })
        assertEquals("Back to Devices", NavCopy.backTo(Tab.DEVICES)); assertEquals("Back to Scans", NavCopy.backTo(Tab.SCANS))
        assertEquals("Back to Tools", NavCopy.backTo(Tab.TOOLS)); assertEquals("Back to Settings", NavCopy.backTo(Tab.SETTINGS))
        inTable(NavCopy.BACK_TO_DEVICE)
    }
    @Test fun `badge text counts to nine then caps`() {
        assertEquals("1", NavCopy.badgeText(1)); assertEquals("8", NavCopy.badgeText(8)); assertEquals("9", NavCopy.badgeText(9))
        assertEquals("9+", NavCopy.badgeText(10)); assertEquals("9+", NavCopy.badgeText(40))
        (1..12).forEach { inTable(NavCopy.badgeText(it)); inTable(NavCopy.scansBadge(it)) }
        assertEquals("Scans, 1 running", NavCopy.scansBadge(1)); assertEquals("Scans, 3 running", NavCopy.scansBadge(3))
    }
    @Test fun `a wordlists page opened from a device says Back to device and any other opener names its tab`() {
        assertEquals("Back to device", NavLabels.wordlists("devices/host/{id}", Tab.DEVICES))
        assertEquals("Back to device", NavLabels.wordlists("scans/host/{id}", Tab.SCANS))
        assertEquals("Back to Tools", NavLabels.wordlists("tools", Tab.TOOLS))
        assertEquals("Back to Settings", NavLabels.wordlists("settings", Tab.SETTINGS))
        assertEquals("Back to Scans", NavLabels.wordlists("scans", Tab.SCANS))
        assertEquals("Back to Scans", NavLabels.wordlists(null, Tab.SCANS))
        assertEquals("Back to Devices", NavLabels.host(Tab.DEVICES)); assertEquals("Back to Scans", NavLabels.host(Tab.SCANS))
    }
    @Test fun `no Nav string has an em dash or the word Home or Activity`() {
        val dash = 0x2014.toChar()
        val all = Tab.entries.flatMap { listOf(NavCopy.label(it), NavCopy.backTo(it)) } + NavCopy.BACK_TO_DEVICE + NavCopy.scansBadge(3)
        all.forEach { assertFalse(it, it.contains(dash)); assertFalse(it, it.contains("Home")); assertFalse(it, it.contains("Activity")) }
    }
}
