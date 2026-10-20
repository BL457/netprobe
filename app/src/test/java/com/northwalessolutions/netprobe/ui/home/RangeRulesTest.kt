package com.northwalessolutions.netprobe.ui.home

import com.northwalessolutions.netprobe.netenv.CidrParser
import org.junit.Assert.assertEquals
import org.junit.Test

class RangeRulesTest {
    private fun net(s: String) = CidrParser.subnetOf(s)!!
    private val local = net("10.0.3.0/24")

    @Test fun `local range is not off subnet`() { assertEquals("10.0.3.0/24" to false, RangeRules.groupFor("10.0.3.9", listOf(local), emptyList())) }
    @Test fun `a swept extra range groups its hosts`() {
        assertEquals("10.0.4.0/24" to true, RangeRules.groupFor("10.0.4.20", listOf(local, net("10.0.4.0/24")), emptyList()))
    }
    @Test fun `a manual slash 28 inside a slash 24 groups only its own hosts`() {
        val manual = listOf(net("10.0.4.16/28"))
        assertEquals("10.0.4.16/28" to true, RangeRules.groupFor("10.0.4.20", listOf(local, net("10.0.4.16/28")), manual))
        assertEquals("10.0.4.0/24" to true, RangeRules.groupFor("10.0.4.50", listOf(local, net("10.0.4.16/28")), manual))
    }
    @Test fun `an unswept off subnet host falls back to its slash 24`() { assertEquals("10.0.7.0/24" to true, RangeRules.groupFor("10.0.7.3", listOf(local), emptyList())) }
    @Test fun `with no local range known the slash 24 is not off subnet`() { assertEquals("10.0.7.0/24" to false, RangeRules.groupFor("10.0.7.3", emptyList(), emptyList())) }
    @Test fun `garbage is never resolved`() { assertEquals("nas.local" to false, RangeRules.groupFor("nas.local", listOf(local), emptyList())) }
}
