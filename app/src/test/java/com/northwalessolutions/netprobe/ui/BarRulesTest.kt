package com.northwalessolutions.netprobe.ui

import org.junit.Assert.*
import org.junit.Test

/** Spec 9.1 item 2 (D5 and D6). */
class BarRulesTest {
    @Test fun `visible on the four roots with the keyboard down`() {
        Tab.entries.forEach { assertTrue(it.root, BarRules.visible(it.root, imeVisible = false)) }
    }
    @Test fun `hidden on every pushed route`() {
        Tab.entries.flatMap { Routes.routesOf(it) }.filterNot { Routes.isRoot(it) }.forEach { assertFalse(it, BarRules.visible(it, imeVisible = false)) }
    }
    @Test fun `hidden with the keyboard up on any route`() {
        Tab.entries.flatMap { Routes.routesOf(it) }.forEach { assertFalse(it, BarRules.visible(it, imeVisible = true)) }
    }
    @Test fun `hidden before the first destination is known`() {
        assertFalse(BarRules.visible(null, imeVisible = false))
    }
}
