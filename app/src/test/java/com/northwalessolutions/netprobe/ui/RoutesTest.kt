package com.northwalessolutions.netprobe.ui

import org.junit.Assert.*
import org.junit.Test

/** Spec 9.1 item 1. */
class RoutesTest {
    @Test fun `tabOf maps every route of the table, graph routes and route patterns included`() {
        val expected = mapOf(
            "devicesTab" to Tab.DEVICES, "devices" to Tab.DEVICES, "devices/host/{id}" to Tab.DEVICES, "devices/host/5" to Tab.DEVICES, "devices/wordlists" to Tab.DEVICES,
            "scansTab" to Tab.SCANS, "scans" to Tab.SCANS, "scans/host/{id}" to Tab.SCANS, "scans/wordlists" to Tab.SCANS,
            "toolsTab" to Tab.TOOLS, "tools" to Tab.TOOLS, "tools/wordlists" to Tab.TOOLS,
            "settingsTab" to Tab.SETTINGS, "settings" to Tab.SETTINGS, "settings/wordlists" to Tab.SETTINGS)
        expected.forEach { (route, tab) -> assertEquals(route, tab, Routes.tabOf(route)) }
        assertNull(Routes.tabOf(null)); assertNull(Routes.tabOf("")); assertNull(Routes.tabOf("home")); assertNull(Routes.tabOf("hosts/5"))
    }
    @Test fun `host and wordlists routes per tab`() {
        assertEquals("devices/host/7", Routes.host(Tab.DEVICES, 7)); assertEquals("scans/host/7", Routes.host(Tab.SCANS, 7))
        assertEquals("devices/host/{id}", Routes.hostPattern(Tab.DEVICES)); assertEquals("scans/host/{id}", Routes.hostPattern(Tab.SCANS))
        assertEquals("devices/wordlists", Routes.wordlists(Tab.DEVICES)); assertEquals("scans/wordlists", Routes.wordlists(Tab.SCANS))
        assertEquals("tools/wordlists", Routes.wordlists(Tab.TOOLS)); assertEquals("settings/wordlists", Routes.wordlists(Tab.SETTINGS))
    }
    @Test fun `every route is declared in exactly one graph and the roots are the four first routes`() {
        val all = Tab.entries.flatMap { Routes.routesOf(it) }
        assertEquals(10, all.size); assertEquals("a route may appear once in a NavHost", all.size, all.toSet().size)
        Tab.entries.forEach { tab ->
            assertEquals(tab.root, Routes.routesOf(tab).first())
            Routes.routesOf(tab).forEach { assertEquals(it, tab, Routes.tabOf(it)) }
        }
        assertEquals(listOf("devices", "scans", "tools", "settings"), Tab.entries.map { it.root })
        assertEquals(listOf("devicesTab", "scansTab", "toolsTab", "settingsTab"), Tab.entries.map { it.graph })
    }
    @Test fun `root and host predicates`() {
        Tab.entries.forEach { assertTrue(Routes.isRoot(it.root)); assertFalse(Routes.isRoot(it.graph)) }
        assertFalse(Routes.isRoot("devices/host/{id}")); assertFalse(Routes.isRoot(null))
        assertTrue(Routes.isHost("scans/host/{id}")); assertFalse(Routes.isHost("tools/wordlists")); assertFalse(Routes.isHost(null))
    }
}
