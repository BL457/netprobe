package com.northwalessolutions.netprobe.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 9.1 item 3 (D7). */
class NavMotionTest {
    @Test fun `a push or pop inside a destination slides`() {
        assertEquals(NavMotionKind.SLIDE, NavMotion.kind("devices", "devices/host/{id}", reduced = false))
        assertEquals(NavMotionKind.SLIDE, NavMotion.kind("devices/host/{id}", "devices", reduced = false))
        assertEquals(NavMotionKind.SLIDE, NavMotion.kind("settings", "settings/wordlists", reduced = false))
    }
    @Test fun `a switch between destinations cross-fades`() {
        assertEquals(NavMotionKind.FADE, NavMotion.kind("devices", "scans", reduced = false))
        assertEquals(NavMotionKind.FADE, NavMotion.kind("scans", "tools", reduced = false))
        assertEquals(NavMotionKind.FADE, NavMotion.kind("settings", "devices", reduced = false))
        assertEquals(NavMotionKind.FADE, NavMotion.kind("scans/host/{id}", "devices", reduced = false))
    }
    @Test fun `reduced motion is none for every pair`() {
        val routes = listOf("devices", "scans", "tools", "settings", "devices/host/{id}", "tools/wordlists", null)
        for (a in routes) for (b in routes) assertEquals("$a -> $b", NavMotionKind.NONE, NavMotion.kind(a, b, reduced = true))
    }
}
