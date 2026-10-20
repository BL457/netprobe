package com.northwalessolutions.netprobe.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderRulesTest {
    @Test fun `settled always shows chips and search`() {
        for (f in StatusFilter.entries) assertTrue(HeaderRules.showFilters(RadarMode.SETTLED, f, ""))
    }
    @Test fun `large disc with All and a blank query collapses them`() {
        assertFalse(HeaderRules.showFilters(RadarMode.SCANNING, StatusFilter.ALL, ""))
        assertFalse(HeaderRules.showFilters(RadarMode.SCANNING, StatusFilter.ALL, "   "))
        assertFalse(HeaderRules.showFilters(RadarMode.HERO, StatusFilter.ALL, ""))
    }
    @Test fun `an active filter or query keeps them while scanning`() {
        assertTrue(HeaderRules.showFilters(RadarMode.SCANNING, StatusFilter.INTERESTING, ""))
        assertTrue(HeaderRules.showFilters(RadarMode.SCANNING, StatusFilter.ALL, "nas"))
    }
}
