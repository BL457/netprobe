package com.northwalessolutions.netprobe.ui

import org.junit.Assert.*
import org.junit.Test

/** Spec 9.1 item 1. */
class RoutesTest {
    @Test fun `tabOf maps every route of the table, graph routes and route patterns included`() {
        val expected = mapOf(
            "devicesTab" to Tab.DEVICES, "devices" to Tab.DEVICES, "devices/host/{id}" to Tab.DEVICES, "devices/host/5" to Tab.DEVICES, "devices/wordlists" to Tab.DEVICES,
            "devices/tool/{key}?host={host}&port={port}" to Tab.DEVICES, "scans/tool/http-fingerprint?host=3&port=80" to Tab.SCANS, "tools/tool/user~x?host=-1&port=-1" to Tab.TOOLS,
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
        assertEquals(13, all.size); assertEquals("a route may appear once in a NavHost", all.size, all.toSet().size)
        Tab.entries.forEach { tab ->
            assertEquals(tab.root, Routes.routesOf(tab).first())
            Routes.routesOf(tab).forEach { assertEquals(it, tab, Routes.tabOf(it)) }
        }
        assertEquals(listOf("devices", "scans", "tools", "settings"), Tab.entries.map { it.root })
        assertEquals(listOf("devicesTab", "scansTab", "toolsTab", "settingsTab"), Tab.entries.map { it.graph })
    }
    @Test fun `tool routes carry the key with a tilde for the colon, and the optional device and port`() {
        assertEquals("tools/tool/{key}?host={host}&port={port}", Routes.toolPattern(Tab.TOOLS))
        assertEquals("devices/tool/{key}?host={host}&port={port}", Routes.toolPattern(Tab.DEVICES))
        assertEquals("tools/tool/dir-bruteforce?host=-1&port=-1", Routes.tool(Tab.TOOLS, "dir-bruteforce"))
        assertEquals("scans/tool/http-fingerprint?host=12&port=8080", Routes.tool(Tab.SCANS, "http-fingerprint", 12, 8080))
        assertEquals("tools/tool/user~my-tool?host=-1&port=-1", Routes.tool(Tab.TOOLS, "user:my-tool"))
        listOf("user:my-tool", "example:dns-lookup", "dir-bruteforce").forEach { assertEquals(it, Routes.toolKey(Routes.toolToken(it))) }
        assertFalse(Routes.toolToken("example:a").contains(':'))
        assertTrue(Routes.isTool("tools/tool/{key}?host={host}&port={port}")); assertFalse(Routes.isTool("tools/wordlists")); assertFalse(Routes.isTool(null))
        assertTrue("settings has no tool page", Routes.routesOf(Tab.SETTINGS).none { Routes.isTool(it) })
    }
    @Test fun `root and host predicates`() {
        Tab.entries.forEach { assertTrue(Routes.isRoot(it.root)); assertFalse(Routes.isRoot(it.graph)) }
        assertFalse(Routes.isRoot("devices/host/{id}")); assertFalse(Routes.isRoot(null))
        assertTrue(Routes.isHost("scans/host/{id}")); assertFalse(Routes.isHost("tools/wordlists")); assertFalse(Routes.isHost(null))
    }
}
