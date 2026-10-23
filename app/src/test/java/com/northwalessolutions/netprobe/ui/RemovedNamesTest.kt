package com.northwalessolutions.netprobe.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 3D spec 6 "Removed" and the Director's rulings of 2026-10-21 (section 11). The forbidden words are built from parts so this file never matches itself. */
class RemovedNamesTest {
    private val sourceRoot: File = listOf(File("src"), File("app/src")).first { it.isDirectory }
    private val sources: List<Pair<String, String>> = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.path to it.readText() }.toList()
    private fun fieldNames(className: String): List<String> = Class.forName(className).declaredFields.map { it.name }

    @Test fun `the moved and removed copy constants are gone`() {
        val home = fieldNames("com.northwalessolutions.netprobe.ui.home.HomeCopy")
        assertFalse("HomeCopy.ABOUT moved to SettingsCopy.ABOUT_ROW", "ABOUT" in home)
        assertFalse("D16: no overflow item for manual tools", "MENU_RUN_TOOL" in home)
        assertFalse("ManualCopy.TITLE is ToolsCopy.TITLE now", "TITLE" in fieldNames("com.northwalessolutions.netprobe.ui.manual.ManualCopy"))
    }
    @Test fun `the old Home back row and its component are gone from every source`() {
        val oldLabel = "Back to " + "Home"
        val oldComponent = "BackTo" + "Home"
        val hits = sources.filter { (_, text) -> text.contains(oldLabel) || text.contains(oldComponent) }.map { it.first }
        // The only allowed mentions are the assertDoesNotExist lines in the Compose tests, which spell the label out to prove it is absent.
        assertTrue("old back row still referenced in: $hits", hits.all { it.contains("androidTest") })
    }
    @Test fun `the Director's Scans label is used everywhere and no Activity name remains`() {
        val forbidden = listOf("Activity" + "Copy", "activity" + "Tab", "activity" + "ClearedAt", "activity" + "OpenScans", "\"" + "act" + "ivity/")
        val hits = sources.filter { (_, text) -> forbidden.any { text.contains(it) } }.map { it.first }
        assertTrue("Activity naming left in: $hits", hits.isEmpty())
    }
    @Test fun `the destinations and the persisted keys are the Director's names`() {
        assertEquals(listOf("devices", "scans", "tools", "settings"), Tab.entries.map { it.root })
        assertEquals("scansClearedAt", com.northwalessolutions.netprobe.ui.scans.ScansHistoryStore.KEY)
        assertEquals("scansOpenScans", com.northwalessolutions.netprobe.engine.portscan.PrefsOpenScanStore.KEY)
        assertEquals("Scans", NavCopy.SCANS); assertEquals("Scans", com.northwalessolutions.netprobe.ui.scans.ScansCopy.TITLE)
    }
    @Test fun `the brute-force options sheet and its store are gone, the generated form took their job`() {
        val forbidden = listOf("DirBrute" + "Sheet", "DirBrute" + "OptionsStore", "dirBrute" + "Options", "onStart" + "DirBrute")
        val hits = sources.filter { (_, text) -> forbidden.any { text.contains(it) } }.map { it.first }
        assertTrue("old sheet or store still referenced in: $hits", hits.isEmpty())
    }
    @Test fun `the old manual tools screen is gone, the grid and the tool page replaced it`() {
        val forbidden = listOf("ManualTools" + "Screen", "ManualTools" + "ViewModel", "Manual" + "Presenter")
        val hits = sources.filter { (_, text) -> forbidden.any { text.contains(it) } }.map { it.first }
        assertTrue("old manual screen still referenced in: $hits", hits.isEmpty())
    }
    @Test fun `the Home overflow code offers nothing but Quick scan all, Hide devices with no open ports and Forget`() {
        val menu = sources.first { it.first.replace(File.separatorChar, '/').endsWith("ui/home/HomeComponents.kt") }.second
        assertEquals("HomeComponents.kt holds only the overflow's three menu items", 3, Regex(Regex.escape("DropdownMenuItem(")).findAll(menu).count())
        assertTrue(menu.contains("HomeCopy.FORGET_NETWORK")); assertTrue(menu.contains("HomeCopy.HIDE_PORTLESS"))
        assertFalse(menu.contains("onSettings")); assertFalse(menu.contains("onAbout"))
    }
}
